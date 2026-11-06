// Chrome side of the parity check: the extension's code must still produce exactly the committed
// protocol/test-vectors.json. android-app/core runs the same file through CoreParityTest.kt.
// Run: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildVectors, unhex, pattern } from './vectors-lib.mjs';
import * as C from '../chrome-extension/crypto.js';

const committed = JSON.parse(readFileSync(new URL('../protocol/test-vectors.json', import.meta.url), 'utf8'));

test('extension reproduces every committed vector', async () => {
  const fresh = await buildVectors();
  for (const section of Object.keys(committed)) {
    assert.deepEqual(fresh[section], committed[section], `section "${section}" changed`);
  }
});

test('every committed ciphertext decrypts back, and tampering is caught', async () => {
  for (const c of committed.content.filter((x) => x.cipher)) {
    const key = await C.importKey(unhex(c.key));
    const plain = await C.decryptBytes(key, unhex(c.prefix), unhex(c.cipher));
    assert.deepEqual(plain, pattern(c.plainSize));
    const bad = unhex(c.cipher); bad[bad.length - 1] ^= 1;
    await assert.rejects(C.decryptBytes(key, unhex(c.prefix), bad), C.DecryptError);
    await assert.rejects(C.decryptBytes(key, unhex(c.prefix), unhex(c.cipher).slice(0, -1)), C.DecryptError);
  }
  for (const h of committed.headers) {
    const opened = await C.openHeader(await C.importKey(unhex(h.key)), h.description);
    assert.deepEqual(opened.header, { ...h.header, name: h.header.name || 'clip' });
  }
});

test('RFC 7914 PBKDF2-HMAC-SHA256 known answer', async () => {
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode('passwd'), 'PBKDF2', false, ['deriveBits']);
  const bits = new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: new TextEncoder().encode('salt'), iterations: 1 }, base, 256));
  assert.equal([...bits].map((b) => b.toString(16).padStart(2, '0')).join(''), '55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc');
});
