// Google Drive access + clip encryption, shared by background.js and popup.js.
// Scope drive.appdata: the extension can only see files it created, never your other Drive files.
// Every clip is end-to-end encrypted (crypto.js) before upload. Drive sees only ciphertext.

import * as C from './crypto.js';

export const MAX_CLIPS = 10;
export const DRIVE = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';
const SMALL = 5 * 1024 * 1024;      // up to this size: one request
const UNIT = 8 * 1024 * 1024;       // resumable pieces (a multiple of 256 KiB, as Drive requires)
export const MAX_CLIPBOARD_TEXT = 5 * 1024 * 1024;

export class AuthError extends Error {}
/** Offline, timeouts, 429, 5xx: worth retrying. */
export class TransientError extends Error {}
/** Drive full, clip gone, bad request: retrying won't help. */
export class PermanentError extends Error { constructor(m, code) { super(m); this.code = code; } }
export class NoKeyError extends Error { constructor() { super('Set your passphrase first'); } }

// ------------------------------------------------------------------ auth

export async function getToken(interactive = false) {
  let r;
  try { r = await chrome.identity.getAuthToken({ interactive }); }
  catch (e) { throw new AuthError(e?.message || String(e)); }
  const token = typeof r === 'string' ? r : r?.token; // Chrome 105+ returns { token }
  if (!token) throw new AuthError('Google did not return a token');
  return token;
}

export async function dropToken(token) {
  try { await chrome.identity.removeCachedAuthToken({ token }); } catch { /* ignore */ }
}

async function classify(res) {
  const body = await res.text().catch(() => '');
  if (body.includes('storageQuotaExceeded')) return new PermanentError('Your Google Drive is full', res.status);
  if (res.status === 429 || res.status >= 500 || body.includes('RateLimitExceeded')) {
    return new TransientError(`Google Drive is busy (HTTP ${res.status})`);
  }
  if (res.status === 404) return new PermanentError('That clip no longer exists', 404);
  return new PermanentError(`Drive error ${res.status}`, res.status);
}

/** fetch() with a token and a timeout; on 401 drops the cached token and retries once. */
async function call(url, opts = {}, retry = true, timeoutMs = 20000) {
  const token = await getToken(false);
  let res;
  try {
    res = await fetch(url, {
      ...opts,
      headers: { ...(opts.headers || {}), Authorization: `Bearer ${token}` },
      // Quick calls get a timeout; uploads/downloads of any size must not be cut off.
      signal: timeoutMs > 0 && !opts.body ? AbortSignal.timeout(timeoutMs) : undefined,
    });
  } catch (e) {
    throw new TransientError(`No connection (${e?.name || 'network'})`);
  }
  if (res.status === 401) {
    await dropToken(token);
    if (retry) return call(url, opts, false, timeoutMs);
    throw new AuthError('Google rejected the sign-in. Sign in again.');
  }
  if (res.ok || res.status === 308) return res;
  if (opts.method === 'DELETE' && res.status === 404) return res; // other device already pruned it
  throw await classify(res);
}

/** Retries only failures that can fix themselves: now, +2 s, +6 s. */
export async function withRetry(fn) {
  let wait = 2000;
  for (let i = 0; ; i++) {
    try { return await fn(); }
    catch (e) {
      if (!(e instanceof TransientError) || i >= 2) throw e;
      await new Promise((r) => setTimeout(r, wait));
      wait *= 3;
    }
  }
}

// ------------------------------------------------------------------ key

let keyCache = { b64: null, key: null };

/** The AES key from local storage, or throws NoKeyError. */
export async function getKey() {
  const { keyB64 } = await chrome.storage.local.get('keyB64');
  if (!keyB64) throw new NoKeyError();
  if (keyCache.b64 !== keyB64) keyCache = { b64: keyB64, key: await C.importKey(C.unb64(keyB64)) };
  return keyCache.key;
}

const headerCache = new Map();

/** { prefix, header } for a clip, or null if this key can't open it (wrong passphrase / tampered). */
export async function openClip(key, clip) {
  const cached = headerCache.get(clip.id);
  if (cached && cached.key === key) return cached.opened;
  try {
    const opened = await C.openHeader(key, clip.description);
    headerCache.set(clip.id, { key, opened });
    return opened;
  } catch { return null; }
}

// ------------------------------------------------------------------ Drive

export async function listClips(pageSize = 20) {
  const q = new URLSearchParams({
    spaces: 'appDataFolder',
    orderBy: 'createdTime desc',
    pageSize: String(pageSize),
    fields: 'files(id,size,createdTime,description,appProperties)',
  });
  const json = await (await call(`${DRIVE}/files?${q}`, {}, true, 15000)).json();
  return (json.files || []).map((f) => ({
    id: f.id,
    size: Number(f.size || 0),
    createdTime: f.createdTime || '',
    origin: f.appProperties?.origin || '',
    description: f.description || null,
  }));
}

export async function email() {
  const json = await (await call(`${DRIVE}/about?fields=user(emailAddress)`, {}, true, 15000)).json();
  return json.user?.emailAddress || null;
}

export const download = (id) => call(`${DRIVE}/files/${id}?alt=media`, {}, true, 0);

export async function deleteClip(id) { await call(`${DRIVE}/files/${id}`, { method: 'DELETE' }); }

export async function prune() {
  const all = await listClips(100);
  for (const c of all.slice(MAX_CLIPS)) { try { await deleteClip(c.id); } catch { /* fine */ } }
}

export async function deleteAll() {
  for (let round = 0; round < 20; round++) { // bounded: never loops forever
    const batch = await listClips(100);
    if (!batch.length) return;
    for (const c of batch) await deleteClip(c.id);
  }
}

function metadata(description, origin) {
  return {
    name: `cb-${Date.now()}.bin`,
    mimeType: 'application/octet-stream',
    parents: ['appDataFolder'],
    description,
    appProperties: { origin, v: '1' },
  };
}

/** Encrypts [blob] and uploads it. Small: one request. Large: 8 MiB resumable pieces, constant memory. */
async function uploadEncrypted(key, blob, header, origin) {
  const prefix = C.newPrefix();
  const description = await C.sealHeader(key, prefix, header);
  const meta = metadata(description, origin);
  const total = C.cipherLength(blob.size);

  if (total <= SMALL) {
    const parts = [];
    for await (const p of C.encryptBlob(key, prefix, blob)) parts.push(p);
    const boundary = 'cb' + crypto.randomUUID().replace(/-/g, '');
    const body = new Blob([
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n`, JSON.stringify(meta),
      `\r\n--${boundary}\r\nContent-Type: application/octet-stream\r\n\r\n`, ...parts, `\r\n--${boundary}--`,
    ]);
    await call(`${UPLOAD}/files?uploadType=multipart&fields=id`, {
      method: 'POST', headers: { 'Content-Type': `multipart/related; boundary=${boundary}` }, body,
    });
    return;
  }

  const start = await call(`${UPLOAD}/files?uploadType=resumable&fields=id`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
      'X-Upload-Content-Type': 'application/octet-stream',
      'X-Upload-Content-Length': String(total),
    },
    body: JSON.stringify(meta),
  });
  const session = start.headers.get('Location');
  if (!session) throw new TransientError('Drive did not start the upload');

  let pending = [];
  let pendingLen = 0;
  let sent = 0;
  const flush = async (final) => {
    const all = new Blob(pending);
    const len = final ? all.size : Math.floor(all.size / (256 * 1024)) * 256 * 1024;
    if (!len) return;
    const piece = all.slice(0, len);
    const res = await call(session, {
      method: 'PUT',
      headers: { 'Content-Range': `bytes ${sent}-${sent + len - 1}/${total}` },
      body: piece,
    });
    if (!final && res.status !== 308) throw new TransientError('Upload was interrupted');
    sent += len;
    pending = [all.slice(len)];
    pendingLen = all.size - len;
  };
  for await (const p of C.encryptBlob(key, prefix, blob)) {
    pending.push(p);
    pendingLen += p.length;
    if (pendingLen >= UNIT) await flush(false);
  }
  await flush(true);
  if (sent !== total) throw new TransientError('Upload was incomplete');
}

export async function sendText(key, text, who, hash) {
  const bytes = new TextEncoder().encode(text);
  await withRetry(() => uploadEncrypted(key, new Blob([bytes]),
    { name: 'clip.txt', mime: 'text/plain', kind: 'text', device: who.deviceLabel, size: bytes.length }, who.deviceId));
  await prune().catch(() => {});
  return hash;
}

export async function sendBlob(key, blob, name, who) {
  const mime = blob.type || 'application/octet-stream';
  const kind = mime.startsWith('image/') ? 'image' : 'file';
  await withRetry(() => uploadEncrypted(key, blob,
    { name: safeName(name), mime, kind, device: who.deviceLabel, size: blob.size }, who.deviceId));
  await prune().catch(() => {});
}

/** Downloads and decrypts a clip into a Blob of its real type. */
export async function readBlob(key, clip, opened) {
  const res = await download(clip.id);
  const parts = await C.decryptStream(key, opened.prefix, res.body);
  return new Blob(parts, { type: opened.header.mime });
}

export async function readText(key, clip, opened) {
  return (await readBlob(key, clip, opened)).text();
}

// ------------------------------------------------------------------ small helpers

const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');

/** Same normalization as Android, so hashes match across devices. */
export async function sha256Text(text) {
  return hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text.replace(/\r\n/g, '\n'))));
}
export async function sha256Blob(blob) {
  return hex(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()));
}

export function safeName(name) {
  const n = (name || 'clip').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim().slice(0, 120);
  return !n || /^\.+$/.test(n) ? 'clip' : n; // blocks "." / ".." tricks
}

export async function identity() {
  let { deviceId } = await chrome.storage.local.get('deviceId');
  if (!deviceId) {
    deviceId = crypto.randomUUID();
    await chrome.storage.local.set({ deviceId });
  }
  let platform = '';
  try { platform = navigator.userAgentData?.platform || ''; } catch { /* ignore */ }
  return { deviceId, deviceLabel: platform ? `Chrome on ${platform}` : 'Chrome' };
}
