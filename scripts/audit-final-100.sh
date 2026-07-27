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

GRADLE="app/build.gradle.kts"
MANIFEST="app/src/main/AndroidManifest.xml"

echo "=== NiOn Android 1.0.0 Final Audit ==="

check "versionCode 10" \
    'versionCode = 10' "$GRADLE"

check "versionName 1.0.0" \
    'versionName = "1\.0\.0"' "$GRADLE"

check "Release signing configuration exists" \
    'create\("release"\)' "$GRADLE"

check "Release build uses release signing config" \
    'signingConfigs\.getByName' "$GRADLE"

check "Signing store comes from environment" \
    'NION_RELEASE_STORE_FILE' "$GRADLE"

if grep -qE \
    'storePassword[[:space:]]*=[[:space:]]*"|keyPassword[[:space:]]*=[[:space:]]*"' \
    "$GRADLE"; then
    bad "No plaintext signing password in Gradle"
else
    ok "No plaintext signing password in Gradle"
fi

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

[ -f README.md ] && ok "README present" || bad "README present"

if find . -maxdepth 1 -type f \
    \( -iname 'LICENSE' -o -iname 'LICENSE.*' \) |
    grep -q .; then
    ok "LICENSE present"
else
    bad "LICENSE present"
fi

for audit_script in \
    scripts/audit-privacy-070.sh \
    scripts/audit-downloads-080.sh \
    scripts/audit-reliability-090.sh
do
    if [ -x "$audit_script" ]; then
        echo
        echo "=== Running $audit_script ==="
        if "$audit_script"; then
            ok "$(basename "$audit_script") retained"
        else
            bad "$(basename "$audit_script") retained"
        fi
    else
        bad "$(basename "$audit_script") exists and is executable"
    fi
done

echo
if [ "$FAIL" -ne 0 ]; then
    echo "FINAL AUDIT FAILED"
    exit 1
fi

echo "FINAL AUDIT PASSED"
