// End-to-end encryption, format "CB1". MUST stay byte-identical to Crypto.kt (Android).
// See Crypto.kt for the full format description.

export const ITERATIONS = 310000;
export const CHUNK = 256 * 1024;
const TAG = 16;
const enc = new TextEncoder();
const MAGIC = enc.encode('CB1C');
const HEADER_AAD = enc.encode('CB1H');

export class DecryptError extends Error {}

const concat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
};
const be32 = (i) => new Uint8Array([(i >>> 24) & 255, (i >>> 16) & 255, (i >>> 8) & 255, i & 255]);
const FF4 = new Uint8Array([255, 255, 255, 255]);
const sha256 = async (b) => new Uint8Array(await crypto.subtle.digest('SHA-256', b));
const eq = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

export function b64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
export function unb64(str) {
  const s = atob(str);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

// ------------------------------------------------------------------ keys

export async function deriveKey(passphrase, email, iterations = ITERATIONS) {
  const salt = await sha256(enc.encode('ClipBridge/v1/salt/' + email.trim().toLowerCase()));
  const base = await crypto.subtle.importKey('raw', enc.encode(passphrase), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, base, 256);
  return new Uint8Array(bits);
}

export async function keyCheck(raw) {
  const h = await sha256(concat(enc.encode('ClipBridge/v1/check/'), raw));
  const hex = [...h.slice(0, 4)].map((b) => b.toString(16).padStart(2, '0')).join('').toUpperCase();
  return `${hex.slice(0, 4)}-${hex.slice(4)}`;
}

export const importKey = (raw) =>
  crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);

export const newPrefix = () => crypto.getRandomValues(new Uint8Array(8));

export function cipherLength(size) {
  const chunks = size === 0 ? 1 : Math.ceil(size / CHUNK);
  return 12 + size + chunks * TAG;
}

async function gcm(mode, key, iv, aad, data) {
  const alg = { name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 };
  return new Uint8Array(await crypto.subtle[mode](alg, key, data));
}
const chunkAad = (i, last) => concat(MAGIC, be32(i), new Uint8Array([last ? 1 : 0]));

// ------------------------------------------------------------------ header

export async function sealHeader(key, prefix, { name, mime, kind, device, size }) {
  const json = enc.encode(JSON.stringify({ n: name, m: mime, k: kind, d: device, s: size }));
  const ct = await gcm('encrypt', key, concat(prefix, FF4), HEADER_AAD, json);
  return b64(concat(prefix, ct));
}

export async function openHeader(key, description) {
  if (!description) throw new DecryptError('Clip has no encrypted header');
  let raw;
  try { raw = unb64(description); } catch { throw new DecryptError('Malformed header'); }
  if (raw.length < 8 + TAG) throw new DecryptError('Malformed header');
  const prefix = raw.slice(0, 8);
  let plain;
  try {
    plain = await gcm('decrypt', key, concat(prefix, FF4), HEADER_AAD, raw.slice(8));
  } catch {
    throw new DecryptError('Wrong passphrase, or the clip was altered');
  }
  const o = JSON.parse(new TextDecoder().decode(plain));
  return {
    prefix,
    header: {
      name: o.n || 'clip', mime: o.m || 'application/octet-stream', kind: o.k || 'file',
      device: o.d || '', size: Number(o.s || 0),
    },
  };
}

// ------------------------------------------------------------------ content

/** Async generator of encrypted pieces, 256 KiB at a time, so big files never sit in memory twice. */
export async function* encryptBlob(key, prefix, blob) {
  yield concat(MAGIC, prefix);
  const size = blob.size;
  const chunks = size === 0 ? 1 : Math.ceil(size / CHUNK);
  for (let i = 0; i < chunks; i++) {
    const part = new Uint8Array(await blob.slice(i * CHUNK, Math.min(size, (i + 1) * CHUNK)).arrayBuffer());
    yield await gcm('encrypt', key, concat(prefix, be32(i)), chunkAad(i, i === chunks - 1), part);
  }
}

export async function encryptBytes(key, prefix, bytes) {
  const parts = [];
  for await (const p of encryptBlob(key, prefix, new Blob([bytes]))) parts.push(p);
  return concat(...parts);
}

/** Decrypts a ciphertext stream (e.g. a fetch body) into plaintext pieces. Wrap them in a Blob to save. */
export async function decryptStream(key, expectedPrefix, readable) {
  const reader = readable.getReader();
  const size = CHUNK + TAG;
  let buf = new Uint8Array(0);
  let headDone = false;
  let i = 0;
  const out = [];
  const take = async (n, last) => {
    const piece = buf.slice(0, n);
    buf = buf.slice(n);
    try {
      out.push(await gcm('decrypt', key, concat(expectedPrefix, be32(i)), chunkAad(i, last), piece));
    } catch {
      throw new DecryptError('Wrong passphrase, or the clip was altered');
    }
    i++;
  };
  for (;;) {
    const { value, done } = await reader.read();
    if (value) buf = concat(buf, value);
    if (!headDone && buf.length >= 12) {
      if (!eq(buf.slice(0, 4), MAGIC)) throw new DecryptError('Not a ClipBridge clip');
      if (!eq(buf.slice(4, 12), expectedPrefix)) throw new DecryptError("Header and content don't match");
      buf = buf.slice(12);
      headDone = true;
    }
    // A full chunk is only safe to open once more data follows (otherwise it's the last one).
    while (headDone && buf.length > size) await take(size, false);
    if (done) break;
  }
  if (!headDone || buf.length < TAG) throw new DecryptError('Clip is truncated');
  await take(buf.length, true);
  return out;
}

export async function decryptBytes(key, prefix, bytes) {
  return concat(...(await decryptStream(key, prefix, new Blob([bytes]).stream())));
}
