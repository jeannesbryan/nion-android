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
MANIFEST="app/src/main/AndroidManifest.xml"
LAYOUT="app/src/main/res/layout/activity_main.xml"
GRADLE="app/build.gradle.kts"
PERM="app/src/main/java/io/github/jeannesbryan/nion/StrictPermissionDelegate.java"
FAV="app/src/main/assets/nion-favicon/favicon.js"

echo "=== NiOn Android 0.9.0 Reliability / Tor / Privacy Audit ==="

check "0.9.0+ reliability baseline version" \
    'versionName = "0\.9\.0(-dev)?|versionName = "1\.[0-9]+\.[0-9]+(-[^"]*)?' "$GRADLE"

echo
echo "--- Tor fail-closed / Retry ---"

check "Tor proxy bound to localhost" \
    'network\.proxy\.socks: "127\.0\.0\.1"' "$MAIN"

check "SOCKS remote DNS enabled" \
    'network\.proxy\.socks_remote_dns: true' "$MAIN"

check "SOCKS5 remote DNS enabled" \
    'network\.proxy\.socks5_remote_dns: true' "$MAIN"

check "Proxy bypass list empty" \
    'network\.proxy\.no_proxies_on: ""' "$MAIN"

check "Native DNS/TRR direct path disabled" \
    'network\.trr\.mode: 5' "$MAIN"

check "Fail-closed path retained" \
    'private fun failClosed' "$MAIN"

check "Retry restarts bundled TorService" \
    'TorService::class\.java' "$MAIN"

check "Retry disables browser before Tor restart" \
    'setBrowserControlsEnabled\(false\)' "$MAIN"

check "Retry state exists" \
    'torRetryInProgress' "$MAIN"

if grep -RqE \
    'LOAD_FLAGS_BYPASS_PROXY|LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE' \
    app/src/main/java/io/github/jeannesbryan/nion \
    --include='*.kt' \
    --include='*.java'; then
    bad "No Gecko proxy/navigation bypass flags"
else
    ok "No Gecko proxy/navigation bypass flags"
fi

if grep -RqE \
    'OkHttpClient|HttpURLConnection|java\.net\.URL\(' \
    app/src/main/java/io/github/jeannesbryan/nion \
    --include='*.kt' \
    --include='*.java'; then
    bad "No separate/direct Android browser networking"
else
    ok "No separate/direct Android browser networking"
fi

echo
echo "--- Gecko reliability ---"

check "Gecko onCrash recovery" \
    'override fun onCrash\(' "$MAIN"

check "Gecko onKill recovery" \
    'override fun onKill\(' "$MAIN"

check "Shared crash/kill recovery path" \
    'recoverCrashedTab' "$MAIN"

check "Low-memory background-tab release" \
    'releaseBackgroundTabsForMemory' "$MAIN"

check "Android trim-memory callback" \
    'override fun onTrimMemory\(' "$MAIN"

check "Android low-memory callback" \
    'override fun onLowMemory\(' "$MAIN"

check "Visible GeckoSession marked active" \
    'setActive\(true\)' "$MAIN"

check "Hidden GeckoSession marked inactive" \
    'setActive\(false\)' "$MAIN"

check "Active GeckoSession gets high priority" \
    'PRIORITY_HIGH' "$MAIN"

check "Gecko low-memory detection enabled" \
    'lowMemoryDetection\(true\)' "$MAIN"

check "Screen-lock display detach retained" \
    'releaseSession\(\)' "$MAIN"

check "Screen-resume reattach path retained" \
    'switchToTab\(' "$MAIN"

echo
echo "--- Android integration ---"

check "Rotation handled without Activity recreation" \
    'android:configChanges="orientation\|screenSize"' "$MANIFEST"

check "System Gecko color preference" \
    'COLOR_SCHEME_SYSTEM' "$MAIN"

if [ -f app/src/main/res/values-night/colors.xml ] &&
   [ -f app/src/main/res/values-night/styles.xml ]; then
    ok "Night-mode resources present"
else
    bad "Night-mode resources present"
fi

if [ -f app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml ] &&
   [ -f app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml ] &&
   grep -q '<monochrome' app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml; then
    ok "Adaptive + themed launcher resources present"
else
    bad "Adaptive + themed launcher resources present"
fi

check "Manifest uses adaptive round icon resource" \
    'android:roundIcon="@mipmap/ic_launcher_round"' "$MANIFEST"

check "Retry Tor accessibility description" \
    'contentDescription="Retry Tor connection"' "$LAYOUT"

check "Address accessibility description" \
    'contentDescription="Address or search"' "$LAYOUT"

check "Browser menu accessibility description" \
    'contentDescription="Browser menu"' "$LAYOUT"

echo
echo "--- Privacy baseline ---"

PERMISSION_COUNT=$(
    grep -o 'android.permission.[A-Za-z0-9_]*' "$MANIFEST" |
    sort -u |
    wc -l
)

if [ "$PERMISSION_COUNT" -eq 1 ] &&
   grep -q 'android.permission.INTERNET' "$MANIFEST"; then
    ok "Manifest permission baseline: INTERNET only"
else
    bad "Manifest permission baseline: INTERNET only"
fi

check "Strict permission delegate installed" \
    'StrictPermissionDelegate' "$MAIN"

check "Permission deny policy retained" \
    'VALUE_DENY|DENY' "$PERM"

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

check "Favicon request remains inside Gecko content" \
    'fetch\(' "$FAV"

echo
echo "--- Previous release audits ---"

if [ -x scripts/audit-privacy-070.sh ]; then
    if scripts/audit-privacy-070.sh; then
        ok "0.7.0 privacy/Tor audit retained"
    else
        bad "0.7.0 privacy/Tor audit retained"
    fi
else
    echo "SKIP audit-privacy-070.sh not present"
fi

if [ -x scripts/audit-downloads-080.sh ]; then
    if scripts/audit-downloads-080.sh; then
        ok "0.8.0 download/Tor audit retained"
    else
        bad "0.8.0 download/Tor audit retained"
    fi
else
    echo "SKIP audit-downloads-080.sh not present"
fi

echo

if [ "$FAIL" -ne 0 ]; then
    echo "AUDIT FAILED"
    exit 1
fi

echo "AUDIT PASSED"
