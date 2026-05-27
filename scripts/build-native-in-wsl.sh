#!/usr/bin/env bash
# build-native-in-wsl.sh
#
# Build FreeRDP native .so files (libfreerdp-android.so + deps) on WSL2 Ubuntu,
# then copy them into core-rdp/src/main/jniLibs/arm64-v8a/ so the Windows-side
# gradle build can pick them up as prebuilt artefacts.
#
# Usage (from WSL Ubuntu shell):
#   cd /mnt/d/Document/Git/PocketRDP
#   bash scripts/build-native-in-wsl.sh
#
# Idempotent: safe to re-run. Installs JDK/SDK/NDK only if missing.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
JNILIBS_DIR="$PROJECT_ROOT/core-rdp/src/main/jniLibs/arm64-v8a"
NDK_VERSION="27.1.12297006"
CMAKE_VERSION="3.22.1"
PLATFORM="platforms;android-36"
CMDLINE_TOOLS_VERSION="11076708_latest"

if [ "$EUID" -eq 0 ]; then
    SUDO=""
else
    SUDO="sudo"
fi

step() { echo; echo "==== $*"; }

# 1. WSL sanity check
if ! grep -qi microsoft /proc/version 2>/dev/null; then
    echo "WARN: this script targets WSL2 Ubuntu. Detected non-WSL environment."
fi

# 2. apt deps
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
    if [ ! -f "$TOOLS_ZIP" ]; then
        curl -fL --retry 5 -o "$TOOLS_ZIP" \
            "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}.zip"
    fi
    unzip -q "$TOOLS_ZIP" -d /tmp/android-tools
    mv /tmp/android-tools/cmdline-tools "$SDK_DIR/cmdline-tools/latest"
    rm -rf /tmp/android-tools "$TOOLS_ZIP"
    echo "  cmdline-tools installed."
else
    echo "  cmdline-tools already present."
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$PATH"

# 4. sdkmanager: accept licenses + install NDK 27 + CMake 3.22 + platform 36
step "Installing NDK $NDK_VERSION, CMake $CMAKE_VERSION, $PLATFORM via sdkmanager"
yes 2>/dev/null | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION" "$PLATFORM" "platform-tools" 2>&1 | tail -5

# 5. Project local.properties: point gradle at the Linux SDK
LOCAL_PROPS="$PROJECT_ROOT/local.properties"
LOCAL_PROPS_BAK="$PROJECT_ROOT/local.properties.windows-backup"
if [ -f "$LOCAL_PROPS" ] && ! [ -f "$LOCAL_PROPS_BAK" ]; then
    cp "$LOCAL_PROPS" "$LOCAL_PROPS_BAK"
    echo "  Backed up existing local.properties -> local.properties.windows-backup"
fi
cat > "$LOCAL_PROPS" <<EOF
sdk.dir=$SDK_DIR
EOF

# 6. Build native
step "Running :core-rdp:externalNativeBuildDebug (30-60 min first time)"
cd "$PROJECT_ROOT"
chmod +x ./gradlew
./gradlew :core-rdp:externalNativeBuildDebug --no-configuration-cache --console=plain --no-daemon

# 7. Restore Windows local.properties so subsequent Windows builds work
if [ -f "$LOCAL_PROPS_BAK" ]; then
    cp "$LOCAL_PROPS_BAK" "$LOCAL_PROPS"
    echo "  Restored local.properties for Windows-side builds."
fi

# 8. Locate built .so and stage into jniLibs
step "Copying .so into core-rdp/src/main/jniLibs/arm64-v8a/"
mkdir -p "$JNILIBS_DIR"
FOUND_LIBS=()
# AGP places merged jniLibs at intermediates; CMake build output at .cxx/.
SEARCH_ROOTS=(
    "core-rdp/build/intermediates/cxx"
    "core-rdp/build/intermediates/merged_native_libs"
    "core-rdp/.cxx"
)
for lib in libfreerdp-android.so libfreerdp3.so libfreerdp-client3.so libwinpr3.so libssl.so libcrypto.so libcjson.so liburiparser.so; do
    SRC=$(find "${SEARCH_ROOTS[@]}" -type f -name "$lib" -path "*arm64-v8a*" 2>/dev/null | head -1)
    if [ -n "$SRC" ]; then
        cp -v "$SRC" "$JNILIBS_DIR/"
        FOUND_LIBS+=("$lib")
    fi
done
echo
echo "Copied ${#FOUND_LIBS[@]} libraries to $JNILIBS_DIR:"
ls -la "$JNILIBS_DIR"

step "SUCCESS"
cat <<EOF

Native .so files are staged at:
  $JNILIBS_DIR

Next steps (back on Windows side):
  1. Comment out the externalNativeBuild/ndk/packaging blocks in
     core-rdp/build.gradle.kts (so Windows gradle just packages the prebuilt
     .so, no native toolchain needed).
  2. Run: .\\gradlew.bat :app:assembleDebug --no-configuration-cache --console=plain --no-daemon
  3. Install the new APK, try connecting to a real RDP host.
EOF
