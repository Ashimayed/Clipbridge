// Small IndexedDB helper shared by the popup, the offscreen page and the background worker
// (they all share this extension's private origin; no web page or other extension can read it).
//   outbox: files picked in the popup, waiting to be encrypted and sent. Handing them over through
//           here lets the upload keep going after the popup closes. Removed as soon as each is sent.
//   thumbs: small downscaled previews of image clips, so the list doesn't re-download images every
//           time it opens. Cleared on sign-out, passphrase change and "Delete all clips".

const DB = 'clipbridge';
let dbp = null;

function open() {
  if (!dbp) {
    dbp = new Promise((resolve, reject) => {
      const r = indexedDB.open(DB, 1);
      r.onupgradeneeded = () => {
        r.result.createObjectStore('outbox', { keyPath: 'id' });
        r.result.createObjectStore('thumbs');
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => { dbp = null; reject(r.error); };
    });
  }
  return dbp;
}

async function tx(store, mode, fn) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction(store, mode);
    const req = fn(t.objectStore(store));
    t.oncomplete = () => resolve(req?.result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
}

// ------------------------------------------------------------------ outbox

/** Queues files; returns their outbox ids, in order. */
export async function queueFiles(files) {
  const ids = [];
  const db = await open();
  await new Promise((resolve, reject) => {
    const t = db.transaction('outbox', 'readwrite');
    const s = t.objectStore('outbox');
    for (const f of files) {
      const id = crypto.randomUUID();
      ids.push(id);
      s.put({ id, blob: f, name: f.name || '', type: f.type || '', size: f.size, at: Date.now() });
    }
    t.oncomplete = resolve;
    t.onerror = () => reject(t.error);
  });
  return ids;
}

export const takeQueued = (id) => tx('outbox', 'readonly', (s) => s.get(id));
export const dropQueued = (id) => tx('outbox', 'readwrite', (s) => s.delete(id));
export const queuedIds = () => tx('outbox', 'readonly', (s) => s.getAllKeys());
export const clearQueue = () => tx('outbox', 'readwrite', (s) => s.clear());

// ------------------------------------------------------------------ thumbnails

export const getThumb = (clipId) => tx('thumbs', 'readonly', (s) => s.get(clipId));
export const putThumb = (clipId, blob) => tx('thumbs', 'readwrite', (s) => s.put(blob, clipId));
export const clearThumbs = () => tx('thumbs', 'readwrite', (s) => s.clear());

/** Forgets previews of clips that are no longer in Drive. */
export async function keepThumbs(clipIds) {
  const keep = new Set(clipIds);
  const keys = await tx('thumbs', 'readonly', (s) => s.getAllKeys());
  const gone = keys.filter((k) => !keep.has(k));
  if (gone.length) await tx('thumbs', 'readwrite', (s) => { gone.forEach((k) => s.delete(k)); });
}

/** A small preview (longest side 128 px, WebP) of an image, or null if it can't be decoded. */
export async function makeThumb(blob) {
  try {
    const bmp = await createImageBitmap(blob);
    const scale = Math.min(1, 128 / Math.max(bmp.width, bmp.height));
    const w = Math.max(1, Math.round(bmp.width * scale));
    const h = Math.max(1, Math.round(bmp.height * scale));
    const canvas = new OffscreenCanvas(w, h);
    canvas.getContext('2d').drawImage(bmp, 0, 0, w, h);
    bmp.close();
    return await canvas.convertToBlob({ type: 'image/webp', quality: 0.7 });
  } catch { return null; }
}
