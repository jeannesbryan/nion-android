# NiOn Android

**NiOn** is a minimal Android browser designed to route browsing through the Tor network only.

NiOn Android is a separate project from the Linux version of NiOn. It uses GeckoView for web rendering and bundles Tor for Android so the browser does not depend on a system Tor installation.

## Current version

**1.0.0**

## Highlights

- Bundled Tor runtime
- Tor-only GeckoView networking
- Remote DNS through Tor
- `.onion` support
- Fail-closed behavior when Tor is unavailable
- Multi-tab browsing
- Popup / `target="_blank"` handling
- Android Back navigation
- Session restore with privacy-aware URL/title persistence
- Simple bookmarks
- Tor-safe downloads
- Clear Data for This Site
- Site & Connection Information
- HTTPS-First for clearnet
- Explicit HTTP fallback when HTTPS fails
- Website favicons in tabs
- NiOn launcher icon
- Compact mobile tab UI
- Up to 8 tabs
- Tor bootstrap Retry control
- Gecko tab crash recovery
- Low-memory background-tab unloading
- Screen rotation without Activity restart
- System light/dark UI integration
- Adaptive and themed launcher icon support
- Accessibility descriptions for browser controls
- Find in Page
- Paste & Go
- Copy / Share current URL
- Long-press link actions (Open in New Tab / Copy Link)
- Compact browser overflow menu
- Automatic Tor Project verification page after successful startup
- Privacy & Data control center
- Global cookie/site-data policy (Balanced, Strict, Block all)
- Clear All Browsing Data
- Global Privacy Control enabled
- Privacy status summary

## Privacy baseline

NiOn is intentionally conservative.

The browser currently:

- routes GeckoView traffic through the bundled Tor SOCKS proxy;
- uses remote DNS through Tor;
- blocks browsing until Tor is ready;
- returns to a blocked state if Tor becomes unavailable;
- does not request camera, microphone, location, or storage permissions;
- disables WebRTC;
- disables WebGL;
- disables geolocation;
- disables web notifications and push;
- disables DNS prefetching, link prefetching, and speculative networking;
- restricts top-level navigation to supported schemes;
- avoids persisting raw Gecko session state or form contents.

NiOn is not intended to replace the Tor Browser security model. It is a small independent browser project with its own implementation and threat model.

## Technology

- Kotlin + Java
- Android XML Views
- GeckoView
- Guardian Project tor-android
- Gradle / Android Gradle Plugin
- Minimum Android API: 26

Development is CLI-first. Android Studio and an emulator are not required.

## Building

### Requirements

- JDK 17
- Android SDK
- Android platform required by the current dependencies
- Android Build Tools
- ADB for physical-device testing

Clone the repository:

```bash
git clone https://github.com/jeannesbryan/nion-android.git
cd nion-android
```

Make sure `local.properties` points to your Android SDK, for example:

```properties
sdk.dir=/home/USERNAME/Android/Sdk
```

Build the normal debug APK:

```bash
./gradlew assembleDebug
```

Or use the included helper:

```bash
./scripts/build-apk.sh
```

The helper copies the APK to:

```text
dist/nion_<version>.apk
```

For example:

```text
dist/nion_1.0.0.apk
```

`dist/` is intentionally ignored by Git. APKs intended for distribution should be attached to a GitHub Release instead of committed to the repository.

## Install on a physical Android device

Enable USB debugging, connect the device, then:

```bash
adb devices
adb install -r dist/nion_1.0.0.apk
```

Launch manually:

```bash
adb shell am start \
  -n io.github.jeannesbryan.nion/.MainActivity
```

## Project structure

```text
app/
  src/main/
    java/io/github/jeannesbryan/nion/
    res/
    assets/

scripts/
  build-apk.sh

artwork/
  nion.svg
```

`artwork/nion.svg` is the source artwork for the NiOn launcher icon. Android launcher PNG resources are generated from this artwork and stored under `app/src/main/res/mipmap-*`.

## Tor routing

NiOn starts the bundled Tor service and waits for bootstrap completion before enabling browsing.

GeckoView is configured to use the Tor SOCKS proxy on localhost with remote DNS enabled. If the Tor runtime fails or its control state is lost, NiOn closes active browsing sessions and enters a blocked state rather than silently falling back to direct networking.

## Startup Tor verification

After bundled Tor reaches 100% bootstrap and GeckoView is configured for the Tor SOCKS proxy, NiOn automatically opens `https://check.torproject.org/`.

If a Tor Check tab already exists in the restored session, NiOn reuses and reloads it instead of creating another duplicate tab. This is an additional visible verification step; NiOn's actual fail-closed enforcement still comes from its own Tor runtime and Gecko proxy state.

## Privacy & Data controls

NiOn 0.7.0 adds a Privacy & Data control center to the browser menu. The default Balanced cookie policy uses Gecko cookie partitioning for third-party storage. Strict mode accepts only first-party cookies/site data, while Block all disables cookie/site-data storage and may break logins.

Clear All Browsing Data closes all open Gecko sessions before clearing Gecko storage, then removes the saved tab session. Bookmarks and the selected privacy policy are kept.

Global Privacy Control is enabled at the Gecko runtime level. The Privacy Status dialog summarizes Tor routing, remote DNS, HTTPS-First, permission denial, and the existing WebRTC/WebGL/prefetch hardening.

## HTTPS-First

For clearnet sites, top-level `http://` navigation is upgraded to `https://`.

`.onion` addresses are exempt because HTTP is commonly used inside authenticated Onion Service connections.

When an HTTPS clearnet connection fails, NiOn may offer an explicit one-time HTTP fallback. It does not automatically downgrade in the background.

## Browser essentials

NiOn 0.6.0 adds a compact browser menu with Find in Page, Paste & Go, Copy URL, Share URL, bookmarks, Site Information, and Clear Data for This Site.

Long-pressing a web link offers Open in New Tab and Copy Link. New-tab navigation remains inside GeckoView and therefore continues through NiOn's existing Tor and HTTPS-First navigation policy.

## Downloads

NiOn keeps download network requests inside GeckoView. `ContentDelegate.onExternalResponse()` provides the already-received `WebResponse` body to the app, so NiOn does not issue a second Android-network request for the file.

NiOn 0.8.0 adds a local Download Center with:

- live byte/percentage progress for active transfers;
- cancellation of active transfers;
- persistent local history (up to 100 records);
- open-file actions for completed downloads;
- retry of failed/cancelled source URLs in a new Gecko tab;
- clear-history without deleting downloaded files.

On Android 10 and newer, files are written to the app-owned `MediaStore.Downloads` entry. On Android 8/9, NiOn uses its app-specific downloads directory and exposes completed files to external viewers through Android `FileProvider`.

Download history stores filenames, source URLs, status, progress and file locations locally. `Clear All Browsing Data` cancels any active download, removes its history metadata, and prevents final transfer callbacks from recreating that history. Already-downloaded files are left untouched.

The Download Center's `Clear History` action removes completed, failed, and cancelled records while active transfers continue.

## Favicons

Favicons are discovered from inside Gecko content using a bundled WebExtension bridge.

This avoids introducing a separate Android networking client just to fetch website icons.

Favicons are kept as tab UI state and are not part of the persisted session snapshot.

## Session restore

NiOn stores only the minimal information required to restore tabs:

- URL
- title
- active-tab index

It intentionally does not persist raw Gecko `SessionState`, form fields, or page contents.

Restored background tabs are loaded lazily.

## Permissions

The application manifest intentionally keeps permissions minimal.

Currently required:

```text
android.permission.INTERNET
```

Sensitive web permissions are denied by the browser's permission delegate.

## Development policy

NiOn Android is developed incrementally with physical-device regression testing.

Changes that are closely related should be grouped into a single patch/build/test pass when doing so does not weaken reliability, privacy, or Tor fail-closed behavior.

## Screen lock and lifecycle recovery

When the Android Activity becomes fully hidden, NiOn detaches the active GeckoView display surface while keeping the underlying GeckoSession alive. When the Activity resumes, the existing tab/session is attached again through the normal tab-switch path. This avoids a forced page reload and prevents a stale black rendering surface after screen lock/unlock.

## Reliability and Android integration

NiOn 0.9.0 handles both Gecko content-process crash and kill callbacks, recovering the affected tab from NiOn's URL-only tab state. It also provides an explicit Tor Retry control when bootstrap stalls or Tor becomes unavailable, plus conservative low-memory handling that unloads only background Gecko sessions while preserving their URLs for lazy reload.

Screen rotation is handled without recreating the Activity, while system light/dark mode continues to use Android day/night resources. Gecko web content follows the system preferred color scheme.

The Retry action never enables a direct-network fallback. Browsing remains disabled while bundled Tor restarts, and Gecko continues to use the configured localhost SOCKS proxy with remote DNS.

Launcher resources now include adaptive-icon variants and a monochrome layer for compatible themed-icon launchers.

## Production release signing

NiOn 1.0.0 uses a dedicated Android release signing key. The private keystore is intentionally stored outside the repository at `~/.config/nion-android/nion-release.jks`, and signing passwords are supplied only through the local release-build process.

Create the permanent key once:

```bash
./scripts/setup-release-key.sh
```

Back up that keystore securely. Published Android updates must continue to use the same signing identity.

Build a signed release candidate or stable APK:

```bash
./scripts/build-release-apk.sh
```

Verify its Android signature, ZIP alignment and SHA-256 checksum:

```bash
./scripts/verify-release-apk.sh
```

Release artifacts are written to `dist/` and remain excluded from Git. The stable APK and `SHA256SUMS` are intended to be attached to the GitHub Release rather than committed to the source repository.

## Status

NiOn Android is an independent open-source project under active development.

Before publishing APKs for end users, use a proper Android release signing configuration. Development APKs produced by `assembleDebug` are debug-signed.

## Author

Bryan / jeannesbryan

GitHub: `https://github.com/jeannesbryan`
