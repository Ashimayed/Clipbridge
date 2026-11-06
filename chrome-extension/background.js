import * as D from './drive.js';
import * as C from './crypto.js';
import * as S from './store.js';
import { pollIntervalMs, jittered, normalizeMode, redact, cleanDeviceName, DEFAULT_MODE, MAX_PINS } from './logic.js';

const OFFSCREEN_URL = 'offscreen.html';

// ================================================================ state
// Service workers are killed when idle, so everything durable lives in storage.

const DEFAULTS = {
  authOk: false, email: null, seenIds: null, lastHash: null, suppressImageUntil: 0,
  autoSend: true, keyB64: null, keyCheck: null, failCount: 0, nextPollAt: 0,
  syncMode: DEFAULT_MODE, lastActivityAt: 0,
};
async function getState() {
  return { ...DEFAULTS, ...(await chrome.storage.local.get(Object.keys(DEFAULTS))) };
}
const setState = (patch) => chrome.storage.local.set(patch);

// "Something happened" clock, kept separately from DEFAULTS so a debug-log read never has to
// pull it. Fresh activity (a send, a receive, the popup opening) makes polling fast again.
async function touchNow() { await setState({ lastActivityAt: Date.now() }); }
/** Lets a clip's own timestamp count as activity too, so a busy OTHER device keeps this one polling fast. */
async function bumpActivity(iso) {
  const t = Date.parse(iso);
  if (!Number.isFinite(t) || t > Date.now()) return; // never trust a bad or future timestamp
  const { lastActivityAt = 0 } = await chrome.storage.local.get('lastActivityAt');
  if (t > lastActivityAt) await setState({ lastActivityAt: t });
}

// ================================================================ debug log
// A short, redacted record of what this extension has been doing, for the "Copy debug info"
// button. Every line goes through logic.js redact() before it's stored, so it can never contain
// your passphrase, your clips, or an access token — safe to paste into an email or a bug report.

const LOG_MAX = 150;
async function pushLog(line) {
  try {
    const { debugLog = [] } = await chrome.storage.local.get('debugLog');
    debugLog.push(`${new Date().toTimeString().slice(0, 8)}  ${redact(String(line))}`);
    while (debugLog.length > LOG_MAX) debugLog.shift();
    await chrome.storage.local.set({ debugLog });
  } catch (e) { console.warn('pushLog:', e); }
}
async function getLog(st) {
  const { debugLog = [] } = await chrome.storage.local.get('debugLog');
  const header = [
    'ClipBridge debug log',
    `Chrome extension v${chrome.runtime.getManifest().version}`,
    `Chrome ${/Chrome\/([\d.]+)/.exec(navigator.userAgent)?.[1] || '?'}`,
    `Sync mode: ${st.syncMode}, auto-send: ${st.autoSend ? 'on' : 'off'}`,
    '----------',
  ];
  return header.concat(debugLog.length ? debugLog : ['(nothing logged yet this session)']).join('\n');
}

// ================================================================ offscreen page

let creating = null;
async function ensureOffscreen() {
  const url = chrome.runtime.getURL(OFFSCREEN_URL);
  const existing = await chrome.runtime.getContexts({ contextTypes: ['OFFSCREEN_DOCUMENT'], documentUrls: [url] });
  if (existing.length) return;
  if (!creating) {
    creating = chrome.offscreen.createDocument({
      url: OFFSCREEN_URL,
      reasons: ['CLIPBOARD'],
      justification: 'Watch and update the clipboard to keep it in sync with your phone',
    }).catch((e) => {
      if (!String(e?.message).includes('single offscreen')) console.warn('offscreen:', e);
    }).finally(() => { creating = null; });
  }
  await creating;
}

const toOffscreen = async (msg) => {
  await ensureOffscreen();
  return chrome.runtime.sendMessage({ target: 'offscreen', ...msg });
};

// ================================================================ notifications (always silent)

const notified = new Set();
async function notify(id, title, message, once = false) {
  if (once && notified.has(id)) return;
  notified.add(id);
  try {
    await chrome.notifications.create(id, {
      type: 'basic', iconUrl: 'icons/icon128.png', title, message: message || ' ', silent: true, priority: 0,
    });
  } catch (e) { console.warn('notify:', e); }
}

chrome.notifications.onClicked.addListener(async (id) => {
  const { dlMap = {} } = await chrome.storage.session.get('dlMap');
  if (dlMap[id] != null) { try { chrome.downloads.show(dlMap[id]); } catch { /* file gone */ } }
  chrome.notifications.clear(id);
});

async function setBadge() {
  const st = await getState();
  const problem = !st.authOk || !st.keyB64;
  chrome.action.setBadgeText({ text: problem ? '!' : st.autoSend ? '' : '||' });
  chrome.action.setBadgeBackgroundColor({ color: problem ? '#B3261E' : '#5B6472' });
}

// ================================================================ auth

async function markSignedOut(reason) {
  const st = await getState();
  if (st.authOk) {
    await setState({ authOk: false });
    await notify('auth', 'Sign in to ClipBridge again', 'Click the ClipBridge icon in the toolbar.');
  }
  await setBadge();
  console.warn('signed out:', reason);
}

async function signIn() {
  await D.getToken(true);
  const email = await D.email();
  const st = await getState();
  const sameAccount = st.email === email;
  await setState({
    authOk: true, email, failCount: 0, nextPollAt: 0,
    seenIds: sameAccount ? st.seenIds : null,
    keyB64: sameAccount ? st.keyB64 : null, keyCheck: sameAccount ? st.keyCheck : null, // key is tied to the account
  });
  chrome.notifications.clear('auth');
  await setBadge();
  await ensureOffscreen();
  await pushLog(`Signed in as ${email}`);
  await touchNow();
  return email;
}

async function signOut() {
  try { await D.dropToken(await D.getToken(false)); } catch { /* already signed out */ }
  // Forget the key too: someone else signing in on this PC must not inherit it.
  await setState({ authOk: false, email: null, seenIds: null, lastHash: null, keyB64: null, keyCheck: null });
  await forgetLocalCopies();
  await setBadge();
}

/** Decrypted leftovers on this computer: image previews and any files still waiting to send. */
async function forgetLocalCopies() {
  await S.clearThumbs().catch(() => {});
  await S.clearQueue().catch(() => {});
  await chrome.storage.session.remove('transfers').catch(() => {});
}

// ================================================================ receiving

/**
 * How often to check Drive. Two things decide the wait, computed fresh after every round in
 * scheduleNextPoll(): logic.js pollIntervalMs() gives the normal pace for the current sync mode
 * and how long it's been since anything happened, and a separate error-streak multiplier
 * stretches that out further after consecutive failures — whichever of the two is longer wins,
 * so a struggling connection doesn't get hammered just because you're actively pasting things.
 */
async function scheduleNextPoll(failCount) {
  const st = await getState();
  const idle = Date.now() - (st.lastActivityAt || 0);
  const normal = jittered(pollIntervalMs(st.syncMode, idle), Math.random());
  const afterErrors = failCount ? Math.min(3000 * 2 ** (failCount - 1), 60000) : 0;
  await setState({ nextPollAt: Date.now() + Math.max(normal, afterErrors) });
}

/**
 * The actual Drive round-trip. Returns normally (including "nothing new") or throws.
 *
 * Everything new from another device is handled, oldest first: every file and image is saved
 * (so sending five photos delivers five photos), but only the NEWEST text goes on the
 * clipboard, since each text would just overwrite the one before it. Older texts stay in history.
 */
async function pollOnce(st) {
  const key = await D.getKey();
  const { deviceId } = await D.identity();
  const clips = await D.listClips(D.MAX_VISIBLE + 20); // headroom over what prune() keeps steady-state
  if (clips.length) await bumpActivity(clips[0].createdTime);

  if (!st.seenIds) { await setState({ seenIds: clips.map((c) => c.id) }); return; } // first run: replay nothing
  const fresh = clips.filter((c) => !st.seenIds.includes(c.id));
  if (!fresh.length) return;

  const retry = new Set(); // ids to try again next round (offline mid-download)
  const incoming = [];
  for (const c of [...fresh].reverse()) { // oldest first
    if (c.origin === deviceId) continue;
    const opened = await D.openClip(key, c);
    if (!opened) {
      await pushLog("Couldn't unlock an incoming clip: passphrase mismatch");
      await notify(`bad-${c.id}`, "A clip couldn't be unlocked",
        'Your devices seem to use different passphrases. Compare the key check codes in ClipBridge settings.', true);
      continue;
    }
    incoming.push({ c, opened });
  }
  const isClipboardText = ({ opened: { header: h } }) => h.kind === 'text' && h.size <= D.MAX_CLIPBOARD_TEXT;
  const newestText = incoming.filter(isClipboardText).pop();

  let failure = null;
  for (const item of incoming) {
    if (isClipboardText(item) && item !== newestText) continue; // superseded by a newer text
    try {
      await applyClip(key, item.c, item.opened);
      await pushLog(`Received a ${item.opened.header.kind} from ${item.opened.header.device || 'another device'}`);
      await touchNow();
    } catch (e) {
      if (e instanceof D.TransientError || e instanceof D.AuthError) retry.add(item.c.id);
      else if (e instanceof C.DecryptError) await pushLog('Rejected an incoming clip: it was altered or used another passphrase');
      else await pushLog(`Couldn't apply an incoming clip: ${e?.message || e}`);
      failure ??= e;
      if (e instanceof D.AuthError) break; // nothing else will work until you sign in again
    }
  }
  const allSeen = [...fresh.map((c) => c.id), ...st.seenIds].filter((id) => !retry.has(id)).slice(0, 60);
  await setState({ seenIds: allSeen });
  if (failure) throw failure;
}

let polling = false;
async function pollDrive() {
  if (polling) return;
  const st0 = await getState();
  if (!st0.authOk || !st0.keyB64 || Date.now() < st0.nextPollAt) return;
  polling = true;
  try {
    await pollOnce(st0);
    await setState({ failCount: 0 });
    await scheduleNextPoll(0);
  } catch (e) {
    if (e instanceof D.AuthError) { await markSignedOut(e.message); polling = false; return; }
    // Known, isolated problems (not this device's connection): don't punish future polling speed.
    const benign = (e instanceof D.PermanentError && e.code === 404) || e instanceof C.DecryptError;
    if (e instanceof C.DecryptError) { // altered in Drive, or a different passphrase: never retry
      await notify('tampered', "A clip couldn't be unlocked", 'It was changed in Drive or sent with a different passphrase, so it was ignored.');
    } else if (!benign) {
      await pushLog(`Poll failed: ${e?.message || e}`);
      console.warn('poll:', e?.message || e);
    }
    const st = await getState();
    const failCount = benign ? (st.failCount || 0) : Math.min((st.failCount || 0) + 1, 10);
    await setState({ failCount });
    await scheduleNextPoll(failCount);
  } finally {
    polling = false;
  }
}

async function applyClip(key, clip, { prefix, header }, { quiet = false } = {}) {
  const from = header.device || 'your phone';
  if (header.kind === 'text' && header.size <= D.MAX_CLIPBOARD_TEXT) {
    const text = await D.readText(key, clip, { prefix, header });
    await setState({ lastHash: await D.sha256Text(text) });
    await toOffscreen({ type: 'write-text', text });
    if (!quiet) await notify(`in-${clip.id}`, `From ${from}, ready to paste`, text.slice(0, 180));
    return;
  }
  // Images, files and huge text go to Downloads/ClipBridge, decrypted in the offscreen page.
  const st = await getState();
  const r = await toOffscreen({
    type: 'decrypt-to-url',
    url: `${D.DRIVE}/files/${D.checkId(clip.id)}?alt=media`,
    token: await D.getToken(false),
    keyB64: st.keyB64,
    prefixB64: C.b64(prefix),
    mime: header.mime,
  });
  if (!r?.ok) throw r?.decrypt ? new D.PermanentError(r.error, 422) : new D.TransientError(r?.error || 'Decrypt failed');
  const name = header.kind === 'text' ? `clip-${Date.now()}.txt` : D.safeName(header.name);
  const downloadId = await chrome.downloads.download({
    url: r.blobUrl, filename: `ClipBridge/${name}`, conflictAction: 'uniquify', saveAs: false,
  });
  const nid = `in-${clip.id}`;
  const { dlMap = {} } = await chrome.storage.session.get('dlMap');
  const keep = Object.fromEntries(Object.entries(dlMap).slice(-20)); // bounded
  await chrome.storage.session.set({ dlMap: { ...keep, [nid]: downloadId } });
  const what = header.kind === 'image' ? 'Image' : name;
  if (!quiet) await notify(nid, `From ${from}`, `${what} saved to Downloads/ClipBridge. Click to show it.`);
}

/** "Save" on a file in the popup's history. Runs here so a big download survives the popup closing. */
async function saveFromHistory(id) {
  D.checkId(id);
  const key = await D.getKey();
  const clip = (await D.listClips(D.MAX_VISIBLE + 20)).find((c) => c.id === id);
  if (!clip) throw new D.PermanentError('That clip no longer exists', 404);
  const opened = await D.openClip(key, clip);
  if (!opened) throw new C.DecryptError("Can't unlock this clip");
  await applyClip(key, clip, opened, { quiet: true });
}

// ================================================================ sending

let queue = Promise.resolve();
const enqueue = (job) => (queue = queue.then(job).catch((e) => console.warn('send:', e)));

async function reportSendError(e, what) {
  await setState({ lastHash: null }); // allow a retry
  if (e instanceof D.AuthError) { await markSignedOut(e.message); return; }
  if (e instanceof D.NoKeyError) return;
  await pushLog(`${what} not sent: ${e?.message || e}`);
  await notify('err', `${what} not sent`, e?.message || 'Check your connection');
}

function sendLocalText(text, force = false) {
  return enqueue(async () => {
    const st = await getState();
    if (!text || !st.authOk || !st.keyB64 || (!st.autoSend && !force)) return;
    const hash = await D.sha256Text(text);
    if (hash === st.lastHash) return; // it's the clip we just received
    await setState({ lastHash: hash });
    await touchNow();
    try { await D.sendText(await D.getKey(), text, await D.identity(), hash); await pushLog('Sent a clip'); }
    catch (e) { await reportSendError(e, 'Clip'); }
  });
}

function sendLocalImage(dataUrl) {
  return enqueue(async () => {
    const st = await getState();
    if (!st.authOk || !st.keyB64 || !st.autoSend) return;
    if (Date.now() < (st.suppressImageUntil || 0)) return; // copied from our own history
    const mime = /^data:([^;,]+)/.exec(dataUrl)?.[1] || 'image/png';
    const raw = await (await fetch(dataUrl)).blob();
    const blob = raw.type === mime ? raw : new Blob([raw], { type: mime });
    const hash = await D.sha256Blob(blob);
    if (hash === st.lastHash) return;
    await setState({ lastHash: hash });
    await touchNow();
    const ext = (mime.split('/')[1] || 'png').replace('jpeg', 'jpg');
    try { await D.sendBlob(await D.getKey(), blob, `image-${Date.now()}.${ext}`, await D.identity()); await pushLog('Sent a clip'); }
    catch (e) { await reportSendError(e, 'Image'); }
  });
}

/** Text typed into the popup. An explicit choice: sends even if it's identical to the last clip. */
function sendTypedText(text) {
  return new Promise((resolve, reject) => enqueue(async () => {
    try {
      const st = await getState();
      if (!st.authOk) throw new D.AuthError('Sign in first');
      if (!text.trim()) throw new Error('Nothing to send');
      const hash = await D.sha256Text(text);
      await D.sendText(await D.getKey(), text, await D.identity(), hash);
      await setState({ lastHash: hash }); // copying the same text next won't send it twice
      await pushLog('Sent typed text');
      await touchNow();
      resolve();
    } catch (e) {
      if (e instanceof D.AuthError) await markSignedOut(e.message);
      reject(e);
    }
  }));
}

/** Files the popup put in the outbox: the offscreen page encrypts and uploads them one by one. */
async function sendQueuedFiles(ids) {
  const st = await getState();
  if (!st.authOk) throw new D.AuthError('Sign in first');
  if (!st.keyB64) throw new D.NoKeyError();
  if (!Array.isArray(ids) || !ids.every((id) => typeof id === 'string' && id.length <= 64)) throw new Error('Bad request');
  await touchNow();
  return toOffscreen({ type: 'upload', ids, keyB64: st.keyB64, who: await D.identity() });
}

/** Progress reports from the offscreen page, kept so a reopened popup can show them again. */
const transferWrites = { chain: Promise.resolve() };
function recordProgress(p) {
  transferWrites.chain = transferWrites.chain.then(async () => {
    const { transfers = {} } = await chrome.storage.session.get('transfers');
    if (p.state === 'sending') {
      transfers[p.id] = { name: p.name, size: p.size, sent: p.sent, total: p.total, at: Date.now() };
    } else {
      delete transfers[p.id];
    }
    await chrome.storage.session.set({ transfers });
    if (p.state === 'done') {
      await pushLog(`Sent a file (${Math.round((p.size || 0) / 1024)} KB)`);
      await touchNow();
      if (p.size >= 5 * 1024 * 1024) await notify(`sent-${p.id}`, 'Sent to your other devices', p.name);
    } else if (p.state === 'failed') {
      await pushLog(`File not sent: ${p.error}`);
      if (p.auth) await markSignedOut(p.error);
      else await notify(`fail-${p.id}`, `${p.name} not sent`, p.error || 'Check your connection');
    }
  }).catch((e) => console.warn('progress:', e));
}

async function deleteOne(id) {
  await D.deleteClip(D.checkId(id));
  const st = await getState();
  if (st.seenIds) await setState({ seenIds: st.seenIds.filter((x) => x !== id) });
  await pushLog('Deleted a clip');
  await touchNow();
}

/** Enforces the pin cap, then flips the flag. Errors surface to whoever asked (usually the popup). */
async function pinClip(id, pinned) {
  if (pinned) {
    const all = await D.listClips(100);
    if (all.filter((c) => c.pinned).length >= MAX_PINS) throw new Error(`You can pin up to ${MAX_PINS} clips. Unpin one first.`);
  }
  await D.setPinned(id, pinned);
  await pushLog(`Clip ${pinned ? 'pinned' : 'unpinned'}`);
  await touchNow();
}

// ================================================================ messages
// Only our own extension pages can talk to us (no externally_connectable, and a sender check).

const OFFSCREEN_PAGE = chrome.runtime.getURL(OFFSCREEN_URL);

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (sender.id !== chrome.runtime.id) return;
  if (msg?.target === 'progress' && sender.url === OFFSCREEN_PAGE) { recordProgress(msg); return; }
  if (msg?.target !== 'bg') return;
  const reply = (p) => { p.then((v) => sendResponse(v ?? { ok: true }), (e) => sendResponse({ ok: false, error: e?.message || String(e) })); return true; };
  switch (msg.type) {
    case 'tick': pollDrive(); return;
    case 'local-text': sendLocalText(msg.value); return;
    case 'local-image': sendLocalImage(msg.value); return;
    case 'image-too-big':
      notify('big', 'Large image not sent automatically', 'Open ClipBridge and paste it there to send it.', true);
      return;
    case 'sign-in': return reply(signIn().then((email) => ({ ok: true, email })));
    case 'sign-out': return reply(signOut());
    case 'send-text': return reply(sendTypedText(String(msg.text ?? '')));
    case 'send-files': return reply(sendQueuedFiles(msg.ids));
    case 'delete-clip': return reply(deleteOne(msg.id));
    case 'save-clip': return reply(saveFromHistory(msg.id));
    case 'set-device-name': {
      const name = cleanDeviceName(msg.name);
      return reply(setState({ deviceName: name }).then(() => pushLog('Device name changed')).then(() => ({ ok: true, name })));
    }
    case 'get-token':
      // Only the offscreen page (which does the uploads) ever gets a token from here.
      if (sender.url !== OFFSCREEN_PAGE) return;
      return reply(D.getToken(false).then((token) => ({ ok: true, token })));
    case 'drop-token':
      if (sender.url !== OFFSCREEN_PAGE || typeof msg.token !== 'string') return;
      return reply(chrome.identity.removeCachedAuthToken({ token: msg.token }));
    case 'mark-hash': return reply(setState({ lastHash: msg.hash }));
    case 'suppress-image': return reply(setState({ suppressImageUntil: Date.now() + 10000 }));
    case 'save-key':
      return reply(setState({ keyB64: msg.keyB64, keyCheck: msg.keyCheck, failCount: 0, nextPollAt: 0, seenIds: null })
        .then(() => S.clearThumbs().catch(() => {})).then(setBadge).then(() => pushLog('Passphrase saved')));
    case 'set-auto-send': return reply(setState({ autoSend: !!msg.on }).then(setBadge));
    case 'set-sync-mode': return reply(setState({ syncMode: normalizeMode(msg.mode) }));
    case 'set-pinned': return reply(pinClip(msg.id, !!msg.pinned));
    case 'get-debug-log': return reply(getState().then((s) => getLog(s)).then((text) => ({ ok: true, text })));
    case 'delete-all':
      return reply(D.deleteAll().then(() => setState({ seenIds: [], lastHash: null })).then(() => S.clearThumbs().catch(() => {})));
    case 'status':
      return reply((async () => {
        await touchNow();
        const s = await getState();
        const { deviceName = '' } = await chrome.storage.local.get('deviceName');
        return {
          authOk: s.authOk, email: s.email, hasKey: !!s.keyB64, keyCheck: s.keyCheck, autoSend: s.autoSend, syncMode: s.syncMode,
          deviceName, defaultDeviceName: D.defaultDeviceLabel(), version: chrome.runtime.getManifest().version,
        };
      })());
  }
});

// ================================================================ right-click menu

function setupMenus() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: 'cb-selection', title: 'Send selected text to phone', contexts: ['selection'] });
    chrome.contextMenus.create({ id: 'cb-link', title: 'Send link to phone', contexts: ['link'] });
    chrome.contextMenus.create({ id: 'cb-page', title: 'Send this page to phone', contexts: ['page'] });
  });
}

chrome.contextMenus.onClicked.addListener((info, tab) => {
  const text =
    info.menuItemId === 'cb-selection' ? info.selectionText :
    info.menuItemId === 'cb-link' ? info.linkUrl :
    info.menuItemId === 'cb-page' ? (tab?.url || info.pageUrl) : null;
  if (text) sendLocalText(text, true); // an explicit choice: sends even when auto-send is paused
});

// ================================================================ lifecycle

async function boot() {
  await setBadge();
  await ensureOffscreen();
  chrome.alarms.create('keepalive', { periodInMinutes: 1 });
}

chrome.runtime.onInstalled.addListener(() => { setupMenus(); boot(); });
// A browser restart cancels any upload that was running: drop the unencrypted copies it left.
chrome.runtime.onStartup.addListener(() => { S.clearQueue().catch(() => {}); chrome.storage.session.remove('transfers'); boot(); });
chrome.alarms.onAlarm.addListener((a) => { if (a.name === 'keepalive') ensureOffscreen(); });
