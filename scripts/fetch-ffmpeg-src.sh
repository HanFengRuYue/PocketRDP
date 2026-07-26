#!/usr/bin/env bash
# Pre-stage the pinned FFmpeg source so ExternalProject never depends on a mid-build download.
set -euo pipefail

VER="n8.1.2"
EXPECTED_SHA256="9fd092511605bbebafe095ea6d38d9e40f34d12f7386e1258372df8be0576eb7"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$PROJECT_ROOT/third_party/FreeRDP/client/Android/Studio/freeRDPCore/src/main/cpp/external/ffmpeg"
VERSION_MARKER="$DEST/.pocketrdp-source-version"
CACHED_TGZ="$PROJECT_ROOT/.gradle/android-native-downloads/${VER}.tar.gz"
DEST_PARENT="$(dirname "$DEST")"
mkdir -p "$DEST_PARENT"
WORK_DIR="$(mktemp -d "$DEST_PARENT/.ffmpeg-refresh.XXXXXX")"
STAGE_DIR="$WORK_DIR/stage"
BACKUP="$WORK_DIR/previous"
TGZ="$WORK_DIR/ffmpeg-${VER}.tar.gz"
mkdir -p "$STAGE_DIR"
cleanup() {
    # If the process is interrupted in the narrow window after moving the previous source aside
    # but before publishing the verified stage, restore the last known-good tree. Otherwise the
    # EXIT trap would delete the only backup together with WORK_DIR.
    if [ ! -d "$DEST" ] && [ -d "$BACKUP" ]; then
        mv "$BACKUP" "$DEST"
    fi
    rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT

URLS=(
    "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/${VER}.tar.gz"
    "https://ghfast.top/https://github.com/FFmpeg/FFmpeg/archive/refs/tags/${VER}.tar.gz"
)

downloaded=0
if [ -f "$CACHED_TGZ" ] &&
   [ "$(sha256sum "$CACHED_TGZ" | awk '{print $1}')" = "$EXPECTED_SHA256" ]; then
    cp "$CACHED_TGZ" "$TGZ"
    downloaded=1
    echo "==== using verified native-source cache $CACHED_TGZ"
fi
for url in "${URLS[@]}"; do
    [ "$downloaded" = 0 ] || break
    echo "==== trying $url"
    if curl -fL --connect-timeout 20 --retry 2 -o "$TGZ" "$url"; then
        actual=$(sha256sum "$TGZ" | awk '{print $1}')
        if [ "$actual" = "$EXPECTED_SHA256" ]; then
            downloaded=1
            break
        fi
        echo "  SHA-256 mismatch: expected $EXPECTED_SHA256, got $actual"
    fi
done
[ "$downloaded" = 1 ] || { echo "FATAL: no FFmpeg mirror produced the pinned archive"; exit 1; }

tar xzf "$TGZ" --strip-components=1 -C "$STAGE_DIR"
[ -f "$STAGE_DIR/configure" ] || { echo "FATAL: configure missing from archive"; exit 1; }
printf '%s\n' "$VER" > "$STAGE_DIR/.pocketrdp-source-version"

if [ -d "$DEST" ]; then mv "$DEST" "$BACKUP"; fi
if ! mv "$STAGE_DIR" "$DEST"; then
    if [ -d "$BACKUP" ]; then mv "$BACKUP" "$DEST"; fi
    exit 1
fi
rm -rf -- "$BACKUP"
rm -rf -- "$WORK_DIR"
trap - EXIT
echo "SUCCESS: FFmpeg $VER source staged ($(du -sh "$DEST" | cut -f1))"
