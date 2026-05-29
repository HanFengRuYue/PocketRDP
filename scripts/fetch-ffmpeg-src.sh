#!/usr/bin/env bash
# Pre-fetch FFmpeg n8.1 source into the cpp/external/ffmpeg SOURCE_DIR so the CMake
# ExternalProject uses it directly (no fragile build-time GitHub download). Tries several mirrors.
set -uo pipefail
VER="n8.1"
DEST="/mnt/d/Document/Git/PocketRDP/third_party/FreeRDP/client/Android/Studio/freeRDPCore/src/main/cpp/external/ffmpeg"
TGZ="/tmp/ffmpeg-${VER}.tar.gz"

if [ -f "$DEST/configure" ]; then
    echo "FFmpeg source already present at $DEST"; exit 0
fi

URLS=(
    "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/${VER}.tar.gz"
    "https://mirror.ghproxy.com/https://github.com/FFmpeg/FFmpeg/archive/refs/tags/${VER}.tar.gz"
    "https://ghfast.top/https://github.com/FFmpeg/FFmpeg/archive/refs/tags/${VER}.tar.gz"
    "https://gitee.com/mirrors/ffmpeg/repository/archive/${VER}.tar.gz"
)

ok=0
for u in "${URLS[@]}"; do
    echo "==== trying $u"
    if curl -fL --connect-timeout 20 --retry 2 -o "$TGZ" "$u" && [ -s "$TGZ" ]; then
        # sanity: is it a gzip tarball big enough to be FFmpeg (>5MB)?
        sz=$(stat -c %s "$TGZ")
        if [ "$sz" -gt 5000000 ]; then ok=1; echo "  downloaded $sz bytes"; break; fi
        echo "  too small ($sz), trying next"
    fi
done
[ "$ok" = 1 ] || { echo "FATAL: all FFmpeg mirrors failed"; exit 1; }

mkdir -p "$DEST"
# Extract: archive top dir is FFmpeg-n8.1/ (github) or ffmpeg-<hash>/ (gitee). Strip it.
tar xzf "$TGZ" -C /tmp
top=$(tar tzf "$TGZ" | head -1 | cut -d/ -f1)
echo "==== extracting $top -> $DEST"
cp -a "/tmp/$top/." "$DEST/"
rm -rf "/tmp/$top" "$TGZ"

if [ -f "$DEST/configure" ]; then
    echo "SUCCESS: FFmpeg source staged ($(du -sh "$DEST" | cut -f1))"
else
    echo "FATAL: configure not found after extract"; exit 1
fi
