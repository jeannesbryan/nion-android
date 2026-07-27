#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

TAG="v1.0.0"
APK="dist/nion_1.0.0.apk"
CHECKSUM="dist/SHA256SUMS"
NOTES="RELEASE_NOTES_1.0.0.md"

for file in "$APK" "$CHECKSUM" "$NOTES"; do
    if [ ! -f "$file" ]; then
        echo "ERROR: missing $file"
        exit 1
    fi
done

if ! command -v gh >/dev/null 2>&1; then
    echo "ERROR: GitHub CLI (gh) not found."
    echo "Create the release manually with tag ${TAG} and upload:"
    echo "  ${APK}"
    echo "  ${CHECKSUM}"
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "ERROR: GitHub CLI is not authenticated."
    echo "Run: gh auth login"
    exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "ERROR: working tree has uncommitted changes."
    echo "Commit the final 1.0.0 source first."
    exit 1
fi

if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "Tag ${TAG} already exists locally."
else
    git tag -a "$TAG" -m "NiOn Android 1.0.0"
fi

git push origin "$TAG"

gh release create "$TAG" \
    "$APK" \
    "$CHECKSUM" \
    --title "NiOn Android 1.0.0" \
    --notes-file "$NOTES" \
    --verify-tag

echo
echo "GitHub Release published: ${TAG}"
