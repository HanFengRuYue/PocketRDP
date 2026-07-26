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
NDK_VERSION="29.0.14206865"
NDK_ARCHIVE_NAME="android-ndk-r29-linux.zip"
NDK_ARCHIVE_SHA256="4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf"
CMAKE_VERSION="4.1.2"
CMAKE_ARCHIVE_NAME="cmake-4.1.2-linux.zip"
CMAKE_ARCHIVE_SHA256="46bda18f0f3c28a55f88da4ba05fec21abe3d9bca0a78a8b18d444987b3dc817"
PLATFORM="platforms;android-37.0"
CMDLINE_TOOLS_VERSION="15859902_latest"
CMDLINE_TOOLS_ARCHIVE_NAME="commandlinetools-linux-${CMDLINE_TOOLS_VERSION}.zip"
CMDLINE_TOOLS_ARCHIVE_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"

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

sdkmanager_install() {
    local log
    log=$(mktemp)
    if timeout 180 sdkmanager --install "$@" >"$log" 2>&1; then
        tail -5 "$log"
        rm -f "$log"
        return 0
    fi
    echo "  sdkmanager failed or timed out while installing: $*" >&2
    tail -20 "$log" >&2
    rm -f "$log"
    return 1
}

download_with_windows_fallback() {
    local url="$1"
    local destination="$2"
    local partial="${destination}.part"

    if curl -fL --connect-timeout 15 --speed-time 30 --speed-limit 1024 \
        --retry 2 -C - -o "$partial" "$url"; then
        mv -f "$partial" "$destination"
        return 0
    fi

    if command -v curl.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
        echo "  WSL download failed; retrying through the Windows network stack."
        local windows_partial
        windows_partial=$(wslpath -w "$partial")
        if curl.exe --http1.1 -fL --speed-time 30 --speed-limit 1024 \
            --retry 3 -C - -o "$windows_partial" "$url"; then
            mv -f "$partial" "$destination"
            return 0
        fi
    fi

    rm -f "$partial"
    return 1
}

install_ndk_archive() {
    local cache_dir archive extract_dir extracted
    cache_dir="$PROJECT_ROOT/.gradle/android-sdk-downloads"
    archive="$cache_dir/$NDK_ARCHIVE_NAME"
    mkdir -p "$cache_dir" "$SDK_DIR/ndk"

    if [ ! -f "$archive" ] ||
       ! echo "$NDK_ARCHIVE_SHA256  $archive" | sha256sum -c - >/dev/null 2>&1; then
        rm -f "$archive"
        download_with_windows_fallback \
            "https://dl.google.com/android/repository/$NDK_ARCHIVE_NAME" \
            "$archive"
    fi
    echo "$NDK_ARCHIVE_SHA256  $archive" | sha256sum -c -

    extract_dir=$(mktemp -d "$SDK_DIR/ndk/.r29-extract.XXXXXX")
    unzip -q "$archive" -d "$extract_dir"
    extracted="$extract_dir/android-ndk-r29"
    if [ ! -x "$extracted/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]; then
        rm -rf "$extract_dir"
        echo "  Downloaded NDK archive did not contain the expected Linux toolchain." >&2
        return 1
    fi
    rm -rf "$SDK_DIR/ndk/$NDK_VERSION"
    mv "$extracted" "$SDK_DIR/ndk/$NDK_VERSION"
    rm -rf "$extract_dir"
}

install_cmake_archive() {
    local cache_dir archive extract_dir
    cache_dir="$PROJECT_ROOT/.gradle/android-sdk-downloads"
    archive="$cache_dir/$CMAKE_ARCHIVE_NAME"
    mkdir -p "$cache_dir" "$SDK_DIR/cmake"

    if [ ! -f "$archive" ] ||
       ! echo "$CMAKE_ARCHIVE_SHA256  $archive" | sha256sum -c - >/dev/null 2>&1; then
        rm -f "$archive"
        download_with_windows_fallback \
            "https://dl.google.com/android/repository/$CMAKE_ARCHIVE_NAME" \
            "$archive"
    fi
    echo "$CMAKE_ARCHIVE_SHA256  $archive" | sha256sum -c -

    extract_dir=$(mktemp -d "$SDK_DIR/cmake/.4.1.2-extract.XXXXXX")
    unzip -q "$archive" -d "$extract_dir"
    if [ ! -x "$extract_dir/bin/cmake" ]; then
        rm -rf "$extract_dir"
        echo "  Downloaded CMake archive did not contain the expected Linux executable." >&2
        return 1
    fi
    rm -rf "$SDK_DIR/cmake/$CMAKE_VERSION"
    mv "$extract_dir" "$SDK_DIR/cmake/$CMAKE_VERSION"
}

ensure_native_source_cache() {
    local cache_dir spec filename expected url archive actual
    cache_dir="$PROJECT_ROOT/.gradle/android-native-downloads"
    mkdir -p "$cache_dir"
    for spec in \
        "openssl-4.0.1.tar.gz|2db3f3a0d6ea4b59e1f094ace2c8cd536dffb87cdc39084c5afa1e6f7f37dd09|https://github.com/openssl/openssl/releases/download/openssl-4.0.1/openssl-4.0.1.tar.gz" \
        "v1.7.19.tar.gz|7fa616e3046edfa7a28a32d5f9eacfd23f92900fe1f8ccd988c1662f30454562|https://github.com/DaveGamble/cJSON/archive/refs/tags/v1.7.19.tar.gz" \
        "n8.1.2.tar.gz|9fd092511605bbebafe095ea6d38d9e40f34d12f7386e1258372df8be0576eb7|https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n8.1.2.tar.gz" \
        "uriparser-1.0.2.tar.xz|7b2496f9622b4b201186a3d159d278605f1fb7262402a10cc1c0824889798b00|https://github.com/uriparser/uriparser/releases/download/uriparser-1.0.2/uriparser-1.0.2.tar.xz"; do
        IFS='|' read -r filename expected url <<<"$spec"
        archive="$cache_dir/$filename"
        actual=$(sha256sum "$archive" 2>/dev/null | awk '{print $1}')
        if [ "$actual" != "$expected" ]; then
            rm -f "$archive"
            download_with_windows_fallback "$url" "$archive"
        fi
        echo "$expected  $archive" | sha256sum -c -
    done
}

# Mirror every dependency tarball already downloaded under ANY sibling ABI into the
# target ABI's ExternalProject download dir (same .cxx config-hash dir). The generated
# download-<dep>.cmake checks "EXISTS + hash match → skip download", so a seeded tarball
# makes the network step a no-op. This is the China-network-fragility mitigation: the
# FFmpeg/OpenSSL tarballs (n8.1 16 MB, openssl 55 MB) download ONCE (or reuse arm64's
# existing cache) and are reused for every other ABI — no per-ABI re-download to flake on
# (GitHub HTTP/2 framing errors killed the first multiarch run on the FFmpeg fetch).
seed_tarballs() {
    local target_abi="$1"
    local cxx="$PROJECT_ROOT/core-rdp/.cxx"
    [ -d "$cxx" ] || return 0

    # Versioned, hash-verified central cache. It can seed the first ABI after a dependency
    # upgrade, when no completed ABI exists yet for the new CMake configuration hash.
    local source_cache="$PROJECT_ROOT/.gradle/android-native-downloads"
    local spec dep filename expected source actual dest
    for spec in \
        "openssl|openssl-4.0.1.tar.gz|2db3f3a0d6ea4b59e1f094ace2c8cd536dffb87cdc39084c5afa1e6f7f37dd09" \
        "cjson|v1.7.19.tar.gz|7fa616e3046edfa7a28a32d5f9eacfd23f92900fe1f8ccd988c1662f30454562" \
        "ffmpeg|n8.1.2.tar.gz|9fd092511605bbebafe095ea6d38d9e40f34d12f7386e1258372df8be0576eb7" \
        "uriparser|uriparser-1.0.2.tar.xz|7b2496f9622b4b201186a3d159d278605f1fb7262402a10cc1c0824889798b00"; do
        IFS='|' read -r dep filename expected <<<"$spec"
        source="$source_cache/$filename"
        [ -f "$source" ] || continue
        actual=$(sha256sum "$source" | awk '{print $1}')
        if [ "$actual" != "$expected" ]; then
            echo "  !! ignoring corrupt central cache file $filename" >&2
            continue
        fi
        find "$cxx" -type d -path "*/$target_abi/$dep-prefix/src" 2>/dev/null |
            while IFS= read -r dest; do
                if [ ! -f "$dest/$filename" ] ||
                   [ "$(sha256sum "$dest/$filename" 2>/dev/null | awk '{print $1}')" != "$expected" ]; then
                    cp -f "$source" "$dest/$filename"
                    echo "  seeded verified $filename -> $target_abi"
                fi
            done
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

# A clean submodule contains the official FreeRDP base only. Apply the audited PocketRDP patch
# before configuring CMake; otherwise a seemingly successful rebuild silently drops the JNI,
# certificate, input-queue and Android decoder fixes. The helper also rejects partial patches,
# unrelated tracked changes and untracked files.
step "Verifying PocketRDP FreeRDP patch"
if ! command -v powershell.exe >/dev/null 2>&1 || ! command -v wslpath >/dev/null 2>&1; then
    echo "  powershell.exe and wslpath are required to verify the FreeRDP patch from WSL2." >&2
    exit 1
fi
PATCH_HELPER_WIN=$(wslpath -w "$PROJECT_ROOT/scripts/apply-freerdp-patches.ps1")
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PATCH_HELPER_WIN"

# 2. apt deps (idempotent)
step "Installing apt build dependencies"
if ! command -v curl >/dev/null 2>&1 ||
   ! command -v unzip >/dev/null 2>&1 ||
   ! command -v xz >/dev/null 2>&1 ||
   ! command -v javac >/dev/null 2>&1 ||
   ! javac -version 2>&1 | grep -q '^javac 21\.' ||
   ! command -v cc >/dev/null 2>&1 ||
   ! command -v pkg-config >/dev/null 2>&1 ||
   ! command -v perl >/dev/null 2>&1 ||
   ! command -v nasm >/dev/null 2>&1; then
    $SUDO apt-get update -qq
    $SUDO apt-get install -y --no-install-recommends \
        curl unzip ca-certificates xz-utils \
        openjdk-21-jdk-headless \
        build-essential pkg-config perl nasm
else
    echo "  Required Ubuntu build tools are already installed."
fi

export JAVA_HOME=$(readlink -f /usr/bin/javac | sed 's:/bin/javac::')

# 3. Android cmdline-tools
step "Setting up Android SDK at $SDK_DIR"
CMDLINE_TOOLS_DIR="$SDK_DIR/cmdline-tools/$CMDLINE_TOOLS_VERSION"
if [ ! -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
    TOOLS_CACHE_DIR="$PROJECT_ROOT/.gradle/android-sdk-downloads"
    TOOLS_ZIP="$TOOLS_CACHE_DIR/$CMDLINE_TOOLS_ARCHIVE_NAME"
    mkdir -p "$SDK_DIR/cmdline-tools" "$TOOLS_CACHE_DIR"
    if [ ! -f "$TOOLS_ZIP" ] ||
       ! echo "$CMDLINE_TOOLS_ARCHIVE_SHA256  $TOOLS_ZIP" |
           sha256sum -c - >/dev/null 2>&1; then
        rm -f "$TOOLS_ZIP"
        download_with_windows_fallback \
            "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ARCHIVE_NAME" \
            "$TOOLS_ZIP"
    fi
    echo "$CMDLINE_TOOLS_ARCHIVE_SHA256  $TOOLS_ZIP" | sha256sum -c -
    TOOLS_TMP=$(mktemp -d "$SDK_DIR/cmdline-tools/.tools-extract.XXXXXX")
    unzip -q "$TOOLS_ZIP" -d "$TOOLS_TMP"
    if [ ! -x "$TOOLS_TMP/cmdline-tools/bin/sdkmanager" ]; then
        rm -rf "$TOOLS_TMP"
        echo "  Downloaded command-line tools archive was incomplete." >&2
        exit 1
    fi
    rm -rf "$CMDLINE_TOOLS_DIR"
    mv "$TOOLS_TMP/cmdline-tools" "$CMDLINE_TOOLS_DIR"
    rm -rf "$TOOLS_TMP"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$CMDLINE_TOOLS_DIR/bin:$PATH"

# 4. NDK + CMake + platform (idempotent)
step "Ensuring NDK $NDK_VERSION, CMake $CMAKE_VERSION, $PLATFORM"
if [ ! -s "$SDK_DIR/licenses/android-sdk-license" ]; then
    yes 2>/dev/null | sdkmanager --licenses >/dev/null 2>&1 || true
fi
if [ ! -x "$SDK_DIR/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]; then
    sdkmanager_install "ndk;$NDK_VERSION" || install_ndk_archive
fi
if [ ! -x "$SDK_DIR/cmake/$CMAKE_VERSION/bin/cmake" ]; then
    sdkmanager_install "cmake;$CMAKE_VERSION" || install_cmake_archive
fi
if [ ! -x "$SDK_DIR/platform-tools/adb" ]; then
    sdkmanager_install "platform-tools"
fi
if [ ! -f "$SDK_DIR/platforms/${PLATFORM#*;}/android.jar" ]; then
    sdkmanager_install "$PLATFORM"
fi

# 5. Cache and pre-stage native sources so ExternalProject never needs WSL network mid-build.
step "Caching native dependency archives"
ensure_native_source_cache

step "Pre-staging FFmpeg source"
bash "$PROJECT_ROOT/scripts/fetch-ffmpeg-src.sh"

# 6. Point gradle at the Linux SDK; restore the Windows path on exit no matter what
LOCAL_PROPS="$PROJECT_ROOT/local.properties"
LOCAL_PROPS_BAK="$PROJECT_ROOT/local.properties.windows-backup"
LOCAL_PROPS_WAS_MISSING=0
if ! [ -f "$LOCAL_PROPS" ]; then
    LOCAL_PROPS_WAS_MISSING=1
fi
if [ -f "$LOCAL_PROPS" ] && ! [ -f "$LOCAL_PROPS_BAK" ]; then
    cp "$LOCAL_PROPS" "$LOCAL_PROPS_BAK"
    echo "  Backed up local.properties -> local.properties.windows-backup"
fi
restore_local_props() {
    if [ -f "$LOCAL_PROPS_BAK" ]; then
        cp "$LOCAL_PROPS_BAK" "$LOCAL_PROPS"
        echo "  Restored local.properties for Windows-side builds."
    elif [ "$LOCAL_PROPS_WAS_MISSING" -eq 1 ]; then
        rm -f "$LOCAL_PROPS"
        echo "  Removed temporary WSL local.properties (none existed before the build)."
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

stage_built_libraries() {
    local abi="$1"
    local dst="$2"
    local triple="$3"
    local -a name_expr=()
    local -a search_roots=()
    local -A newest=()
    local model config_hash config_dir obj_dir stage_dir
    local lib stamp src name libcxx_source staged_count root_real dst_real
    local need16 alignment_failed align

    # AGP keeps old CMake configuration hashes indefinitely. Select the configuration model that
    # the just-finished build invocation actually refreshed, then search only that hash. Searching
    # merged_native_libs or every .cxx hash by mtime can select a newer packaging copy from an old
    # native configuration and silently create an ABI-mismatched stack.
    model=$(
        find "$PROJECT_ROOT/core-rdp/build/intermediates/cxx/Debug" -type f \
            -path "*/logs/$abi/build_model.json" -printf '%T@\t%p\n' 2>/dev/null |
            sort -t $'\t' -k1,1nr |
            head -1 |
            cut -f2-
    )
    if [ -z "$model" ]; then
        echo "  !! active AGP native build model not found for $abi" >&2
        return 1
    fi
    config_hash=$(basename "$(dirname "$(dirname "$(dirname "$model")")")")
    config_dir="$PROJECT_ROOT/core-rdp/.cxx/Debug/$config_hash/$abi"
    obj_dir="$PROJECT_ROOT/core-rdp/build/intermediates/cxx/Debug/$config_hash/obj/$abi"
    if [ ! -d "$config_dir" ]; then
        echo "  !! active CMake output directory not found for $abi ($config_hash)" >&2
        return 1
    fi
    search_roots=("$config_dir")
    if [ -d "$obj_dir" ]; then search_roots+=("$obj_dir"); fi

    # Walk the active configuration once per ABI, not once per library. Sort newest-first and
    # retain the first path for each basename (build and install copies may both exist).
    for lib in $STAGE_LIBS; do
        if [ "${#name_expr[@]}" -gt 0 ]; then name_expr+=(-o); fi
        name_expr+=(-name "$lib")
    done
    while IFS=$'\t' read -r stamp src; do
        [ -n "$src" ] || continue
        name=$(basename "$src")
        if [ -z "${newest[$name]+present}" ]; then
            newest["$name"]="$src"
        fi
    done < <(
        find -L "${search_roots[@]}" -type f \
            \( "${name_expr[@]}" \) -printf '%T@\t%p\n' 2>/dev/null |
            sort -t $'\t' -k1,1nr
    )

    # Stage into a sibling temporary directory and publish only after the complete set validates.
    # Copying directly over jniLibs would leave stale old-version libraries behind when one expected
    # output is missing, exactly the mixed-stack failure this script is meant to prevent.
    stage_dir=$(mktemp -d "$JNILIBS_ROOT/.${abi}-stage.XXXXXX")
    for lib in $STAGE_LIBS; do
        src="${newest[$lib]:-}"
        if [ -n "$src" ]; then
            cp -Lv "$src" "$stage_dir/"
        else
            echo "  !! $lib NOT FOUND for $abi in active config $config_hash" >&2
            rm -rf "$stage_dir"
            return 1
        fi
    done

    libcxx_source="$NDK_LIBCXX_DIR/$triple/libc++_shared.so"
    if [ ! -f "$libcxx_source" ]; then
        echo "  !! libc++_shared.so NOT FOUND at $libcxx_source" >&2
        rm -rf "$stage_dir"
        return 1
    fi
    cp -Lv "$libcxx_source" "$stage_dir/"

    staged_count=$(find "$stage_dir" -maxdepth 1 -type f -name '*.so' -print | wc -l)
    if [ "$staged_count" -ne 9 ]; then
        echo "  !! expected 9 native libraries for $abi, staged $staged_count" >&2
        rm -rf "$stage_dir"
        return 1
    fi

    # Validate the temporary set before replacing the last known-good jniLibs. A failed ELF or
    # 64-bit 16 KB alignment check must not publish a broken release merely because the script
    # detects the problem immediately afterwards.
    if [ -z "$READELF" ]; then
        echo "  !! llvm-readelf not found; refusing to publish unverified native libraries" >&2
        rm -rf "$stage_dir"
        return 1
    fi
    case "$abi" in arm64-v8a|x86_64) need16=1 ;; *) need16=0 ;; esac
    alignment_failed=0
    for lib in "$stage_dir"/*.so; do
        align=$("$READELF" -l "$lib" 2>/dev/null | awk '/LOAD/{print $NF; exit}')
        if [ -z "$align" ] || { [ "$need16" -eq 1 ] && [ "$align" != "0x4000" ]; }; then
            echo "  !! refusing invalid/alignment-incompatible $(basename "$lib"): ${align:-no LOAD}" >&2
            alignment_failed=1
        fi
    done
    if [ "$alignment_failed" -ne 0 ]; then
        rm -rf "$stage_dir"
        return 1
    fi

    # Resolve and validate the destructive target before replacing its .so set.
    root_real=$(realpath -m "$JNILIBS_ROOT")
    dst_real=$(realpath -m "$dst")
    if [ "$dst_real" != "$root_real/$abi" ]; then
        echo "  !! refusing unexpected jniLibs destination: $dst_real" >&2
        rm -rf "$stage_dir"
        return 1
    fi
    mkdir -p "$dst"
    find "$dst" -maxdepth 1 -type f -name '*.so' -delete
    mv "$stage_dir"/*.so "$dst/"
    rmdir "$stage_dir"
}

# 7. Build each ABI in its own gradle invocation, then stage its .so
for ABI in $ABIS; do
    TRIPLE=$(ndk_triple "$ABI")
    if [ -z "$TRIPLE" ]; then
        echo "!! Unknown ABI '$ABI'; refusing an incomplete build" >&2
        exit 1
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
    stage_built_libraries "$ABI" "$DST" "$TRIPLE"

    # Verify LOAD alignment. 16 KB (0x4000) is REQUIRED for 64-bit ABIs on Android 15+;
    # 32-bit ABIs (armeabi-v7a, x86) do not require it. Invalid ELF output fails every ABI.
    if [ -n "$READELF" ]; then
        step "LOAD alignment check — $ABI"
        case "$ABI" in arm64-v8a|x86_64) NEED16=1 ;; *) NEED16=0 ;; esac
        ALIGNMENT_FAILED=0
        for f in "$DST"/*.so; do
            align=$("$READELF" -l "$f" 2>/dev/null | awk '/LOAD/{print $NF; exit}')
            if [ "$align" = "0x4000" ]; then status=OK
            elif [ -z "$align" ]; then status="!! invalid ELF or missing LOAD segment"; ALIGNMENT_FAILED=1
            elif [ "$NEED16" = 0 ]; then status="$align (32-bit, 16 KB not required)"
            else status="!! NOT 16KB ($align)"; ALIGNMENT_FAILED=1; fi
            printf '  %-26s %s\n' "$(basename "$f")" "$status"
        done
        if [ "$ALIGNMENT_FAILED" -ne 0 ]; then
            echo "  !! native alignment validation failed for $ABI" >&2
            exit 1
        fi
    else
        echo "  !! llvm-readelf not found; refusing to publish unverified native libraries" >&2
        exit 1
    fi
    LIB_COUNT=$(find "$DST" -maxdepth 1 -type f -name '*.so' -print | wc -l)
    if [ "$LIB_COUNT" -ne 9 ]; then
        echo "  !! expected exactly 9 .so files in $DST, found $LIB_COUNT" >&2
        exit 1
    fi
    echo "  -> $LIB_COUNT .so staged in $DST"
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
