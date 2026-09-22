import * as D from './drive.js';
import * as C from './crypto.js';
import { SYNC_MODES, normalizeMode, buildPairing } from './logic.js';
import qrcode from './qr.js';

const $ = (id) => document.getElementById(id);
const bg = (msg) => chrome.runtime.sendMessage({ target: 'bg', ...msg });
const VIEWS = ['viewSignIn', 'viewPass', 'viewDone', 'viewMain', 'viewSettings', 'viewSync', 'viewQr'];
const objectUrls = [];
let status = {};
let pendingKey = null;
let passFromSettings = false;
let loadSeq = 0;
let searchQuery = '';

/** Exactly one screen is visible at any time. */
function show(view) {
  for (const v of VIEWS) $(v).hidden = v !== view;
}

let toastTimer;
function toast(text, { kind = '', ms = 2200 } = {}) {
  const t = $('toast');
  t.textContent = text;
  t.className = `show ${kind}`;
  clearTimeout(toastTimer);
  if (ms) toastTimer = setTimeout(() => { t.className = ''; }, ms);
}

async function refreshStatus() {
  status = (await bg({ type: 'status' })) || {};
  const on = !!status.autoSend;
  $('autoSend').setAttribute('aria-checked', String(on));
  $('dot').className = on ? 'dot' : 'dot off';
  $('dot').title = on ? `Syncing as ${status.email || ''}` : 'Auto-send is paused';
}

async function route() {
  await refreshStatus();
  if (!status.authOk) return show('viewSignIn');
  if (!status.hasKey) return openPass(false);
  show('viewMain');
  loadHistory();
}

// ================================================================ sign in

$('signIn').addEventListener('click', async () => {
  $('authError').textContent = '';
  $('signIn').disabled = true;
  $('signIn').textContent = 'Opening Google…';
  const r = await bg({ type: 'sign-in' });
  $('signIn').disabled = false;
  $('signIn').textContent = 'Continue with Google';
  if (r?.ok) return route();
  const e = r?.error || 'unknown error';
  $('authError').textContent = /not signed in/i.test(e)
    ? 'Sign Chrome itself in to your Google account first (profile icon, top right), then try again.'
    : `Sign-in failed: ${e}`;
});

// ================================================================ passphrase

function openPass(fromSettings) {
  passFromSettings = fromSettings;
  pendingKey = null;
  $('passBack').hidden = !fromSettings;
  $('warn').hidden = true;
  $('passStatus').textContent = '';
  $('passHint').className = 'hint';
  $('pass1').value = '';
  $('pass2').value = '';
  show('viewPass');
  $('pass1').focus();
}

async function commitKey(raw) {
  const code = await C.keyCheck(raw);
  await bg({ type: 'save-key', keyB64: C.b64(raw), keyCheck: code });
  $('pass1').value = '';
  $('pass2').value = '';
  pendingKey = null;
  $('doneCode').textContent = code;
  show('viewDone');
}

async function savePass() {
  const a = $('pass1').value;
  const b = $('pass2').value;
  $('warn').hidden = true;
  if (a.length < 10) { $('passHint').className = 'hint error'; $('pass1').focus(); return; }
  $('passHint').className = 'hint';
  if (a !== b) { $('passStatus').textContent = "The two passphrases don't match."; return; }
  $('savePass').disabled = true;
  $('passStatus').textContent = 'Checking…';
  try {
    const raw = await C.deriveKey(a, status.email || '');
    // Compare with what the phone has been using, if it has sent anything yet.
    let verdict = 'unknown';
    try {
      const clips = (await D.listClips()).filter((c) => c.description).slice(0, 3);
      if (!clips.length) verdict = 'none';
      else {
        const key = await C.importKey(raw);
        const opens = await Promise.all(clips.map((c) => C.openHeader(key, c.description).then(() => true, () => false)));
        verdict = opens.some(Boolean) ? 'match' : 'different';
      }
    } catch { /* offline: can't compare, carry on */ }
    if (verdict === 'different') {
      pendingKey = raw;
      $('warn').hidden = false;
      $('passStatus').textContent = '';
    } else {
      await commitKey(raw);
    }
  } catch (e) {
    $('passStatus').textContent = `Couldn't save: ${e.message}`;
  } finally {
    $('savePass').disabled = false;
  }
}
$('savePass').addEventListener('click', savePass);
for (const id of ['pass1', 'pass2']) $(id).addEventListener('keydown', (e) => { if (e.key === 'Enter') savePass(); });
$('useAnyway').addEventListener('click', () => { if (pendingKey) commitKey(pendingKey); });
$('passBack').addEventListener('click', () => openSettings());
$('doneBtn').addEventListener('click', () => route());

// ================================================================ header + settings

$('autoSend').addEventListener('click', async () => {
  const on = $('autoSend').getAttribute('aria-checked') !== 'true';
  await bg({ type: 'set-auto-send', on });
  await refreshStatus();
  toast(on ? 'Auto-send on' : 'Auto-send paused. Copies stay on this PC.');
});

async function openSettings() {
  await refreshStatus();
  $('setEmail').textContent = status.email || '';
  $('setCode').textContent = status.keyCheck || '';
  $('setSyncMode').textContent = SYNC_MODES[normalizeMode(status.syncMode)].label;
  show('viewSettings');
}
$('openSettings').addEventListener('click', openSettings);
$('settingsBack').addEventListener('click', () => route());
$('changePass').addEventListener('click', () => openPass(true));
$('openSync').addEventListener('click', openSync);
$('syncBack').addEventListener('click', openSettings);
$('openQr').addEventListener('click', openQr);
$('qrBack').addEventListener('click', closeQr);
$('qrHide').addEventListener('click', closeQr);
$('copyDebug').addEventListener('click', async () => {
  const r = await bg({ type: 'get-debug-log' });
  if (!r?.ok) { toast(`Couldn't get the log: ${r?.error}`, { kind: 'err', ms: 4000 }); return; }
  await navigator.clipboard.writeText(r.text);
  toast('Debug info copied');
});

// ================================================================ sync speed

function openSync() {
  const box = $('syncOptions');
  box.replaceChildren();
  const current = normalizeMode(status.syncMode);
  for (const [id, mode] of Object.entries(SYNC_MODES)) {
    const btn = el('button', 'item sync-option' + (id === current ? ' selected' : ''));
    const label = el('div', 'sync-label');
    label.append(el('div', null, mode.label), el('div', 'hint', mode.hint));
    btn.append(label);
    if (id === current) btn.append(icon('check', 18));
    btn.addEventListener('click', async () => {
      await bg({ type: 'set-sync-mode', mode: id });
      status.syncMode = id;
      openSettings();
    });
    box.append(btn);
  }
  show('viewSync');
}

// ================================================================ QR pairing
// Chrome already has the key; this lets a phone join without retyping the passphrase.
// The key is only ever shown for a short window, and never persists once this closes.

const QR_SECONDS = 60;
let qrTimer = null;

function closeQr() {
  clearInterval(qrTimer);
  qrTimer = null;
  $('qrImg').src = '';
  $('qrCode').textContent = '';
  show('viewSettings');
}

async function openQr() {
  show('viewQr');
  $('qrCountdown').textContent = '';
  $('qrImg').src = '';
  $('qrCode').textContent = '';
  try {
    const raw = C.unb64(await D.getRawKeyB64());
    const pairing = await buildPairing(raw, status.email || '');
    const qr = qrcode(0, 'M');
    qr.addData(pairing);
    qr.make();
    $('qrImg').src = qr.createDataURL(6, 4);
    $('qrCode').textContent = await C.keyCheck(raw);
  } catch (e) {
    toast(`Couldn't build the code: ${e.message}`, { kind: 'err', ms: 4000 });
    show('viewSettings');
    return;
  }
  let remaining = QR_SECONDS;
  const tick = () => {
    if (remaining <= 0) { closeQr(); return; }
    $('qrCountdown').textContent = `Hides itself in ${remaining}s`;
    remaining--;
  };
  tick();
  clearInterval(qrTimer);
  qrTimer = setInterval(tick, 1000);
}

/** Destructive actions need a second click within 4 s (no ugly native dialogs). */
function twoStep(btn, label, confirmLabel, action) {
  let timer;
  btn.addEventListener('click', async () => {
    if (btn.dataset.armed) {
      clearTimeout(timer);
      btn.dataset.armed = '';
      btn.textContent = label;
      await action();
      return;
    }
    btn.dataset.armed = '1';
    btn.textContent = confirmLabel;
    timer = setTimeout(() => { btn.dataset.armed = ''; btn.textContent = label; }, 4000);
  });
}
twoStep($('deleteAll'), 'Delete all clips', 'Tap again to delete everything', async () => {
  const r = await bg({ type: 'delete-all' });
  if (r?.ok) { toast('All clips deleted'); route(); } else toast(`Couldn't delete: ${r?.error}`, { kind: 'err', ms: 4000 });
});
twoStep($('signOut'), 'Sign out', 'Tap again to sign out', async () => {
  await bg({ type: 'sign-out' });
  route();
});

// ================================================================ sending

const composerText = $('text');
function syncComposer() {
  composerText.style.height = 'auto';
  composerText.style.height = `${Math.min(composerText.scrollHeight, 96)}px`;
  $('send').disabled = !composerText.value.trim();
}
composerText.addEventListener('input', syncComposer);
composerText.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); if (!$('send').disabled) $('send').click(); }
});

$('send').addEventListener('click', async () => {
  const text = composerText.value;
  if (!text.trim()) return;
  $('send').disabled = true;
  toast('Sending…', { ms: 0 });
  try {
    const hash = await D.sha256Text(text);
    await D.sendText(await D.getKey(), text, await D.identity(), hash);
    await bg({ type: 'mark-hash', hash });
    composerText.value = '';
    syncComposer();
    toast('Sent to your phone');
    loadHistory();
  } catch (e) {
    toast(`Not sent: ${e.message}`, { kind: 'err', ms: 4000 });
    syncComposer();
  }
});

async function sendFiles(files) {
  const list = [...files];
  if (!list.length) return;
  const who = await D.identity();
  const key = await D.getKey();
  for (let i = 0; i < list.length; i++) {
    const f = list[i];
    toast(list.length > 1 ? `Sending ${i + 1} of ${list.length}…` : `Sending ${f.name || 'image'}…`, { ms: 0 });
    try {
      await D.sendBlob(key, f, f.name || `image-${Date.now()}.png`, who);
      await bg({ type: 'mark-hash', hash: await D.sha256Blob(f) });
    } catch (e) { toast(`Not sent: ${e.message}`, { kind: 'err', ms: 4000 }); return; }
  }
  toast(list.length > 1 ? `Sent ${list.length} items` : 'Sent to your phone');
  loadHistory();
}
$('attach').addEventListener('click', () => $('files').click());
$('files').addEventListener('change', (e) => { sendFiles(e.target.files); e.target.value = ''; });
// Ctrl+V of an image or a file copied in Explorer, or dragging files onto the popup.
document.addEventListener('paste', (e) => {
  if ($('viewMain').hidden) return;
  const files = e.clipboardData?.files;
  if (files && files.length) { e.preventDefault(); sendFiles(files); }
});
document.addEventListener('dragover', (e) => { if (!$('viewMain').hidden) e.preventDefault(); });
document.addEventListener('drop', (e) => {
  if ($('viewMain').hidden) return;
  e.preventDefault();
  if (e.dataTransfer?.files?.length) sendFiles(e.dataTransfer.files);
});

$('search').addEventListener('input', () => {
  searchQuery = $('search').value.trim().toLowerCase();
  renderList();
});

// ================================================================ history

const SVG = {
  link: '<path d="M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1"/><path d="M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1"/>',
  file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2.5"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  copy: '<rect x="8.5" y="8.5" width="11" height="12" rx="2.5"/><path d="M15.5 8.5V6a2.5 2.5 0 0 0-2.5-2.5H7A2.5 2.5 0 0 0 4.5 6v7A2.5 2.5 0 0 0 7 15.5h1.5"/>',
  save: '<path d="M12 4v11"/><path d="M7 10l5 5 5-5"/><path d="M4 18h16"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  pin: '<path d="M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7z"/><path d="M12 6.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5z"/>',
};
// Only fixed strings from this file are ever used as SVG markup, never clip content.
function icon(name, size = 17) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  for (const [k, v] of Object.entries({ width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 1.9, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' })) s.setAttribute(k, v);
  s.innerHTML = SVG[name];
  return s;
}
const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = text; return n; };

const timeOf = (iso) => { const d = new Date(iso); return isNaN(d) ? '' : d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }); };
const sizeOf = (n) => (n < 1024 ? `${n} B` : n < 1048576 ? `${Math.round(n / 1024)} KB` : `${(n / 1048576).toFixed(1)} MB`);

async function toPng(blob) {
  if (blob.type === 'image/png') return blob;
  const bmp = await createImageBitmap(blob);
  const canvas = new OffscreenCanvas(bmp.width, bmp.height);
  canvas.getContext('2d').drawImage(bmp, 0, 0);
  return canvas.convertToBlob({ type: 'image/png' });
}

async function copyClip(key, clip, opened, row) {
  row.disabled = true;
  try {
    const { header } = opened;
    let message = 'Copied';
    if (header.kind === 'text' && header.size <= D.MAX_CLIPBOARD_TEXT) {
      const text = await D.readText(key, clip, opened);
      await bg({ type: 'mark-hash', hash: await D.sha256Text(text) }); // never send it back out
      await navigator.clipboard.writeText(text);
    } else if (header.kind === 'image') {
      await bg({ type: 'suppress-image' });
      await navigator.clipboard.write([new ClipboardItem({ 'image/png': await toPng(await D.readBlob(key, clip, opened)) })]);
      message = 'Image copied';
    } else {
      const url = URL.createObjectURL(await D.readBlob(key, clip, opened));
      objectUrls.push(url);
      const name = header.kind === 'text' ? `clip-${Date.now()}.txt` : D.safeName(header.name);
      await chrome.downloads.download({ url, filename: `ClipBridge/${name}`, conflictAction: 'uniquify' });
      message = 'Saved to Downloads/ClipBridge';
    }
    toast(message);
    row.classList.add('done');
    row.querySelector('.act').replaceChildren(icon('check'));
    setTimeout(() => {
      row.classList.remove('done');
      row.querySelector('.act')?.replaceChildren(icon(header.kind === 'file' ? 'save' : 'copy'));
    }, 1600);
  } catch (e) {
    toast(`Couldn't get it: ${e.message}`, { kind: 'err', ms: 4000 });
  } finally {
    row.disabled = false;
  }
}

let currentKey = null;
let clipCache = []; // [{ clip, opened, deviceId, preview }] — the last successful fetch, decrypted once

function matchesSearch(entry) {
  if (!searchQuery) return true;
  const h = entry.opened?.header;
  if (!h) return 'locked clip'.includes(searchQuery);
  return [h.name, h.device, h.kind, entry.preview].filter(Boolean).join(' ').toLowerCase().includes(searchQuery);
}

async function loadHistory() {
  const my = ++loadSeq; // a newer load supersedes this one
  const ul = $('list');
  const empty = (text) => { ul.replaceChildren(el('li', 'empty', text)); };
  let key, clips;
  try {
    key = await D.getKey();
    clips = (await D.listClips(D.MAX_VISIBLE + 10)).slice(0, D.MAX_VISIBLE); // small headroom, same idea as the Android app
  } catch (e) {
    if (my === loadSeq) empty(e instanceof D.AuthError ? 'Sign in again to see your clips.' : `Can't reach Drive: ${e.message}`);
    return;
  }
  const { deviceId } = await D.identity();
  const opened = await Promise.all(clips.map((c) => D.openClip(key, c)));
  // Text previews are decrypted up front (small, fast) so the search box can match on content
  // from the moment the list appears, not just once each row's async render catches up.
  const previews = await Promise.all(clips.map(async (c, i) => {
    const o = opened[i];
    if (o?.header.kind !== 'text' || o.header.size > 64000) return null;
    try { return (await D.readText(key, c, o)).replace(/\s+/g, ' ').trim(); } catch { return null; }
  }));
  if (my !== loadSeq) return;
  objectUrls.splice(0).forEach((u) => URL.revokeObjectURL(u));
  currentKey = key;
  clipCache = clips.map((c, i) => ({ clip: c, opened: opened[i], deviceId, preview: previews[i] }));
  renderList();
}

function renderList() {
  const ul = $('list');
  const empty = (text) => { ul.replaceChildren(el('li', 'empty', text)); };
  if (!clipCache.length) return empty('Nothing yet. Copy something here or on your phone.');
  const visible = clipCache.filter(matchesSearch);
  if (!visible.length) return empty('Nothing matches that search.');
  ul.replaceChildren();
  objectUrls.splice(0).forEach((u) => URL.revokeObjectURL(u)); // thumbnails get rebuilt below
  for (const entry of visible) buildRow(entry);
}

function buildRow({ clip: c, opened: o, deviceId, preview }) {
  const li = el('li', 'clip-row');
  const row = el('button', 'row');
  const tile = el('div', 'tile');
  const body = el('div', 'body');
  const title = el('div', 'clip-title');
  const meta = el('div', 'clip-meta');
  const act = el('span', 'act');
  body.append(title, meta);
  row.append(tile, body, act);

  const pinBtn = el('button', 'icon pin-btn' + (c.pinned ? ' pinned' : ''));
  pinBtn.setAttribute('aria-label', c.pinned ? 'Unpin this clip' : 'Pin this clip');
  pinBtn.setAttribute('aria-pressed', String(!!c.pinned));
  pinBtn.append(icon('pin', 16));
  pinBtn.addEventListener('click', async (ev) => {
    ev.stopPropagation();
    pinBtn.disabled = true;
    const r = await bg({ type: 'set-pinned', id: c.id, pinned: !c.pinned });
    if (!r?.ok) { pinBtn.disabled = false; toast(r?.error || "Couldn't update", { kind: 'err', ms: 4000 }); return; }
    loadHistory();
  });

  li.append(row, pinBtn);
  $('list').append(li);

  if (!o) {
    tile.append(icon('lock'));
    title.textContent = 'Locked clip';
    meta.textContent = 'Sent with a different passphrase';
    row.disabled = true;
    pinBtn.disabled = true; // nothing meaningful to verify locally before pinning it
    return;
  }
  const h = o.header;
  const from = c.origin === deviceId ? 'This computer' : h.device || 'Your phone';
  meta.textContent = h.kind === 'file' ? `${from} · ${sizeOf(h.size)} · ${timeOf(c.createdTime)}` : `${from} · ${timeOf(c.createdTime)}`;
  act.append(icon(h.kind === 'file' ? 'save' : 'copy'));
  row.setAttribute('aria-label', `${h.kind === 'file' ? 'Save' : 'Copy'} ${h.kind === 'text' ? 'text' : h.name}`);
  row.addEventListener('click', () => copyClip(currentKey, c, o, row));

  if (h.kind === 'text') {
    tile.textContent = 'Aa';
    title.textContent = preview?.slice(0, 200) || 'Text';
    if (preview && /^https?:\/\//.test(preview)) tile.replaceChildren(icon('link', 16));
  } else if (h.kind === 'image') {
    title.textContent = h.name;
    tile.append(icon('file', 16));
    if (h.size <= 3_000_000) {
      D.readBlob(currentKey, c, o).then((b) => {
        const url = URL.createObjectURL(b);
        objectUrls.push(url);
        const img = el('img'); img.alt = ''; img.src = url;
        tile.replaceChildren(img);
      }).catch(() => {});
    }
  } else {
    tile.append(icon('file', 16));
    title.textContent = h.name;
  }
}

route();
