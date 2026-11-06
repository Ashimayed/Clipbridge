#!/usr/bin/env bash
# Builds dist/ClipBridge-Chrome-Extension.zip from chrome-extension/, the same way every time:
# files sorted, timestamps fixed, no extra metadata. So the zip on the release page can be
# checked against the source (its SHA-256 is printed and published with each release).
set -euo pipefail
cd "$(dirname "$0")/.."
version="$(tr -d '[:space:]' < VERSION)"
manifest_version="$(node -p "require('./chrome-extension/manifest.json').version")"
[ "$version" = "$manifest_version" ] || { echo "VERSION ($version) and manifest ($manifest_version) differ" >&2; exit 1; }

rm -rf dist && mkdir -p dist/stage/ClipBridge-Chrome-Extension
cp -R chrome-extension/. dist/stage/ClipBridge-Chrome-Extension/
find dist/stage -exec touch -h -d "2000-01-01T00:00:00Z" {} +
(cd dist/stage && find ClipBridge-Chrome-Extension -print | LC_ALL=C sort | zip -X -q -D -@ ../ClipBridge-Chrome-Extension.zip)
rm -rf dist/stage
sha256sum dist/ClipBridge-Chrome-Extension.zip
