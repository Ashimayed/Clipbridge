import * as D from './drive.js';
import * as C from './crypto.js';

const OFFSCREEN_URL = 'offscreen.html';

// ================================================================ state
// Service workers are killed when idle, so everything durable lives in storage.

const DEFAULTS = {
  authOk: false, email: null, seenIds: null, lastHash: null, suppressImageUntil: 0,
  autoSend: true, keyB64: null, keyCheck: null, failCount: 0, nextPollAt: 0,
};
async function getState() {
  return { ...DEFAULTS, ...(await chrome.storage.local.get(Object.keys(DEFAULTS))) };
}
const setState = (patch) => chrome.storage.local.set(patch);

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
  return email;
}

async function signOut() {
  try { await D.dropToken(await D.getToken(false)); } catch { /* already signed out */ }
  // Forget the key too: someone else signing in on this PC must not inherit it.
  await setState({ authOk: false, email: null, seenIds: null, lastHash: null, keyB64: null, keyCheck: null });
  await setBadge();
}

// ================================================================ receiving

let polling = false;
async function pollDrive() {
  if (polling) return;
  const st = await getState();
  if (!st.authOk || !st.keyB64 || Date.now() < st.nextPollAt) return;
  polling = true;
  try {
    const key = await D.getKey();
    const { deviceId } = await D.identity();
    const clips = await D.listClips();
    if (st.failCount) await setState({ failCount: 0, nextPollAt: 0 });

    if (!st.seenIds) { await setState({ seenIds: clips.map((c) => c.id) }); return; } // first run: replay nothing
    const fresh = clips.filter((c) => !st.seenIds.includes(c.id));
    if (!fresh.length) return;
    const allSeen = [...fresh.map((c) => c.id), ...st.seenIds].slice(0, 60);

    const newest = clips[0];
    if (newest.origin === deviceId || !fresh.some((c) => c.id === newest.id)) { await setState({ seenIds: allSeen }); return; }
    const opened = await D.openClip(key, newest);
    if (!opened) {
      await setState({ seenIds: allSeen });
      await notify(`bad-${newest.id}`, "A clip couldn't be unlocked",
        'Your devices seem to use different passphrases. Compare the key check codes in ClipBridge settings.', true);
      return;
    }
    try {
      await applyClip(key, newest, opened);
      await setState({ seenIds: allSeen });
    } catch (e) {
      // Offline mid-download: leave it unseen so the next round tries again.
      const retry = e instanceof D.TransientError || e instanceof D.AuthError;
      await setState({ seenIds: retry ? allSeen.filter((id) => id !== newest.id) : allSeen });
      throw e;
    }
  } catch (e) {
    if (e instanceof D.AuthError) { await markSignedOut(e.message); return; }
    if (e instanceof C.DecryptError) { // altered in Drive, or a different passphrase: never retry
      await notify('tampered', "A clip couldn't be unlocked", 'It was changed in Drive or sent with a different passphrase, so it was ignored.');
      return;
    }
    if (e instanceof D.PermanentError && e.code === 404) return; // replaced by the other device
    const failCount = (st.failCount || 0) + 1; // back off: 6 s, 12 s, 24 s ... max 60 s
    await setState({ failCount, nextPollAt: Date.now() + Math.min(60000, 3000 * 2 ** failCount) });
    console.warn('poll:', e?.message || e);
  } finally {
    polling = false;
  }
}

async function applyClip(key, clip, { prefix, header }) {
  const from = header.device || 'your phone';
  if (header.kind === 'text' && header.size <= D.MAX_CLIPBOARD_TEXT) {
    const text = await D.readText(key, clip, { prefix, header });
    await setState({ lastHash: await D.sha256Text(text) });
    await toOffscreen({ type: 'write-text', text });
    await notify(`in-${clip.id}`, `From ${from}, ready to paste`, text.slice(0, 180));
    return;
  }
  // Images, files and huge text go to Downloads/ClipBridge, decrypted in the offscreen page.
  const st = await getState();
  const r = await toOffscreen({
    type: 'decrypt-to-url',
    url: `${D.DRIVE}/files/${clip.id}?alt=media`,
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
  await notify(nid, `From ${from}`, `${what} saved to Downloads/ClipBridge. Click to show it.`);
}

// ================================================================ sending

let queue = Promise.resolve();
const enqueue = (job) => (queue = queue.then(job).catch((e) => console.warn('send:', e)));

async function reportSendError(e, what) {
  await setState({ lastHash: null }); // allow a retry
  if (e instanceof D.AuthError) await markSignedOut(e.message);
  else if (!(e instanceof D.NoKeyError)) await notify('err', `${what} not sent`, e?.message || 'Check your connection');
}

function sendLocalText(text, force = false) {
  return enqueue(async () => {
    const st = await getState();
    if (!text || !st.authOk || !st.keyB64 || (!st.autoSend && !force)) return;
    const hash = await D.sha256Text(text);
    if (hash === st.lastHash) return; // it's the clip we just received
    await setState({ lastHash: hash });
    try { await D.sendText(await D.getKey(), text, await D.identity(), hash); }
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
    const ext = (mime.split('/')[1] || 'png').replace('jpeg', 'jpg');
    try { await D.sendBlob(await D.getKey(), blob, `image-${Date.now()}.${ext}`, await D.identity()); }
    catch (e) { await reportSendError(e, 'Image'); }
  });
}

// ================================================================ messages
// Only our own extension pages can talk to us (no externally_connectable, and a sender check).

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (sender.id !== chrome.runtime.id || msg?.target !== 'bg') return;
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
    case 'send-text': return reply(sendLocalText(msg.text, true));
    case 'mark-hash': return reply(setState({ lastHash: msg.hash }));
    case 'suppress-image': return reply(setState({ suppressImageUntil: Date.now() + 10000 }));
    case 'save-key':
      return reply(setState({ keyB64: msg.keyB64, keyCheck: msg.keyCheck, failCount: 0, nextPollAt: 0, seenIds: null }).then(setBadge));
    case 'set-auto-send': return reply(setState({ autoSend: !!msg.on }).then(setBadge));
    case 'delete-all':
      return reply(D.deleteAll().then(() => setState({ seenIds: [], lastHash: null })));
    case 'status':
      return reply(getState().then((s) => ({
        authOk: s.authOk, email: s.email, hasKey: !!s.keyB64, keyCheck: s.keyCheck, autoSend: s.autoSend,
      })));
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
chrome.runtime.onStartup.addListener(boot);
chrome.alarms.onAlarm.addListener((a) => { if (a.name === 'keepalive') ensureOffscreen(); });
