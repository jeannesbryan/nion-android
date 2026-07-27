#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="$(
    sed -n \
        's/.*versionName = "\([^"]*\)".*/\1/p' \
        app/build.gradle.kts |
    head -n1
)"

APK="dist/nion_${VERSION}.apk"

if [ ! -f "${APK}" ]; then
    echo "ERROR: APK not found:"
    echo "  ${APK}"
    exit 1
fi

find_build_tool() {
    local name="$1"
    local sdk="${ANDROID_HOME:-${HOME}/Android/Sdk}"

    if command -v "${name}" >/dev/null 2>&1; then
        command -v "${name}"
        return 0
    fi

    local found

    found="$(
        find "${sdk}/build-tools" \
            -maxdepth 2 \
            -type f \
            -name "${name}" \
            -print 2>/dev/null |
        sort -V |
        tail -n1
    )"

    if [ -n "${found}" ]; then
        printf '%s\n' "${found}"
        return 0
    fi

    return 1
}

APKSIGNER="$(find_build_tool apksigner || true)"
ZIPALIGN="$(find_build_tool zipalign || true)"

if [ -z "${APKSIGNER}" ]; then
    echo "ERROR: apksigner not found."
    exit 1
fi

echo "=== APK signature ==="

"${APKSIGNER}" \
    verify \
    --verbose \
    --print-certs \
    "${APK}"

if [ -n "${ZIPALIGN}" ]; then
    echo
    echo "=== ZIP alignment ==="

    "${ZIPALIGN}" \
        -c \
        -P 16 \
        -v 4 \
        "${APK}"
fi

echo
echo "=== SHA-256 ==="

(
    cd dist
    sha256sum -c SHA256SUMS
)

echo
echo "RELEASE APK VERIFIED"
