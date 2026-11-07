// Google Drive access + clip encryption, shared by background.js and popup.js.
// Scope drive.appdata: the extension can only see files it created, never your other Drive files.
// Every clip is end-to-end encrypted (crypto.js) before upload. Drive sees only ciphertext.

import * as C from './crypto.js';
import { planPrune, MAX_PINS, safeName, cleanDeviceName } from './logic.js';

export { safeName };

export const MAX_CLIPS = 10;
export const MAX_VISIBLE = MAX_CLIPS + MAX_PINS; // newest 10 unpinned, plus up to 10 pinned that can be older
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

// The offscreen page (which does the big uploads, so they survive the popup closing) has no
// chrome.identity. It plugs in a token source that asks the background worker instead.
let tokenSource = null;
let tokenDropper = null;
export function useTokenSource(get, drop) { tokenSource = get; tokenDropper = drop; }

/** Drive file ids are short base64url-ish strings. Anything else never reaches a URL. */
const RE_ID = /^[A-Za-z0-9_-]{10,200}$/;
export function checkId(id) {
  if (typeof id !== 'string' || !RE_ID.test(id)) throw new PermanentError('Not a valid clip id', 400);
  return id;
}

export async function getToken(interactive = false) {
  if (tokenSource) return tokenSource(interactive);
  let r;
  try { r = await chrome.identity.getAuthToken({ interactive }); }
  catch (e) { throw new AuthError(e?.message || String(e)); }
  const token = typeof r === 'string' ? r : r?.token; // Chrome 105+ returns { token }
  if (!token) throw new AuthError('Google did not return a token');
  return token;
}

export async function dropToken(token) {
  if (tokenDropper) { try { await tokenDropper(token); } catch { /* ignore */ } return; }
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
  const raw = await getRawKeyB64();
  if (keyCache.b64 !== raw) keyCache = { b64: raw, key: await C.importKey(C.unb64(raw)) };
  return keyCache.key;
}

/** The raw base64 key, for building a pairing QR. Same NoKeyError as getKey(). */
export async function getRawKeyB64() {
  const { keyB64 } = await chrome.storage.local.get('keyB64');
  if (!keyB64) throw new NoKeyError();
  return keyB64;
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
    pinned: f.appProperties?.pinned === '1',
  }));
}

/**
 * Marks a clip pinned or not. A lightweight metadata PATCH: the (possibly large, encrypted)
 * content is never re-uploaded. The pin flag is visible in Drive as plain metadata — like the
 * clip's size and timestamp already are — but it never reveals the clip's actual content.
 */
export async function setPinned(id, pinned) {
  await call(`${DRIVE}/files/${checkId(id)}?fields=id`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify({ appProperties: { pinned: pinned ? '1' : null } }),
  });
}

export async function email() {
  const json = await (await call(`${DRIVE}/about?fields=user(emailAddress)`, {}, true, 15000)).json();
  return json.user?.emailAddress || null;
}

export const download = (id) => call(`${DRIVE}/files/${checkId(id)}?alt=media`, {}, true, 0);

export async function deleteClip(id) { await call(`${DRIVE}/files/${checkId(id)}`, { method: 'DELETE' }); }

export async function prune() {
  const all = await listClips(100);
  const ids = planPrune(all, MAX_CLIPS);
  for (const id of ids) { try { await deleteClip(id); } catch { /* other device already pruned it */ } }
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

/**
 * Sends one resumable piece. Where XMLHttpRequest exists (the offscreen page) it reports
 * upload progress byte by byte; in the service worker it falls back to fetch().
 */
async function putPiece(session, piece, range, onBytes) {
  if (typeof XMLHttpRequest === 'undefined' || !onBytes) {
    return call(session, { method: 'PUT', headers: { 'Content-Range': range }, body: piece });
  }
  const token = await getToken(false);
  const res = await new Promise((resolve, reject) => {
    const x = new XMLHttpRequest();
    x.open('PUT', session);
    x.setRequestHeader('Authorization', `Bearer ${token}`);
    x.setRequestHeader('Content-Range', range);
    x.upload.onprogress = (e) => onBytes(e.loaded);
    x.onload = () => resolve({ status: x.status, text: x.responseText });
    x.onerror = () => reject(new TransientError('No connection (network)'));
    x.ontimeout = () => reject(new TransientError('No connection (timeout)'));
    x.send(piece);
  });
  if (res.status === 308 || (res.status >= 200 && res.status < 300)) {
    return { status: res.status, json: async () => JSON.parse(res.text || '{}') };
  }
  if (res.status === 401) throw new AuthError('Google rejected the sign-in. Sign in again.');
  throw await classify(new Response(res.text, { status: res.status }));
}

/** How many bytes of a resumable upload Drive already has. */
async function uploadedSoFar(session, total) {
  const res = await call(session, { method: 'PUT', headers: { 'Content-Range': `bytes */${total}` } }, true, 20000);
  if (res.status !== 308) return total; // 200/201: Drive already has everything
  const m = /bytes=0-(\d+)/.exec(res.headers.get('Range') || '');
  return m ? Number(m[1]) + 1 : 0;
}

/**
 * Encrypts [blob] and uploads it. Small: one request. Large: 8 MiB resumable pieces, constant
 * memory. onProgress(sentBytes, totalBytes) is called for large uploads. Returns the new Drive id.
 */
async function uploadEncrypted(key, blob, header, origin, onProgress) {
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
    const res = await call(`${UPLOAD}/files?uploadType=multipart&fields=id`, {
      method: 'POST', headers: { 'Content-Type': `multipart/related; boundary=${boundary}` }, body,
    });
    return (await res.json().catch(() => ({}))).id || null;
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
  let id = null;
  onProgress?.(0, total);
  const flush = async (final) => {
    const all = new Blob(pending);
    const len = final ? all.size : Math.floor(all.size / (256 * 1024)) * 256 * 1024;
    if (!len) return;
    let piece = all.slice(0, len);
    let base = sent; // where this attempt starts, in bytes of ciphertext
    let res;
    // A dropped connection resumes this piece where Drive stopped, instead of resending the
    // whole file: ask Drive how much it has (Google's documented resumable-upload recipe).
    for (let attempt = 0; ; attempt++) {
      try {
        res = await putPiece(session, piece, `bytes ${base}-${sent + len - 1}/${total}`,
          onProgress && ((n) => onProgress(Math.min(base + n, total), total)));
        break;
      } catch (e) {
        // A dropped connection, or Drive refusing a piece because it already holds part of it
        // (browsers may resend a request on their own after a reset): ask, then resume.
        const resumable = e instanceof TransientError || (e instanceof PermanentError && [400, 409, 416].includes(e.code));
        if (!resumable || attempt >= 4) throw e;
        await new Promise((r) => setTimeout(r, 2000 * 2 ** attempt));
        const have = await uploadedSoFar(session, total);
        if (have >= sent + len) { res = { status: final ? 200 : 308, json: async () => ({}) }; break; }
        // Drive lost data, or kept an odd amount a middle piece can't resume from: start over.
        if (have < sent || (!final && (have - sent) % (256 * 1024) !== 0)) throw new TransientError('Upload was interrupted');
        piece = all.slice(have - sent, len);
        base = have;
      }
    }
    const done = res.status !== 308;
    if (done !== (sent + len === total)) throw new TransientError('Upload was interrupted');
    if (done) id = (await res.json().catch(() => ({}))).id || null;
    sent += len;
    onProgress?.(sent, total);
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
  return id;
}

export async function sendText(key, text, who, hash) {
  const bytes = new TextEncoder().encode(text);
  await withRetry(() => uploadEncrypted(key, new Blob([bytes]),
    { name: 'clip.txt', mime: 'text/plain', kind: 'text', device: who.deviceLabel, size: bytes.length }, who.deviceId));
  await prune().catch(() => {});
  return hash;
}

/** Uploads any file. Returns the new clip's Drive id (or null if Drive didn't say). */
export async function sendBlob(key, blob, name, who, onProgress) {
  const mime = blob.type || 'application/octet-stream';
  const kind = mime.startsWith('image/') ? 'image' : 'file';
  const id = await withRetry(() => uploadEncrypted(key, blob,
    { name: safeName(name), mime, kind, device: who.deviceLabel, size: blob.size }, who.deviceId, onProgress));
  await prune().catch(() => {});
  return id;
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

/** "Chrome on Windows" etc.: what this computer is called until you give it a name. */
export function defaultDeviceLabel() {
  let platform = '';
  try { platform = navigator.userAgentData?.platform || ''; } catch { /* ignore */ }
  return platform ? `Chrome on ${platform}` : 'Chrome';
}

/** This computer's random id (never personal data) and its name, which only travels encrypted. */
export async function identity() {
  let { deviceId, deviceName } = await chrome.storage.local.get(['deviceId', 'deviceName']);
  if (!deviceId) {
    deviceId = crypto.randomUUID();
    await chrome.storage.local.set({ deviceId });
  }
  return { deviceId, deviceLabel: cleanDeviceName(deviceName || '') || defaultDeviceLabel() };
}
