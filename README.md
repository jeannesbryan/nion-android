# NiOn Android

**NiOn** is a minimal Tor-only web browser for Android.

It uses **GeckoView** for web rendering and a **bundled Tor runtime** for networking. NiOn does not require a system Tor installation and is designed to fail closed rather than silently fall back to a direct connection.

> NiOn Android is a separate project from the Linux version of NiOn.  
> NiOn is an independent project and is **not Tor Browser**.

## Current release

**1.0.0 — Stable**

Android 8.0+ / API 26+

Download the signed APK from the repository's **GitHub Releases** page:

https://github.com/jeannesbryan/nion-android/releases

Release assets:

```text
nion_1.0.0.apk
SHA256SUMS
```

The release APK is signed with the permanent NiOn Android release key. APK binaries are distributed through GitHub Releases and are not committed to the source repository.

## Highlights

- Bundled Tor runtime
- Tor-only GeckoView networking
- Remote DNS through Tor
- `.onion` support
- Fail-closed browsing when Tor is unavailable
- Tor bootstrap status and Retry Tor control
- Automatic Tor Check page after successful startup
- HTTPS-First for clearnet
- Explicit one-shot HTTP fallback when HTTPS fails
- Multi-tab browsing with up to 8 tabs
- Popup / `target="_blank"` handling
- Android Back navigation
- Privacy-aware session restore
- Simple bookmarks
- Website favicons
- Find in Page
- Paste & Go
- Copy URL and Android Share
- Long-press link actions
- Site & Connection Information
- Clear Data for This Site
- Privacy & Data controls
- Global Privacy Control
- Tor-routed downloads
- Download progress, history, cancel, retry, and Open File
- Gecko content-process crash / kill recovery
- Low-memory background-tab unloading
- Screen lock / unlock recovery
- Rotation handling
- System light / dark integration
- Adaptive launcher icon
- Accessibility descriptions for browser controls

## Tor and fail-closed model

NiOn starts its bundled Tor service and waits for Tor bootstrap before enabling browsing.

GeckoView is configured to use the Tor SOCKS proxy on localhost with remote DNS enabled. The proxy bypass list is empty and native DNS-over-HTTPS/TRR is disabled.

If Tor becomes unavailable, NiOn disables browsing and enters a blocked state instead of silently switching to the device's normal network connection.

The **Retry Tor** action restarts the bundled Tor service while browsing remains blocked until Tor is ready again.

Both clearnet and `.onion` browsing use the same Tor-gated GeckoView path.

## Privacy baseline

NiOn intentionally uses a conservative browser configuration.

The Android manifest requests only:

```text
android.permission.INTERNET
```

NiOn does not request Android camera, microphone, location, or storage permissions for normal browsing.

The browser also disables or restricts several web capabilities that can increase privacy or leak risk, including:

- WebRTC
- WebGL
- geolocation
- web notifications
- push
- DNS prefetching
- link prefetching
- network prediction
- speculative connections
- browser pings

Sensitive web permission requests are denied by NiOn's permission delegate.

### Cookie policy

NiOn provides three cookie modes:

**Balanced** — default and recommended; normal first-party cookies with third-party partitioning.

**Strict** — first-party cookies only.

**Block all** — blocks cookies entirely.

The selected policy is stored as a browser preference.

### Global Privacy Control

Global Privacy Control is enabled in Gecko.

### Session restore

NiOn intentionally avoids persisting raw Gecko session state.

Only minimal tab information is stored:

- URL
- title
- active-tab index

Form contents and raw Gecko `SessionState` are not persisted. Restored background tabs are loaded lazily.

## HTTPS-First

Top-level clearnet `http://` navigation is upgraded to `https://`.

`.onion` addresses are exempt from this upgrade because Onion Services commonly use HTTP inside the authenticated Tor connection.

If a clearnet HTTPS request fails, NiOn can perform an explicit one-shot HTTP fallback. It does not silently downgrade in the background.

## Downloads

NiOn does **not** use Android `DownloadManager` to perform the network request.

Download response bodies are consumed from GeckoView's existing response stream, keeping the request inside Gecko's Tor-routed networking path.

The Download Center supports:

- progress
- local download history
- cancellation
- retry through Gecko
- opening completed files
- clearing download history

On Android 10+, completed files are written through `MediaStore.Downloads`.

On Android 8 and 9, NiOn uses its app-specific downloads directory and `FileProvider` when opening completed files.

Partial files are removed when a transfer fails or is cancelled.

`Clear All Browsing Data` also clears NiOn's download-history metadata and cancels active downloads, but it does not delete files that were already completed.

## Reliability

NiOn includes several Android and Gecko lifecycle protections:

- active tabs receive high Gecko priority
- inactive tabs are marked inactive
- background Gecko sessions may be unloaded under memory pressure
- unloaded background tabs retain their URL and reload lazily when selected
- Gecko content-process crash and kill callbacks recover the affected tab from URL-only state
- screen lock / unlock detaches and reattaches the active Gecko display without forcing a normal page reload
- screen rotation preserves the Activity and current session objects
- system light / dark preference is passed to Gecko

## Technology

- Kotlin + Java
- Android XML Views
- GeckoView
- Guardian Project `tor-android`
- `jtorctl`
- Gradle / Android Gradle Plugin
- JDK 17
- Minimum Android API 26

Development is CLI-first. Android Studio and an emulator are not required.

## Install

Download `nion_1.0.0.apk` from GitHub Releases.

With ADB:

```bash
adb install nion_1.0.0.apk
```

For an existing NiOn installation signed with the same production key:

```bash
adb install -r nion_1.0.0.apk
```

Launch manually if needed:

```bash
adb shell am start \
  -n io.github.jeannesbryan.nion/.MainActivity
```

Package name:

```text
io.github.jeannesbryan.nion
```

## Build from source

### Requirements

- JDK 17
- Android SDK
- Android Build Tools
- ADB for physical-device testing

Clone:

```bash
git clone https://github.com/jeannesbryan/nion-android.git
cd nion-android
```

Configure the Android SDK in `local.properties`, for example:

```properties
sdk.dir=/home/USERNAME/Android/Sdk
```

### Development APK

```bash
./scripts/build-apk.sh
```

This uses the development/debug build path and copies the resulting APK to:

```text
dist/nion_<version>.apk
```

### Production release build

The production signing key is intentionally **not** part of this repository.

Maintainers can create a local release key with:

```bash
./scripts/setup-release-key.sh
```

The default local key location is:

```text
~/.config/nion-android/nion-release.jks
```

A published signing key must be backed up securely and reused for future updates.

Build the signed release APK:

```bash
./scripts/build-release-apk.sh
```

Verify the signature, ZIP alignment, and SHA-256 checksum:

```bash
./scripts/verify-release-apk.sh
```

Release output:

```text
dist/nion_1.0.0.apk
dist/SHA256SUMS
```

Never commit release keystores, passwords, or private signing material.

## Regression audits

The repository includes static regression checks for the privacy, download, reliability, and production-release baselines:

```bash
./scripts/audit-privacy-070.sh
./scripts/audit-downloads-080.sh
./scripts/audit-reliability-090.sh
./scripts/audit-final-100.sh
```

These checks supplement physical-device testing; they are not a substitute for an independent security audit.

## Project structure

```text
app/
  src/main/
    java/io/github/jeannesbryan/nion/
    res/
    assets/

artwork/
  nion.svg

scripts/
  build-apk.sh
  setup-release-key.sh
  build-release-apk.sh
  verify-release-apk.sh
  audit-privacy-070.sh
  audit-downloads-080.sh
  audit-reliability-090.sh
  audit-final-100.sh
```

`artwork/nion.svg` is the source artwork for the NiOn launcher icon.

Generated build directories, `dist/`, APK files, local Android configuration, and signing material are intentionally excluded from Git.

## Security note

NiOn is designed around Tor-only routing and a deliberately restricted browser configuration, but it has its own implementation and threat model.

**NiOn is not Tor Browser and does not claim security or anonymity parity with Tor Browser.**

The project has not undergone the same level of security review, hardening, or ecosystem testing as Tor Browser. Users with a high-risk threat model should prefer Tor Browser and the official Tor Project guidance.

## Development policy

Privacy, Tor routing, remote DNS, and fail-closed behavior are treated as release invariants.

Changes that touch networking, Gecko lifecycle, permissions, downloads, or Tor state should be regression-tested before release.

The project is tested primarily on physical Android hardware through ADB.
