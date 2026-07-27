#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

FAIL=0

ok()  { printf 'OK   %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; FAIL=1; }

check() {
    local label="$1"
    local pattern="$2"
    local file="$3"

    if grep -qE "$pattern" "$file"; then
        ok "$label"
    else
        bad "$label"
    fi
}

MAIN="app/src/main/java/io/github/jeannesbryan/nion/MainActivity.kt"
CENTER="app/src/main/java/io/github/jeannesbryan/nion/DownloadCenter.kt"
STORE="app/src/main/java/io/github/jeannesbryan/nion/DownloadHistoryStore.kt"
HANDLER="app/src/main/java/io/github/jeannesbryan/nion/TorDownloadHandler.kt"
MANIFEST="app/src/main/AndroidManifest.xml"
PATHS="app/src/main/res/xml/nion_file_paths.xml"
GRADLE="app/build.gradle.kts"

echo "=== NiOn Android 0.8.0 Download/Tor audit ==="

check "0.8.0+ download baseline version" \
    'versionName = "0\.[89]\.0(-dev)?|versionName = "1\.[0-9]+\.[0-9]+' "$GRADLE"

check "Gecko response enters Download Center" \
    'handleExternalResponse' "$MAIN"

check "Download consumes WebResponse body" \
    'response\.body' "$HANDLER"

check "MediaStore used on Android 10+" \
    'MediaStore\.Downloads' "$HANDLER"

check "Partial MediaStore file removed on error/cancel" \
    'resolver\.delete\(uri' "$HANDLER"

check "Partial app-specific file removed on error/cancel" \
    'outputFile\.delete\(\)' "$HANDLER"

check "Cancel closes Gecko response input" \
    'input\.close\(\)' "$HANDLER"

check "Download history capped at 100" \
    'MAX_ITEMS = 100' "$STORE"

check "Interrupted transfer becomes failed history" \
    'Interrupted before completion' "$STORE"

check "Clear History preserves active transfer records" \
    'clearFinished' "$STORE"

check "Clear All suppresses callback history recreation" \
    'suppressHistory' "$CENTER"

check "Clear All cancels active download metadata" \
    'clearForBrowsingData' "$MAIN"

check "Tor fail-closed cancels active downloads" \
    'cancelActiveForTorLoss' "$MAIN"

check "FileProvider is not exported" \
    'android:exported="false"' "$MANIFEST"

check "FileProvider grants temporary URI permission" \
    'android:grantUriPermissions="true"' "$MANIFEST"

check "FileProvider path config exists" \
    'external-files-path' "$PATHS"

if grep -qE \
    'READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE' \
    "$MANIFEST"; then
    bad "No storage permission requested"
else
    ok "No storage permission requested"
fi

if grep -RqE \
    'DownloadManager|OkHttpClient|HttpURLConnection|java\.net\.URL\(' \
    app/src/main/java/io/github/jeannesbryan/nion \
    --include='*.kt' \
    --include='*.java'; then
    bad "No separate/direct Android download networking"
else
    ok "No separate/direct Android download networking"
fi

if [ -x scripts/audit-privacy-070.sh ]; then
    echo
    echo "=== Re-running 0.7.0 privacy/Tor baseline ==="

    if scripts/audit-privacy-070.sh; then
        ok "0.7.0 privacy/Tor baseline retained"
    else
        bad "0.7.0 privacy/Tor baseline retained"
    fi
fi

echo

if [ "$FAIL" -ne 0 ]; then
    echo "AUDIT FAILED"
    exit 1
fi

echo "AUDIT PASSED"
