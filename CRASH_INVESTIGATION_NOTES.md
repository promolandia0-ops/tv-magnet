# v1.0.3: "nothing plays" in v1.0.2

**Root cause (reproduced with a rate-limited local seeder, 1.5 MB/s):** v1.0.2 waited for the first 3 MB *and* the last 1 MB of the
file before serving, but only the last 3 *pieces* had high priority and the torrent ran in sequential mode, so the rest of that
1 MB tail was fetched last. "Prepare" therefore finished only after nearly the whole file had been downloaded (about 40 s for 61 MB
here; on a real swarm that is minutes, so both "Transmitir pra TV" and "Tocar no celular" looked dead and timed out).
Earlier seeders on localhost were unthrottled and hid this. A second weakness: the whole torrent was capped at 1 B/s while the file
list was shown, so peers got no data and no prefetch happened.

Fixes in v1.0.3
- No download cap. While the list is shown only the preselected video's first 6 MB and last 2 MB are prefetched (bounded).
- After the pick: head and tail pieces priority 7, rest of a 96 MiB window default, everything else off, sequential, and
  `setPieceDeadline` on the first pieces. The HTTP server's reader re-prioritises (priority 7 + deadlines) the next 8 pieces it is waiting for.
- "Prepare" waits only for the first 2 MiB (150 s limit, clear message). Players fetch the mp4 index themselves; the server blocks on exactly those pieces.
- Progress shows buffered MB at the play position, percent of the pre-buffer, speed and peers.

Verified on the build box (synthetic 61 MB mp4 with the index at the END, local seeder throttled to 1.5 MB/s):
- JVM, real libtorrent4j + the app's StreamService: Buscar listing, wait on the list (0/3/20 s), `prepareLocal` returned in 2.8-5 s while only 8-36 MB of 61 MB were downloaded
  (v1.0.2 code: ~40 s, whole file); ffprobe, a 20 s ffmpeg decode and a seek to 90 s on the local URL all succeeded while still downloading.
- Fake Samsung DLNA renderer (`tools/fake_renderer.py`): Stop, SetAVTransportURI, Play -> HEAD, `Range: bytes=0-` (12 MB), tail range, all 206.
- Android 14 x86_64 emulator, real app code (instrumentation, no UI): search -> prepareLocal -> real ExoPlayer reached 6+ s of playback. The emulator has no KVM,
  so it is ~10x slower than a phone; startup took about a minute there. A server probe showed 1 MB in under 3 s after the piece-availability cache was added (47 s before).
- 24 JVM tests incl. the opt-in end-to-end ones.
Not verified: a real public swarm (no BitTorrent egress here), a real Samsung TV/phone, arm native libs at runtime, the touch UI on the emulator (too slow to drive).

---

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
