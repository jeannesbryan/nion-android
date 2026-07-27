#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

KEYSTORE_DEFAULT="${HOME}/.config/nion-android/nion-release.jks"

VERSION="$(
    sed -n \
        's/.*versionName = "\([^"]*\)".*/\1/p' \
        app/build.gradle.kts |
    head -n1
)"

if [ -z "${VERSION}" ]; then
    echo "ERROR: versionName not found."
    exit 1
fi

KEYSTORE="${NION_RELEASE_STORE_FILE:-${KEYSTORE_DEFAULT}}"
ALIAS="${NION_RELEASE_KEY_ALIAS:-nion-release}"

if [ ! -f "${KEYSTORE}" ]; then
    echo "ERROR: release keystore not found:"
    echo "  ${KEYSTORE}"
    echo
    echo "Run:"
    echo "  ./scripts/setup-release-key.sh"
    exit 1
fi

if [ -z "${NION_RELEASE_STORE_PASSWORD:-}" ]; then
    read -rsp "Release keystore password: " NION_RELEASE_STORE_PASSWORD
    echo
fi

if [ -z "${NION_RELEASE_KEY_PASSWORD:-}" ]; then
    NION_RELEASE_KEY_PASSWORD="${NION_RELEASE_STORE_PASSWORD}"
fi

export NION_RELEASE_STORE_FILE="${KEYSTORE}"
export NION_RELEASE_STORE_PASSWORD
export NION_RELEASE_KEY_ALIAS="${ALIAS}"
export NION_RELEASE_KEY_PASSWORD

echo "Building signed NiOn ${VERSION} release APK..."

./gradlew clean assembleRelease

SOURCE="app/build/outputs/apk/release/app-release.apk"

if [ ! -f "${SOURCE}" ]; then
    echo "ERROR: release APK not found:"
    echo "  ${SOURCE}"
    exit 1
fi

mkdir -p dist

TARGET="dist/nion_${VERSION}.apk"

cp "${SOURCE}" "${TARGET}"

(
    cd dist
    sha256sum "nion_${VERSION}.apk" > SHA256SUMS
)

unset NION_RELEASE_STORE_PASSWORD
unset NION_RELEASE_KEY_PASSWORD

echo
echo "Release artifacts:"
ls -lh "${TARGET}" dist/SHA256SUMS
echo
cat dist/SHA256SUMS
