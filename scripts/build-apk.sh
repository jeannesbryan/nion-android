#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(
  sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts |
  head -n1
)"

[ -n "$VERSION" ] || { echo "ERROR: versionName not found"; exit 1; }

./gradlew assembleDebug

SOURCE="app/build/outputs/apk/debug/app-debug.apk"
TARGET="dist/nion_${VERSION}.apk"

[ -f "$SOURCE" ] || { echo "ERROR: $SOURCE not found"; exit 1; }

mkdir -p dist
cp "$SOURCE" "$TARGET"

echo
echo "NiOn APK:"
ls -lh "$TARGET"
