// Shared logic that must behave EXACTLY like the Android app's Kotlin versions:
//   PollPolicy.kt (pollIntervalMs), Redact.kt (redact), PrunePlan.kt (planPrune), Pairing.kt (build/parsePairing).
// tests/parity checks run both sides against the same inputs.

import { b64, unb64, keyCheck } from './crypto.js';

// ------------------------------------------------------------------ sync speed

export const SYNC_MODES = {
  instant:  { label: 'Instant',       hint: 'Checks every 3 seconds, always. Fastest, but uses the most battery and Google quota.' },
  balanced: { label: 'Balanced',      hint: "Fast while you're active, slower when idle. Recommended." },
  saver:    { label: 'Battery saver', hint: 'Slowest when idle. A clip can take up to 45 seconds to arrive.' },
};
export const DEFAULT_MODE = 'balanced';
export const normalizeMode = (m) => (m in SYNC_MODES ? m : DEFAULT_MODE);

/** idleMs = time since anything happened (clip sent/received, you copied something, window focus...). */
export function pollIntervalMs(mode, idleMs) {
  const idle = idleMs < 0 ? 0 : idleMs;
  switch (normalizeMode(mode)) {
    case 'instant': return 3000;
    case 'saver': return idle < 30000 ? 4000 : idle < 120000 ? 15000 : 45000;
    default: return idle < 30000 ? 3000 : idle < 120000 ? 6000 : idle < 600000 ? 12000 : 20000;
  }
}

/** +/-10% so many devices never line up. unit is in [0, 1). */
export const jittered = (ms, unit) => Math.trunc(ms * (0.9 + 0.2 * Math.min(Math.max(unit, 0), 0.999999)));

// ------------------------------------------------------------------ safe debug lines

const RE_EMAIL = /[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/g;
const RE_BEARER = /bearer[ \t\r\n\f\u000B]+[^ \t\r\n\f\u000B]+/gi;
const RE_QUERY = /(https?:\/\/[^ \t\r\n\f\u000B?]+)\?[^ \t\r\n\f\u000B]*/g;
const RE_OPAQUE = /[A-Za-z0-9_\-+/=]{28,}/g;

/** Removes emails, bearer tokens, URL queries and long opaque strings (ids, keys, hashes, ciphertext). */
export function redact(s, max = 300) {
  let t = String(s).replace(RE_EMAIL, '<email>');
  t = t.replace(RE_BEARER, 'Bearer <token>');
  t = t.replace(RE_QUERY, '$1?<query>');
  t = t.replace(RE_OPAQUE, '<id>');
  t = t.replace(/[ \t\r\n\f\u000B]+/g, ' ').replace(/^ | $/g, '');
  return t.length > max ? t.slice(0, max) + '…' : t;
}

// ------------------------------------------------------------------ pinning + pruning

export const MAX_PINS = 10;

/** ids to delete: keep the newest `keep` UNPINNED clips; pinned clips are never deleted. */
export function planPrune(items, keep) {
  const newestFirst = [...items].sort((a, b) =>
    a.createdTime < b.createdTime ? 1 : a.createdTime > b.createdTime ? -1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
  let unpinned = 0;
  const out = [];
  for (const c of newestFirst) {
    if (c.pinned) continue;
    unpinned++;
    if (unpinned > keep) out.push(c.id);
  }
  return out;
}

// ------------------------------------------------------------------ QR pairing

const PREFIX = 'clipbridge://pair?';
const b64url = (bytes) => b64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const unb64url = (s) => unb64(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4));

/** Escapes everything except A-Z a-z 0-9 . _ - (exactly what Kotlin's URLEncoder + '*' fix produces). */
const pctEncode = (x) => encodeURIComponent(x).replace(/[!'()*~]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());

/** clipbridge://pair?v=1&k=<32-byte key, base64url>&c=<key check>&e=<email> */
export async function buildPairing(keyRaw, email) {
  if (keyRaw.length !== 32) throw new Error('key must be 32 bytes');
  return `${PREFIX}v=1&k=${b64url(keyRaw)}&c=${await keyCheck(keyRaw)}&e=${pctEncode(email.trim().toLowerCase())}`;
}

export async function parsePairing(raw) {
  const bad = (reason) => ({ ok: false, reason });
  const s = (raw ?? '').trim();
  if (!s || s.length > 400 || !s.startsWith(PREFIX)) return bad("That isn't a ClipBridge pairing code.");
  const params = new Map();
  for (const part of s.slice(PREFIX.length).split('&')) {
    const i = part.indexOf('=');
    if (i <= 0) return bad('The pairing code is damaged.');
    let value;
    try { value = decodeURIComponent(part.slice(i + 1)); } catch { return bad('The pairing code is damaged.'); }
    const name = part.slice(0, i);
    if (params.has(name)) return bad('The pairing code is damaged.');
    params.set(name, value);
  }
  if (params.get('v') !== '1') return bad('This code is from a newer version. Update ClipBridge.');
  let key;
  try { key = unb64url(params.get('k') ?? ''); } catch { return bad('The pairing code is damaged.'); }
  if (key.length !== 32) return bad('The pairing code is damaged.');
  const check = (params.get('c') ?? '').toUpperCase();
  if (check !== (await keyCheck(key))) return bad('The pairing code is damaged. Try scanning again.');
  const email = (params.get('e') ?? '').trim().toLowerCase();
  if (!email.includes('@') || email.length < 3 || email.length > 254) return bad('The pairing code is damaged.');
  return { ok: true, key, check, email };
}
