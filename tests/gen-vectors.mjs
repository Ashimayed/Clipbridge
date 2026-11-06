// Regenerates protocol/test-vectors.json. Only run this when the protocol changes ON PURPOSE,
// then make the Android side pass again: node tests/gen-vectors.mjs
import { writeFileSync } from 'node:fs';
import { buildVectors } from './vectors-lib.mjs';

const out = new URL('../protocol/test-vectors.json', import.meta.url);
writeFileSync(out, JSON.stringify(await buildVectors(), null, 1) + '\n');
console.log('wrote', out.pathname);
