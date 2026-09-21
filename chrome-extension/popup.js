import * as D from './drive.js';
import * as C from './crypto.js';

const $ = (id) => document.getElementById(id);
const bg = (msg) => chrome.runtime.sendMessage({ target: 'bg', ...msg });
const VIEWS = ['viewSignIn', 'viewPass', 'viewMain', 'viewSettings'];
const objectUrls = [];
let status = {};
let pendingKey = null;

function show(view) {
  for (const v of VIEWS) $(v).hidden = v !== view;
  $('headerTools').hidden = !(view === 'viewMain' || view === 'viewSettings');
}

function say(text, kind = '') {
  const m = $('msg');
  m.textContent = text;
  m.className = `small ${kind === 'ok' ? 'msg-ok' : kind === 'err' ? 'msg-err' : ''}`;
}

async function refreshStatus() {
  status = await bg({ type: 'status' });
  $('autoSend').setAttribute('aria-checked', String(!!status.autoSend));
  $('email').textContent = status.email || '';
  $('setEmail').textContent = status.email || '';
  $('statusCode').textContent = status.keyCheck || '';
  $('setCode').textContent = status.keyCheck || '';
  $('statusText').textContent = status.autoSend ? 'Syncing with your phone' : 'Auto-send paused';
  $('dot').className = status.autoSend ? 'dot' : 'dot off';
}

async function route() {
  await refreshStatus();
  if (!status.authOk) return show('viewSignIn');
  if (!status.hasKey) { $('cancelPass').hidden = true; return show('viewPass'); }
  show('viewMain');
  loadHistory();
}

// ================================================================ sign in

$('signIn').addEventListener('click', async () => {
  $('authError').textContent = '';
  $('signIn').disabled = true;
  const r = await bg({ type: 'sign-in' });
  $('signIn').disabled = false;
  if (r?.ok) return route();
  const e = r?.error || 'unknown error';
  $('authError').textContent = /not signed in/i.test(e)
    ? 'Chrome itself must be signed in to your Google account (profile icon, top right), then try again.'
    : `Sign-in failed: ${e}`;
});

// ================================================================ passphrase

async function commitKey(raw) {
  await bg({ type: 'save-key', keyB64: C.b64(raw), keyCheck: await C.keyCheck(raw) });
  $('pass1').value = '';
  $('pass2').value = '';
  pendingKey = null;
  $('warn').hidden = true;
  $('passStatus').textContent = '';
  route();
}

$('savePass').addEventListener('click', async () => {
  const a = $('pass1').value;
  const b = $('pass2').value;
  $('warn').hidden = true;
  if (a.length < 10) { $('passHint').className = 'small error'; return; }
  $('passHint').className = 'small';
  if (a !== b) { $('passStatus').textContent = "The two passphrases don't match."; return; }
  $('savePass').disabled = true;
  $('passStatus').textContent = 'Checking…';
  try {
    const raw = await C.deriveKey(a, status.email || '');
    $('checkCode').textContent = await C.keyCheck(raw);
    $('checkCard').hidden = false;
    // Compare with what the phone has been using (if any clips exist yet).
    let verdict = 'unknown';
    try {
      const clips = (await D.listClips()).filter((c) => c.description).slice(0, 3);
      if (!clips.length) verdict = 'none';
      else {
        const key = await C.importKey(raw);
        const opens = await Promise.all(clips.map((c) => C.openHeader(key, c.description).then(() => true, () => false)));
        verdict = opens.some(Boolean) ? 'match' : 'different';
      }
    } catch { /* offline: can't compare */ }
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
});
$('useAnyway').addEventListener('click', () => { if (pendingKey) commitKey(pendingKey); });
$('cancelPass').addEventListener('click', () => route());

// ================================================================ header + settings

$('autoSend').addEventListener('click', async () => {
  const on = $('autoSend').getAttribute('aria-checked') !== 'true';
  await bg({ type: 'set-auto-send', on });
  await refreshStatus();
});
$('openSettings').addEventListener('click', () => show('viewSettings'));
$('closeSettings').addEventListener('click', () => route());
$('changePass').addEventListener('click', () => { $('cancelPass').hidden = false; $('checkCard').hidden = true; show('viewPass'); });
$('deleteAll').addEventListener('click', async () => {
  if (!confirm('Delete all clips from your Google Drive? This affects every device and cannot be undone.')) return;
  const r = await bg({ type: 'delete-all' });
  alert(r?.ok ? 'All clips deleted.' : `Couldn't delete: ${r?.error}`);
  route();
});
$('signOut').addEventListener('click', async () => {
  if (!confirm('Sign out? This computer stops syncing and forgets its passphrase.')) return;
  await bg({ type: 'sign-out' });
  route();
});

// ================================================================ sending

$('send').addEventListener('click', async () => {
  const text = $('text').value;
  if (!text) { say('Type or paste something first.'); return; }
  say('Sending…');
  try {
    const hash = await D.sha256Text(text);
    await D.sendText(await D.getKey(), text, await D.identity(), hash);
    await bg({ type: 'mark-hash', hash });
    $('text').value = '';
    say('Sent to your phone.', 'ok');
    loadHistory();
  } catch (e) { say(`Not sent: ${e.message}`, 'err'); }
});

async function sendFiles(files) {
  const list = [...files];
  if (!list.length) return;
  const who = await D.identity();
  const key = await D.getKey();
  for (let i = 0; i < list.length; i++) {
    const f = list[i];
    say(list.length > 1 ? `Sending ${i + 1} of ${list.length}: ${f.name}…` : `Sending ${f.name || 'image'}…`);
    try {
      await D.sendBlob(key, f, f.name || `image-${Date.now()}.png`, who);
      await bg({ type: 'mark-hash', hash: await D.sha256Blob(f) });
    } catch (e) { say(`Not sent: ${e.message}`, 'err'); return; }
  }
  say(list.length > 1 ? `Sent ${list.length} items to your phone.` : 'Sent to your phone.', 'ok');
  loadHistory();
}
$('files').addEventListener('change', (e) => { sendFiles(e.target.files); e.target.value = ''; });
$('filesLabel').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('files').click(); }
});
document.addEventListener('paste', (e) => {
  if ($('viewMain').hidden) return;
  const files = e.clipboardData?.files;
  if (files && files.length) { e.preventDefault(); sendFiles(files); }
});

// ================================================================ history

const SVG = {
  link: '<path d="M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1"/><path d="M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1"/>',
  file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2.5"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  copy: '<rect x="8.5" y="8.5" width="11" height="12" rx="2.5"/><path d="M15.5 8.5V6a2.5 2.5 0 0 0-2.5-2.5H7A2.5 2.5 0 0 0 4.5 6v7A2.5 2.5 0 0 0 7 15.5h1.5"/>',
  save: '<path d="M12 4v11"/><path d="M7 10l5 5 5-5"/><path d="M4 18h16"/>',
};
// Icons are fixed strings from this file, never clip content, so building them as SVG is safe.
function icon(name, size = 18) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('width', size); s.setAttribute('height', size); s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor'); s.setAttribute('stroke-width', '1.8');
  s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round'); s.setAttribute('aria-hidden', 'true');
  s.innerHTML = SVG[name];
  return s;
}

const timeOf = (iso) => { const d = new Date(iso); return isNaN(d) ? '' : d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }); };
const sizeOf = (n) => n < 1024 ? `${n} B` : n < 1048576 ? `${Math.round(n / 1024)} KB` : `${(n / 1048576).toFixed(1)} MB`;

async function toPng(blob) {
  if (blob.type === 'image/png') return blob;
  const bmp = await createImageBitmap(blob);
  const canvas = new OffscreenCanvas(bmp.width, bmp.height);
  canvas.getContext('2d').drawImage(bmp, 0, 0);
  return canvas.convertToBlob({ type: 'image/png' });
}

async function copyClip(key, clip, opened, button) {
  button.disabled = true;
  try {
    const { header } = opened;
    if (header.kind === 'text' && header.size <= D.MAX_CLIPBOARD_TEXT) {
      const text = await D.readText(key, clip, opened);
      await bg({ type: 'mark-hash', hash: await D.sha256Text(text) }); // don't send it back out
      await navigator.clipboard.writeText(text);
      say('Copied.', 'ok');
    } else if (header.kind === 'image') {
      await bg({ type: 'suppress-image' });
      await navigator.clipboard.write([new ClipboardItem({ 'image/png': await toPng(await D.readBlob(key, clip, opened)) })]);
      say('Image copied.', 'ok');
    } else {
      const url = URL.createObjectURL(await D.readBlob(key, clip, opened));
      objectUrls.push(url);
      const name = header.kind === 'text' ? `clip-${Date.now()}.txt` : D.safeName(header.name);
      await chrome.downloads.download({ url, filename: `ClipBridge/${name}`, conflictAction: 'uniquify' });
      say('Saved to Downloads/ClipBridge.', 'ok');
    }
  } catch (e) {
    say(`Couldn't get it: ${e.message}`, 'err');
  } finally {
    button.disabled = false;
  }
}

async function loadHistory() {
  const ul = $('list');
  const empty = (text) => { ul.replaceChildren(); const li = document.createElement('li'); li.className = 'empty'; li.textContent = text; ul.append(li); };
  let key, clips;
  try {
    key = await D.getKey();
    clips = (await D.listClips()).slice(0, D.MAX_CLIPS);
  } catch (e) {
    return empty(e instanceof D.AuthError ? 'Sign in again to see your clips.' : `Can't reach Drive: ${e.message}`);
  }
  const { deviceId } = await D.identity();
  objectUrls.splice(0).forEach((u) => URL.revokeObjectURL(u));
  if (!clips.length) return empty('Nothing yet. Copy something here or on your phone.');
  ul.replaceChildren();
  for (const c of clips) {
    const opened = await D.openClip(key, c);
    const li = document.createElement('li');
    const tile = document.createElement('div'); tile.className = 'tile';
    const body = document.createElement('div'); body.className = 'grow';
    const title = document.createElement('div'); title.className = 'clip-title';
    const meta = document.createElement('div'); meta.className = 'clip-meta';
    const btn = document.createElement('button'); btn.className = 'icon';
    body.append(title, meta);
    li.append(tile, body, btn);
    ul.append(li);

    if (!opened) {
      tile.append(icon('lock'));
      title.textContent = 'Locked clip';
      meta.textContent = 'Sent with a different passphrase';
      btn.append(icon('copy')); btn.disabled = true; btn.setAttribute('aria-label', 'Locked');
      continue;
    }
    const h = opened.header;
    const from = c.origin === deviceId ? 'This computer' : `From ${h.device || 'your phone'}`;
    meta.textContent = h.kind === 'file' ? `${from}, ${sizeOf(h.size)}, ${timeOf(c.createdTime)}` : `${from}, ${timeOf(c.createdTime)}`;
    btn.append(icon(h.kind === 'file' ? 'save' : 'copy'));
    btn.setAttribute('aria-label', h.kind === 'file' ? 'Save to Downloads' : 'Copy');
    btn.addEventListener('click', () => copyClip(key, c, opened, btn));

    if (h.kind === 'text') {
      tile.textContent = 'Aa';
      title.textContent = 'Text';
      if (h.size <= 64000) {
        D.readText(key, c, opened).then((t) => {
          const one = t.replace(/\s+/g, ' ').trim();
          title.textContent = one.slice(0, 200) || 'Text';
          if (/^https?:\/\//.test(one)) tile.replaceChildren(icon('link'));
        }).catch(() => {});
      }
    } else if (h.kind === 'image') {
      title.textContent = h.name;
      if (h.size <= 3_000_000) {
        D.readBlob(key, c, opened).then((b) => {
          const url = URL.createObjectURL(b);
          objectUrls.push(url);
          const img = document.createElement('img'); img.alt = ''; img.src = url;
          tile.replaceChildren(img);
        }).catch(() => {});
      }
    } else {
      tile.append(icon('file'));
      title.textContent = h.name;
    }
  }
}

route();
