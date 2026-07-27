#!/usr/bin/env bash
set -euo pipefail

CONFIG_DIR="${HOME}/.config/nion-android"
KEYSTORE="${CONFIG_DIR}/nion-release.jks"
ALIAS="nion-release"

mkdir -p "${CONFIG_DIR}"
chmod 700 "${CONFIG_DIR}"

if ! command -v keytool >/dev/null 2>&1; then
    echo "ERROR: keytool not found. JDK 17 is required."
    exit 1
fi

if [ -f "${KEYSTORE}" ]; then
    echo "Release keystore already exists:"
    echo "  ${KEYSTORE}"
    echo
    echo "Nothing changed."
    echo "NEVER delete or replace this key after publishing 1.0.0."
    exit 0
fi

echo "Creating the permanent NiOn Android release key."
echo
echo "IMPORTANT:"
echo "  Back up this .jks file offline."
echo "  Future NiOn updates must use this same key."
echo

read -rsp "New keystore/key password: " PASSWORD
echo
read -rsp "Confirm password: " PASSWORD2
echo

if [ "${PASSWORD}" != "${PASSWORD2}" ]; then
    echo "ERROR: passwords do not match."
    exit 1
fi

if [ "${#PASSWORD}" -lt 12 ]; then
    echo "ERROR: use at least 12 characters."
    exit 1
fi

keytool \
  -genkeypair \
  -v \
  -keystore "${KEYSTORE}" \
  -storepass "${PASSWORD}" \
  -keypass "${PASSWORD}" \
  -alias "${ALIAS}" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=NiOn Android Release,O=NiOn"

chmod 600 "${KEYSTORE}"

unset PASSWORD PASSWORD2

echo
echo "Created:"
echo "  ${KEYSTORE}"
echo
echo "Back this file up securely before publishing 1.0.0."
