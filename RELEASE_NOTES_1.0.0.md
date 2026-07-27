# NiOn Android 1.0.0

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
