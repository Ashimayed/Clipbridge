// One version everywhere. The repo-root VERSION file is the source of truth; the Android app
// reads it directly (android-app/app/build.gradle.kts), and this check fails CI if the Chrome
// manifest or the docs say anything else.  Run: node scripts/check-version.mjs
import { readFileSync } from 'node:fs';

const root = new URL('../', import.meta.url);
const read = (p) => readFileSync(new URL(p, root), 'utf8');
const version = read('VERSION').trim();
const problems = [];

if (!/^\d{1,2}\.\d{1,2}\.\d{1,2}$/.test(version)) problems.push(`VERSION must look like 2.2.0, got "${version}"`);
const manifest = JSON.parse(read('chrome-extension/manifest.json'));
if (manifest.version !== version) problems.push(`chrome-extension/manifest.json says ${manifest.version}`);
for (const doc of ['README.md', 'index.html', 'privacy.html']) {
  for (const m of read(doc).matchAll(/data-version="([^"]*)"|<!-- version -->([^<]*)<!-- \/version -->/g)) {
    const v = (m[1] ?? m[2]).trim();
    if (v !== version) problems.push(`${doc} mentions version ${v}`);
  }
}

if (problems.length) {
  console.error(`Version mismatch (VERSION file says ${version}):\n  - ${problems.join('\n  - ')}`);
  process.exit(1);
}
console.log(`All versions match: ${version}`);
