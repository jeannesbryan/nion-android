# NiOn Android

**NiOn** is a minimal Android browser designed to route browsing through the Tor network only.

NiOn Android is a separate project from the Linux version of NiOn. It uses GeckoView for web rendering and bundles Tor for Android so the browser does not depend on a system Tor installation.

## Current version

**0.5.0**

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
dist/nion_0.5.0.apk
```

`dist/` is intentionally ignored by Git. APKs intended for distribution should be attached to a GitHub Release instead of committed to the repository.

## Install on a physical Android device

Enable USB debugging, connect the device, then:

```bash
adb devices
adb install -r dist/nion_0.5.0.apk
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

## HTTPS-First

For clearnet sites, top-level `http://` navigation is upgraded to `https://`.

`.onion` addresses are exempt because HTTP is commonly used inside authenticated Onion Service connections.

When an HTTPS clearnet connection fails, NiOn may offer an explicit one-time HTTP fallback. It does not automatically downgrade in the background.

## Downloads

NiOn does not delegate normal downloads to Android `DownloadManager`.

Download response bodies are consumed from GeckoView so the original request remains part of Gecko's Tor-routed networking path.

On modern Android versions, completed files are written to the system Downloads collection. Older supported Android versions use the app-specific downloads directory.

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

## Status

NiOn Android is an independent open-source project under active development.

Before publishing APKs for end users, use a proper Android release signing configuration. Development APKs produced by `assembleDebug` are debug-signed.

## Author

Bryan / jeannesbryan

GitHub: `https://github.com/jeannesbryan`
