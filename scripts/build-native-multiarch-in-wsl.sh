#!/usr/bin/env bash
# build-native-multiarch-in-wsl.sh
#
# Build the FreeRDP native .so stack (libfreerdp-android.so + OpenSSL + FFmpeg +
# cjson + uriparser + libc++_shared) for one or more Android ABIs on WSL2 Ubuntu,
# then stage them into core-rdp/src/main/jniLibs/<abi>/ so the Windows-side gradle
# build packages all of them into a single fat APK.
#
# Usage (from a WSL2 Ubuntu shell, run as root or with sudo):
#   cd /mnt/d/Document/Git/PocketRDP
#   bash scripts/build-native-multiarch-in-wsl.sh                 # builds the 3 NEW ABIs
#   bash scripts/build-native-multiarch-in-wsl.sh x86             # builds just one
#   bash scripts/build-native-multiarch-in-wsl.sh armeabi-v7a x86 x86_64
#   ABIS="arm64-v8a armeabi-v7a x86 x86_64" bash scripts/build-native-multiarch-in-wsl.sh   # all four
#
# Default ABIs are armeabi-v7a x86 x86_64 — arm64-v8a is left UNTOUCHED because the
# committed arm64 binaries are field-tested (rebuilding risks an ABI-mismatch regression).
# Pass arm64-v8a explicitly (or via ABIS=) only if you really want to rebuild it too.
#
# ONE ABI per gradle invocation on purpose: the OpenSSL/FFmpeg ExternalProjects build
# from a shared source tree, so a concurrent multi-ABI build would race on it.
#
# Idempotent: safe to re-run. ExternalProject stamps make re-runs resume, not restart.

# NOTE: deliberately NO `pipefail`. The only pipes here are reporting/utility ones
# (readelf|awk, ls|wc, find|sort|head) where the downstream command exits early and
# SIGPIPEs the upstream — pipefail would turn that benign SIGPIPE into a non-zero
# pipeline status and `set -e` would abort the whole build mid-verification (it killed
# the x86/x86_64 builds on the first multiarch run). Real gradle failures are single
# commands, still caught by `set -e` and the explicit `if ! ./gradlew` guard.
set -eu

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
JNILIBS_ROOT="$PROJECT_ROOT/core-rdp/src/main/jniLibs"
NDK_VERSION="27.1.12297006"
CMAKE_VERSION="3.22.1"
PLATFORM="platforms;android-36"
CMDLINE_TOOLS_VERSION="11076708_latest"

# ABIs to build: CLI args > $ABIS env > default (the 3 new ones)
if [ "$#" -gt 0 ]; then
    ABIS="$*"
else
    ABIS="${ABIS:-armeabi-v7a x86 x86_64}"
fi

# The 8 .so we stage per ABI from the CMake superbuild (libfreerdp-android = the JNI
# bridge; the rest are its deps). libc++_shared.so is handled separately (from the NDK).
STAGE_LIBS="libfreerdp-android.so libfreerdp3.so libfreerdp-client3.so libwinpr3.so libssl.so libcrypto.so libcjson.so liburiparser.so"

if [ "$EUID" -eq 0 ]; then SUDO=""; else SUDO="sudo"; fi
step() { echo; echo "==== $*"; }

# Mirror every dependency tarball already downloaded under ANY sibling ABI into the
# target ABI's ExternalProject download dir (same .cxx config-hash dir). The generated
# download-<dep>.cmake checks "EXISTS + hash match → skip download", so a seeded tarball
# makes the network step a no-op. This is the China-network-fragility mitigation: the
# FFmpeg/OpenSSL tarballs (n8.1 16 MB, openssl 55 MB) download ONCE (or reuse arm64's
# existing cache) and are reused for every other ABI — no per-ABI re-download to flake on
# (GitHub HTTP/2 framing errors killed the first multiarch run on the FFmpeg fetch).
seed_tarballs() {
    local target_abi="$1"
    [ "$target_abi" = "arm64-v8a" ] && return 0
    local cxx="$PROJECT_ROOT/core-rdp/.cxx"
    [ -d "$cxx" ] || return 0
    # Canonical source = arm64-v8a's download caches (arm64 is fully built, so its tarballs
    # are guaranteed complete). Copy each into the target ABI's matching <dep>-prefix/src,
    # OVERWRITING when the destination is missing or a different size — a previous run's
    # half-finished download leaves a truncated tarball whose hash won't match, and that
    # stale partial must be replaced or the build re-downloads (and re-flakes) it.
    find "$cxx" -type f -path "*/arm64-v8a/*-prefix/src/*.tar.gz" 2>/dev/null | while IFS= read -r tb; do
        local prefixdir hashdir dest destfile
        prefixdir=$(dirname "$(dirname "$tb")")          # <hash>/arm64-v8a/<dep>-prefix
        hashdir=$(dirname "$(dirname "$prefixdir")")      # <hash>
        dest="$hashdir/$target_abi/$(basename "$prefixdir")/src"
        destfile="$dest/$(basename "$tb")"
        if [ ! -f "$destfile" ] || \
           [ "$(stat -c%s "$destfile" 2>/dev/null || echo 0)" != "$(stat -c%s "$tb")" ]; then
            mkdir -p "$dest"
            cp -f "$tb" "$destfile" && echo "  seeded $(basename "$tb") -> $target_abi"
        fi
    done
    return 0
}

# NDK triple for libc++_shared.so lookup, keyed by ABI
ndk_triple() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "arm-linux-androideabi" ;;
        x86)         echo "i686-linux-android" ;;
        x86_64)      echo "x86_64-linux-android" ;;
        *) echo "" ;;
    esac
}

# 1. WSL sanity
if ! grep -qi microsoft /proc/version 2>/dev/null; then
    echo "WARN: this script targets WSL2 Ubuntu. Detected non-WSL environment."
fi

# 2. apt deps (idempotent)
step "Installing apt build dependencies"
$SUDO apt-get update -qq
$SUDO apt-get install -y --no-install-recommends \
    curl unzip ca-certificates xz-utils \
    openjdk-21-jdk-headless \
    build-essential pkg-config perl nasm

export JAVA_HOME=$(readlink -f /usr/bin/javac | sed 's:/bin/javac::')

# 3. Android cmdline-tools
step "Setting up Android SDK at $SDK_DIR"
if [ ! -d "$SDK_DIR/cmdline-tools/latest" ]; then
    mkdir -p "$SDK_DIR/cmdline-tools"
    TOOLS_ZIP=/tmp/android-cmdline-tools.zip
    [ -f "$TOOLS_ZIP" ] || curl -fL --retry 5 -o "$TOOLS_ZIP" \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}.zip"
    unzip -q "$TOOLS_ZIP" -d /tmp/android-tools
    mv /tmp/android-tools/cmdline-tools "$SDK_DIR/cmdline-tools/latest"
    rm -rf /tmp/android-tools "$TOOLS_ZIP"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$PATH"

# 4. NDK + CMake + platform (idempotent)
step "Ensuring NDK $NDK_VERSION, CMake $CMAKE_VERSION, $PLATFORM"
yes 2>/dev/null | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION" "$PLATFORM" "platform-tools" 2>&1 | tail -3

# 5. Pre-stage FFmpeg source so the ExternalProject never needs the network mid-build
step "Pre-staging FFmpeg source"
bash "$PROJECT_ROOT/scripts/fetch-ffmpeg-src.sh" || echo "  (fetch-ffmpeg-src returned non-zero; continuing — source may already be present)"

# 6. Point gradle at the Linux SDK; restore the Windows path on exit no matter what
LOCAL_PROPS="$PROJECT_ROOT/local.properties"
LOCAL_PROPS_BAK="$PROJECT_ROOT/local.properties.windows-backup"
if [ -f "$LOCAL_PROPS" ] && ! [ -f "$LOCAL_PROPS_BAK" ]; then
    cp "$LOCAL_PROPS" "$LOCAL_PROPS_BAK"
    echo "  Backed up local.properties -> local.properties.windows-backup"
fi
restore_local_props() {
    if [ -f "$LOCAL_PROPS_BAK" ]; then
        cp "$LOCAL_PROPS_BAK" "$LOCAL_PROPS"
        echo "  Restored local.properties for Windows-side builds."
    fi
}
trap restore_local_props EXIT
cat > "$LOCAL_PROPS" <<EOF
sdk.dir=$SDK_DIR
EOF

cd "$PROJECT_ROOT"
chmod +x ./gradlew

READELF=$(find "$SDK_DIR/ndk/$NDK_VERSION" -name 'llvm-readelf' 2>/dev/null | head -1)
NDK_LIBCXX_DIR="$SDK_DIR/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"

SEARCH_ROOTS=(
    "core-rdp/build/intermediates/cxx"
    "core-rdp/build/intermediates/merged_native_libs"
    "core-rdp/.cxx"
)

# 7. Build each ABI in its own gradle invocation, then stage its .so
for ABI in $ABIS; do
    TRIPLE=$(ndk_triple "$ABI")
    if [ -z "$TRIPLE" ]; then
        echo "!! Unknown ABI '$ABI' — skipping"; continue
    fi

    step "Building native for $ABI  (gradle :core-rdp:externalNativeBuildDebug -PnativeAbi=$ABI)"
    # Pre-seed so the very first attempt skips the network (works when the ABI's .cxx dir
    # already exists). If a fresh ABI still hits a download, seed again post-failure (the
    # ExternalProject dirs exist by then) and retry once — the retry resumes from the failed
    # download step, finds the seeded tarball, and proceeds. ExternalProject stamps make the
    # retry skip everything already built.
    seed_tarballs "$ABI"
    if ! ./gradlew :core-rdp:externalNativeBuildDebug -PnativeAbi="$ABI" \
            --no-configuration-cache --console=plain --no-daemon; then
        echo "  >> first attempt failed — re-seeding download cache and retrying once"
        seed_tarballs "$ABI"
        ./gradlew :core-rdp:externalNativeBuildDebug -PnativeAbi="$ABI" \
            --no-configuration-cache --console=plain --no-daemon
    fi

    DST="$JNILIBS_ROOT/$ABI"
    mkdir -p "$DST"
    step "Staging .so into jniLibs/$ABI/"
    for lib in $STAGE_LIBS; do
        # Precise '*/<abi>/*' so 'x86' does not also match 'x86_64'. Newest match wins —
        # stale libs from old config-hash dirs linger under .cxx and must not be picked.
        SRC=$(find -L "${SEARCH_ROOTS[@]}" -type f -name "$lib" -path "*/$ABI/*" \
                -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2-)
        if [ -n "$SRC" ]; then
            cp -Lv "$SRC" "$DST/"
        else
            echo "  !! $lib NOT FOUND for $ABI"
        fi
    done

    # libc++_shared.so straight from the NDK (it is not produced by the superbuild)
    if [ -f "$NDK_LIBCXX_DIR/$TRIPLE/libc++_shared.so" ]; then
        cp -v "$NDK_LIBCXX_DIR/$TRIPLE/libc++_shared.so" "$DST/"
    else
        echo "  !! libc++_shared.so NOT FOUND at $NDK_LIBCXX_DIR/$TRIPLE/"
    fi

    # Verify LOAD alignment. 16 KB (0x4000) is REQUIRED for 64-bit ABIs on Android 15+;
    # 32-bit ABIs (armeabi-v7a, x86) do not require it, so just report, don't fail.
    if [ -n "$READELF" ]; then
        step "LOAD alignment check — $ABI"
        case "$ABI" in arm64-v8a|x86_64) NEED16=1 ;; *) NEED16=0 ;; esac
        for f in "$DST"/*.so; do
            align=$("$READELF" -l "$f" 2>/dev/null | awk '/LOAD/{print $NF; exit}')
            if [ "$align" = "0x4000" ]; then status=OK
            elif [ "$NEED16" = 0 ]; then status="$align (32-bit, 16 KB not required)"
            else status="!! NOT 16KB ($align)"; fi
            printf '  %-26s %s\n' "$(basename "$f")" "$status"
        done
    fi
    echo "  -> $(ls "$DST"/*.so 2>/dev/null | wc -l) .so staged in $DST"
done

step "SUCCESS — staged ABIs: $ABIS"
echo
echo "jniLibs tree now:"
for d in "$JNILIBS_ROOT"/*/; do
    printf '  %-14s %s libs\n' "$(basename "$d")" "$(ls "$d"*.so 2>/dev/null | wc -l)"
done
cat <<'EOF'

Next (Windows side):
  .\gradlew.bat :app:assembleDebug --no-configuration-cache --console=plain --no-daemon
  # then verify the APK carries all four ABIs:
  #   unzip -l app\build\outputs\apk\debug\app-debug.apk | findstr lib/
EOF
