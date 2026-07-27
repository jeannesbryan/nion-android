from pathlib import Path
import shutil

ROOT = Path(".")
GRADLE = ROOT / "app/build.gradle.kts"
README = ROOT / "README.md"
RELEASE_NOTES = ROOT / "RELEASE_NOTES_1.0.0.md"
FINAL_AUDIT = ROOT / "scripts/audit-final-100.sh"
PUBLISH = ROOT / "scripts/publish-github-release.sh"

def die(message: str) -> None:
    raise SystemExit("FINALIZE FAILED: " + message)

for path in (GRADLE, README):
    if not path.is_file():
        die(f"missing {path}")

gradle = GRADLE.read_text(encoding="utf-8")
readme = README.read_text(encoding="utf-8")

if 'versionCode = 10' not in gradle:
    die("expected versionCode 10")

if 'versionName = "1.0.0-rc1"' not in gradle:
    die("expected tested 1.0.0-rc1 baseline")

gradle = gradle.replace(
    'versionName = "1.0.0-rc1"',
    'versionName = "1.0.0"',
    1,
)

readme = readme.replace(
    "**1.0.0-rc1**",
    "**1.0.0**",
    1,
)

readme = readme.replace(
    "dist/nion_1.0.0-rc1.apk",
    "dist/nion_1.0.0.apk",
)

release_notes = '''# NiOn Android 1.0.0

NiOn is a minimal Tor-only Android browser built around GeckoView and a bundled Tor runtime.

## Highlights

- Bundled Tor with fail-closed browsing
- Clearnet and `.onion` browsing through Tor
- Remote DNS through Tor with direct fallback blocked
- HTTPS-First for clearnet with explicit one-shot HTTP fallback
- Multi-tab browsing and privacy-aware URL-only session restore
- Bookmarks, favicon support, Find in Page, sharing, and site information
- Strict permission policy for camera, microphone, location, notifications, and related web permissions
- WebRTC, WebGL, geolocation, push, DNS prefetch, speculative networking, and related leak-prone features hardened
- Cookie privacy controls and Global Privacy Control
- Per-site and global browsing-data clearing
- Tor-routed downloads using Gecko's response stream
- Download progress, local history, cancellation, retry, and completed-file opening
- Tor bootstrap retry and fail-closed recovery
- Gecko content-process crash/kill recovery
- Low-memory background-tab unloading
- Screen lock/unlock and rotation lifecycle recovery
- System light/dark integration
- Adaptive launcher icon and accessibility cleanup

## Distribution

The Android APK is built with the permanent NiOn release signing key and distributed as a GitHub Release asset.

Assets:

- `nion_1.0.0.apk`
- `SHA256SUMS`

The APK is not committed to the source repository.
'''

audit = r'''#!/usr/bin/env bash
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
'''

publish = r'''#!/usr/bin/env bash
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
'''

if 'versionName = "1.0.0"' not in gradle:
    die("stable version replacement failed")

backup = Path("/tmp/nion-android-pre100-stable")
if backup.exists():
    shutil.rmtree(backup)

for source in (GRADLE, README):
    target = backup / source
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)

GRADLE.write_text(gradle, encoding="utf-8")
README.write_text(readme, encoding="utf-8")
RELEASE_NOTES.write_text(release_notes, encoding="utf-8")

FINAL_AUDIT.parent.mkdir(parents=True, exist_ok=True)
FINAL_AUDIT.write_text(audit, encoding="utf-8")
FINAL_AUDIT.chmod(0o755)

PUBLISH.write_text(publish, encoding="utf-8")
PUBLISH.chmod(0o755)

print("NiOn Android 1.0.0 stable source finalized.")
print("✓ versionCode 10 retained")
print("✓ versionName 1.0.0")
print("✓ release notes written")
print("✓ final audit helper written")
print("✓ GitHub Release helper written")
print("✓ backup:", backup)
print()
print("Next:")
print("  ./scripts/audit-final-100.sh")
print("  ./scripts/build-release-apk.sh")
print("  ./scripts/verify-release-apk.sh")
