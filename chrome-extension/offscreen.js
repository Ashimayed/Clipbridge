// Hidden page with clipboard access (service workers have none). Jobs:
//  1. notice when you copy something,  2. put incoming text on the clipboard,
//  3. decrypt incoming files so they can be saved,  4. wake the worker every 3 s to check Drive.
import * as C from './crypto.js';

const ta = document.getElementById('ta');
const ed = document.getElementById('ed');
const WATCH_MS = 1500;
const TICK_MS = 3000;
const MAX_IMAGE_CHARS = 36_000_000; // ~26 MB image; larger ones are sent from the popup instead

let lastKey = null;
let baselined = false;

function readClipboard() {
  ta.value = '';
  ta.focus();
  ta.select();
  document.execCommand('paste');
  if (ta.value) return { kind: 'text', value: ta.value };
  // No text: maybe an image. Pasting into contenteditable turns it into <img src="data:...">.
  ed.innerHTML = '';
  ed.focus();
  document.execCommand('paste');
  const src = ed.querySelector('img')?.getAttribute('src') || '';
  ed.textContent = '';
  if (src.startsWith('data:image/')) return { kind: 'image', value: src };
  return null;
}

// Cheap fingerprint: big images aren't re-sent to the worker every 1.5 s.
const keyOf = (item) =>
  !item ? 'empty' : item.kind === 'text' ? 't:' + item.value : `i:${item.value.length}:${item.value.slice(-96)}`;

function watch() {
  let item = null;
  try { item = readClipboard(); } catch { return; }
  const key = keyOf(item);
  if (key === lastKey) return;
  lastKey = key;
  if (!baselined) { baselined = true; return; } // never upload what was there before we started
  if (!item) return;
  if (item.kind === 'image' && item.value.length > MAX_IMAGE_CHARS) {
    chrome.runtime.sendMessage({ target: 'bg', type: 'image-too-big' }).catch(() => {});
    return;
  }
  chrome.runtime.sendMessage({
    target: 'bg', type: item.kind === 'text' ? 'local-text' : 'local-image', value: item.value,
  }).catch(() => {});
}

function writeText(text) {
  ta.value = text;
  ta.focus();
  ta.select();
  document.execCommand('copy');
  lastKey = keyOf({ kind: 'text', value: ta.value }); // don't bounce it straight back
  baselined = true;
}

/** Fetches an encrypted clip, decrypts it, returns a temporary blob: URL for chrome.downloads. */
async function decryptToUrl({ url, token, keyB64, prefixB64, mime }) {
  const key = await C.importKey(C.unb64(keyB64));
  const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`Drive HTTP ${res.status}`);
  const parts = await C.decryptStream(key, C.unb64(prefixB64), res.body);
  const blobUrl = URL.createObjectURL(new Blob(parts, { type: mime }));
  setTimeout(() => URL.revokeObjectURL(blobUrl), 10 * 60 * 1000); // freed after the download starts
  return blobUrl;
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (sender.id !== chrome.runtime.id || msg?.target !== 'offscreen') return; // only our own extension
  if (msg.type === 'write-text') {
    try { writeText(msg.text); sendResponse({ ok: true }); }
    catch (e) { sendResponse({ ok: false, error: String(e) }); }
  } else if (msg.type === 'decrypt-to-url') {
    decryptToUrl(msg).then(
      (blobUrl) => sendResponse({ ok: true, blobUrl }),
      (e) => sendResponse({ ok: false, error: e?.message || String(e), decrypt: e instanceof C.DecryptError }),
    );
    return true;
  } else if (msg.type === 'ignore-current') {
    try { lastKey = keyOf(readClipboard()); } catch { /* ignore */ }
    sendResponse({ ok: true });
  }
});

setInterval(watch, WATCH_MS);
setInterval(() => { chrome.runtime.sendMessage({ target: 'bg', type: 'tick' }).catch(() => {}); }, TICK_MS);
watch();
