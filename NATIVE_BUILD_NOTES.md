# Native (FreeRDP) build notes

PocketRDP normally packages the committed native libraries from
`core-rdp/src/main/jniLibs/`; a normal Windows Gradle build does not compile C/C++.
Native rebuilding is supported only in WSL2 and is deliberately gated by
`-PnativeAbi=<abi>`.

## Pinned toolchain and sources

| Component | Pinned version |
| --- | --- |
| FreeRDP | Official `3.30.0` commit `6b107f0aadbabc47941c5a5b893b88c01792af6d` |
| PocketRDP FreeRDP patch | `patches/freerdp/pocketrdp-3.30.patch` |
| Android NDK | `29.0.14206865` |
| CMake | `4.1.2` |
| Android command-line tools | `15859902_latest` |
| OpenSSL | `4.0.1` |
| FFmpeg | `n8.1.2` |
| cJSON | `1.7.19` |
| uriparser | `1.0.2` |

The WSL scripts verify the NDK, CMake and command-line-tools archives with SHA-256.
FreeRDP's `cmake/DepVersions.cmake` pins source archive hashes for its native
dependencies. The dormant OpenH264 fallback is pinned to an immutable commit, not a
movable tag.

## Recreate the patched FreeRDP tree

After cloning and initializing submodules, keep the submodule at the official
FreeRDP base and apply the tracked PocketRDP patch:

```powershell
git submodule update --init third_party/FreeRDP
.\scripts\apply-freerdp-patches.ps1
```

Use `-CheckOnly` to validate a clean official base without changing it. The helper
refuses an unexpected FreeRDP commit or an unrelated dirty submodule.
The WSL build driver invokes this helper automatically, so a clean checkout cannot
silently rebuild the unpatched upstream JNI bridge.

## Build all four ABIs

Run from WSL2 Ubuntu at the repository root:

```bash
bash scripts/build-native-multiarch-in-wsl.sh \
  arm64-v8a armeabi-v7a x86 x86_64
```

The script intentionally invokes Gradle once per ABI. OpenSSL and FFmpeg external
projects share source trees and must not build concurrently. It:

1. verifies or applies the exact audited FreeRDP patch;
2. installs or verifies JDK 21, NDK 29, CMake 4.1.2 and Android API 37;
3. verifies and reuses cached dependency archives;
4. pre-stages the verified FFmpeg source;
5. builds one ABI through `-PnativeAbi=<abi>`;
6. stages all eight outputs from that invocation's active CMake configuration plus
   the NDK's `libc++_shared.so`, and refuses an incomplete or mixed set;
7. enforces the expected file count and ELF LOAD alignment; and
8. restores the Windows `local.properties` through an exit trap.

The former single-ABI command remains as a compatibility wrapper around the same
validated multi-ABI driver:

```bash
bash scripts/build-native-in-wsl.sh
```

Do not attempt the native superbuild on Windows. OpenSSL's Perl/configure path model
and FreeRDP's external-project environment are not supported there.

## Shipped native stack

Every ABI directory must contain exactly these nine files:

```text
libfreerdp-android.so
libfreerdp3.so
libfreerdp-client3.so
libwinpr3.so
libssl.so
libcrypto.so
libcjson.so
liburiparser.so
libc++_shared.so
```

H.264 uses Android MediaCodec first. A minimal FFmpeg H.264 decoder and swscale are
linked statically into `libfreerdp3.so` as the fallback; there is no FFmpeg or
OpenH264 shared library in the APK. Unused sensitive redirection clients
(microphone, camera, printer, smart card, USB, serial, parallel, SSH agent and
location) are compiled out.

PocketRDP still enables the channels needed by its public features: graphics,
dynamic display control, RDPEI touch, text clipboard, phone-storage drive
redirection and remote audio playback.

## 16 KB page-size requirement

All 64-bit libraries (`arm64-v8a`, `x86_64`) must report `0x4000` LOAD alignment.
The 32-bit ABIs may legitimately contain a mix of `0x4000` and `0x1000`.

The requirement is enforced in three layers:

- `core-rdp/build.gradle.kts`;
- FreeRDP Android `ExternalDeps.cmake`; and
- FreeRDP Android `ExternalFreeRDP.cmake`.

Always inspect the post-strip libraries from the final APK as well as `jniLibs`;
stale AGP merge outputs can otherwise hide a bad source artifact.

## Windows packaging build

Before invoking Gradle on Windows:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug --no-configuration-cache --console=plain --no-daemon
```

If an interrupted WSL run leaves `local.properties` pointing at `/root/android-sdk`,
restore the preserved `local.properties.windows-backup` before any Windows Gradle
command.
