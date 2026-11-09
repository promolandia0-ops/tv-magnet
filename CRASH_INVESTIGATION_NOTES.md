# v1.0.1 crash investigation
No device log was available, so the exact v1.0.0 crash is not confirmed. Most likely cause: libtorrent (native library load,
session start and JNI status calls) ran on the Android main thread, and the whole video was requested/allocated in the cache.
Fixes in v1.0.1: global crash handler (stack trace shown on next launch), all torrent/JNI work on one background thread,
sparse storage with a 96 MiB sliding request window and free-space checks, cache wiped at startup/stop, guarded foreground
service start and notifications (POST_NOTIFICATIONS may be denied), and an armeabi-v7a native library.
Limits: native (SIGSEGV) crashes cannot be caught by Java; already-played pieces stay in the private cache until stop.
