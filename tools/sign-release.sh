#!/usr/bin/env bash
set -euo pipefail
set +x  # Never expose local signing variables in tracing output.
# The signing key and environment values stay outside git.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KS="${KS:-/workspace/tv-magnet-keystore/tv-magnet.jks}"
APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
ALIGNED="$ROOT/app/build/outputs/apk/release/app-release-aligned-unsigned.apk"
SIGNED="$ROOT/app/build/outputs/apk/release/TVMagnet-v1.0.0.apk"
: "${ANDROID_HOME:?Set ANDROID_HOME to the installed Android SDK}"
: "${TV_MAGNET_STORE_PASSWORD:?Export the keystore password}"
: "${TV_MAGNET_KEY_PASSWORD:?Export the key password}"
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -f -p 4 "$APK" "$ALIGNED"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$KS" --ks-pass env:TV_MAGNET_STORE_PASSWORD --key-pass env:TV_MAGNET_KEY_PASSWORD --out "$SIGNED" "$ALIGNED"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --min-sdk-version 28 --print-certs "$SIGNED"
