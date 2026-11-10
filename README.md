# TV Magnet

Standalone Android app for streaming your own magnet-linked videos to a DLNA TV on the same Wi-Fi. Built for Android 9+ (arm64 and armeabi-v7a), Kotlin, libtorrent4j, NanoHTTPD and Media3/ExoPlayer.

- Paste a magnet, open a `magnet:` URI, or share plain text to TV Magnet.
- Reads the clipboard on foreground (Android may limit this to the foreground app).
- Finds DLNA MediaRenderers through SSDP; manually probes common Samsung ports/descriptions, including 9197 `/dmr` and 7676.
- Stream the largest video file automatically (or select another video).
- Range-aware local HTTP streaming blocks for missing pieces and assigns them urgent deadlines; first and tail pieces are prioritized for indexing.
- DLNA SetAVTransportURI / Play / Pause / Stop / Seek, position feedback and optional rendering volume.
- ExoPlayer on-phone fallback.
- Foreground playback notification, screen-off enabled service. Android 15's dataSync quota limits background streaming to six hours per rolling 24 hours; a notification is not visible if its optional notification permission is denied.
- **Temporary storage only:** buffer inside `cacheDir/stream-buffer`, never gallery or external/shared storage, no keep-file option and no magnet history. The app makes outbound peer-to-peer BitTorrent connections as well as local HTTP control requests; it does not make external HTTP content requests itself. Delete via service stop, task dismissal, or startup cleanup.

## Use

Choose your TV, paste the magnet link, press **Buscar** to list the files, pick a video, then press **Transmitir pra TV** (or **Tocar no celular**). The phone and TV must remain on the same Wi-Fi network, without client isolation. Playback depends on available peers/seeders, Wi-Fi routing, and the TV's codec/container support. One active video session. Adapter-specific Samsung quirks can affect seek, volume and MIME handling; a real Samsung TV is still required for end-to-end confirmation.

This app does not download or host shared videos itself, access a normal file library, or include videos or torrent links.

## Build / validate

Requires an Android SDK, Java 17 and Gradle. Default module package `org.libtorrent4j:libtorrent4j-android-arm64`.

```sh
gradle :app:assembleRelease :app:testDebugUnitTest
```

To sign a release, use the standard Android signing properties in your uncommitted `local.properties`: `TV_MAGNET_KEYSTORE`, `TV_MAGNET_KEY_ALIAS`, `TV_MAGNET_STORE_PASSWORD` and `TV_MAGNET_KEY_PASSWORD`; then run `gradle :app:assembleRelease`. Alternatively, source the existing local `signing.env` and run `tools/sign-release.sh`. Neither the key nor the passwords are committed. Preserve the private signing key for future updates.

## Verification and limitations

Current `v1.0.0`: all 22 JVM tests passed, including the Robolectric app-launch check. Release and debug builds were run with AGP 8.7.3, which supports compile SDK 35. No physical Android device or Samsung TV was available for runtime testing. Native libtorrent4j initialization, tracker/DHT connectivity, codec support and actual TV playback still need to be confirmed on hardware. Release v1.0.1 ships both arm64-v8a and armeabi-v7a native libraries.

## Coverage

- HTTP HEAD, GET and Range: open-ended, bounded, suffix and invalid ranges.
- Piece reads, exact bounds, missing pieces arriving later, EOF and stream closure.
- XML namespaces, whitespace, escaping, and DLNA DIDL MIME/protocol flags.
- Full mock AVTransport command sequence: Stop, SetAVTransportURI, Play, Pause, Play, Seek and Position.
- Robolectric launcher with service callback arriving without its optional attached implementation.
- Release signature (v1/v2/v3 checked using the Android SDK tools), manifest permissions and packaged ARM native library.

Dependency notices in `LICENSE-libtorrent4j.txt` and `LICENSE-NanoHTTPD.txt`. Third-party libs are requested directly from their Maven repositories, not copied from other projects.


Current `v1.0.3`: fixes "nothing plays" (prepare waited for most of the file; see CRASH_INVESTIGATION_NOTES.md). Previous `v1.0.2`: fixes the native crash when starting playback (stale libtorrent handle from an alert), adds the two-step Buscar flow and a next-launch diagnostic from `ApplicationExitInfo`. See CRASH_INVESTIGATION_NOTES.md for what was and was not verified.
