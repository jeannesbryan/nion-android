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

MANIFEST="app/src/main/AndroidManifest.xml"
MAIN="app/src/main/java/io/github/jeannesbryan/nion/MainActivity.kt"
PERM="app/src/main/java/io/github/jeannesbryan/nion/StrictPermissionDelegate.java"
FAV="app/src/main/assets/nion-favicon/favicon.js"

echo "=== NiOn Android 0.7.0 privacy/Tor audit ==="

PERM_COUNT="$(
    grep -o 'android.permission.[A-Za-z0-9_]*' "$MANIFEST" |
    sort -u |
    wc -l
)"

if [ "$PERM_COUNT" -eq 1 ] &&
   grep -q 'android.permission.INTERNET' "$MANIFEST"; then
    ok "Manifest permission baseline: INTERNET only"
else
    bad "Manifest permission baseline"
fi

check "SOCKS proxy localhost" \
    'network\.proxy\.socks: "127\.0\.0\.1"' "$MAIN"

check "SOCKS remote DNS" \
    'network\.proxy\.socks_remote_dns: true' "$MAIN"

check "SOCKS5 remote DNS" \
    'network\.proxy\.socks5_remote_dns: true' "$MAIN"

check "No proxy bypass" \
    'network\.proxy\.no_proxies_on: ""' "$MAIN"

check "TRR disabled" \
    'network\.trr\.mode: 5' "$MAIN"

check "WebRTC disabled" \
    'media\.peerconnection\.enabled: false' "$MAIN"

check "Media navigator disabled" \
    'media\.navigator\.enabled: false' "$MAIN"

check "Geolocation disabled" \
    'geo\.enabled: false' "$MAIN"

check "Notifications disabled" \
    'dom\.webnotifications\.enabled: false' "$MAIN"

check "Push disabled" \
    'dom\.push\.enabled: false' "$MAIN"

check "WebGL disabled" \
    'webgl\.disabled: true' "$MAIN"

check "DNS prefetch disabled" \
    'network\.dns\.disablePrefetch: true' "$MAIN"

check "Link prefetch disabled" \
    'network\.prefetch-next: false' "$MAIN"

check "Predictor disabled" \
    'network\.predictor\.enabled: false' "$MAIN"

check "Speculative connections disabled" \
    'network\.http\.speculative-parallel-limit: 0' "$MAIN"

check "Ping sending disabled" \
    'browser\.send_pings: false' "$MAIN"

check "Strict permission delegate installed" \
    'StrictPermissionDelegate' "$MAIN"

check "Permission deny policy exists" \
    'VALUE_DENY|DENY' "$PERM"

check "Favicon fetched inside Gecko content" \
    'fetch\(' "$FAV"

check "Fail-closed path exists" \
    'private fun failClosed' "$MAIN"

check "GeckoView lifecycle detach exists" \
    'releaseSession\(\)' "$MAIN"

check "Lifecycle reattach uses switchToTab" \
    'switchToTab\(' "$MAIN"

if grep -RqE \
    'OkHttpClient|HttpURLConnection|java\.net\.URL\(' \
    app/src/main/java/io/github/jeannesbryan/nion \
    --include='*.kt' \
    --include='*.java'; then
    bad "No separate Android HTTP client"
else
    ok "No separate Android HTTP client"
fi

echo

if [ "$FAIL" -ne 0 ]; then
    echo "AUDIT FAILED"
    exit 1
fi

echo "AUDIT PASSED"
