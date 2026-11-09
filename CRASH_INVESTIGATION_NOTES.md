# v1.0.2 crash investigation

**Root cause (reproduced on the build box, not on a phone):** libtorrent4j `TorrentHandle` objects taken from an *alert*
(`AddTorrentAlert.handle()`) were stored and used later on another thread (v1.0.0/v1.0.1 did this). Alert memory is freed when
the alert callback returns, so the handle points at freed native memory. The next native call on it
(`prioritizeFiles` / `filePriority` / `status`, i.e. what starts the stream when the user taps play) either throws
"invalid torrent handle" or **segfaults** (`SIGSEGV` in `torrent_handle_prioritize_files_ex` / `file_priority_ex`, captured with a
real desktop libtorrent4j build). A native SIGSEGV cannot be caught by the Java crash handler, which is why v1.0.1 showed nothing and the app just closed.

Fixes in v1.0.2
- The info-hash is copied inside the alert callback; every handle is re-resolved with `SessionManager.find()` and checked with `isValid()`.
- Per-file `filePriority` instead of the vector call `prioritizeFiles`.
- Two-step flow: **Buscar** fetches metadata only and lists files (videos first, largest preselected, nothing streamed;
  per-torrent 1 B/s cap while listing). **Transmitir pra TV** / **Tocar no celular** select the file, start the local server and wait for the first/last pieces.
- All server start / SSDP / SOAP / torrent work runs in coroutines on `Dispatchers.IO` with try/catch that becomes a UI message.
- libtorrent uses plain (non-mmap) disk IO and sequential download; session started with the library defaults (alert mask, DHT).
- Next launch shows `ApplicationExitInfo` (reason, status, native tombstone text) plus the last breadcrumbs (`last-steps.txt`) and any Java stack trace.
- Audit: `usesCleartextTraffic=true`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (MulticastLock) present; `NEARBY_WIFI_DEVICES`
  is not needed for SSDP/HTTP on targetSdk 35 and was not added; foreground service types dataSync|mediaPlayback have their permissions declared.

Verified: 23 JVM tests pass; opt-in `EndToEndTorrentTest` (real libtorrent4j on Linux + the app's TorrentStream and StreamServer) against a
local synthetic test torrent: metadata → file list → nothing downloaded → pick → HTTP Range 206 byte-identical (head, middle, tail).
Not verified: a real Samsung phone/TV, the Android (bionic) native libraries at runtime (an x86_64 emulator booted and showed the new
screen, but software emulation was too slow to drive), and the public Sintel torrent (no peers reachable from the build box).
