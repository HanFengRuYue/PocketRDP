# Native (FreeRDP) Build Notes

The Kotlin/UI side of PocketRDP is fully built. To produce a session that actually
exchanges RDP wire bytes, the FreeRDP native libraries (`libfreerdp-android.so`,
`libfreerdp3.so`, `libfreerdp-client3.so`, `libwinpr3.so`) must be compiled via
the CMake superbuild in `third_party/FreeRDP`. This was attempted on Windows but
hit several Windows-host-specific issues. The notes below capture exactly what
was set up so it can be finished in 30–60 min next time (or done on Linux/WSL/CI).

## What's already in place on this machine

| Component | Path |
|---|---|
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` |
| Platforms | `platforms;android-CinnamonBun-ext23` (API 37.1-beta3) + `android-36.1` |
| NDK | `ndk\27.1.12297006` (fully installed; ~2.2 GB) |
| CMake | `cmake\3.22.1` |
| cmdline-tools | `cmdline-tools\latest` |
| pkg-config | `%LOCALAPPDATA%\pkg-config\pkg-config-lite-0.28-1\bin\pkg-config.exe` (0.28) |
| FreeRDP source | `third_party/FreeRDP` (git submodule, master / 3.x) |
| Pre-downloaded deps | OpenSSL 3.6.2 (52 MB) + OpenH264 v2.6.0 (57 MB) + cJSON v1.7.19 + uriparser 1.0.0 were already in `core-rdp/.cxx/Debug/<hash>/arm64-v8a/<pkg>-prefix/src/` before .cxx was last cleaned |
| `make.exe` | NDK 27 ships one at `ndk\27.1.12297006\prebuilt\windows-x86_64\bin\make.exe` |
| `perl` 5.42.2 | Git for Windows at `C:\Program Files\Git\usr\bin\perl.exe` — **missing `Locale::Maketext::Simple`**, so OpenSSL's Configure fails |

## What blocks `:core-rdp:externalNativeBuildDebug` on Windows

1. **`Locale::Maketext::Simple` not in Git's perl.** OpenSSL `Configure` (a perl
   script) requires it. Fix: install Strawberry Perl portable
   (https://strawberryperl.com) and put its `perl\bin` on PATH **before** the
   Git one. Verify with `perl -MLocale::Maketext::Simple -e 1` (no output = OK).
2. **`-E env "PATH=...:..."` uses Unix path separator.** Upstream FreeRDP's
   `client/Android/cmake/External*.cmake` files set `PATH=${NDK_TOOLCHAIN_BIN}:$ENV{PATH}`
   with `:`. On Windows shells split this at `:` and the call fails with
   `拒绝访问`. Fix: in each of `ExternalOpenSSL.cmake`, `ExternalOpenH264.cmake`,
   `ExternalFFmpeg.cmake`, replace the `"PATH=...:$ENV{PATH}"` argument with
   nothing (drop it — child process inherits parent PATH) OR change `:` to `;`.
3. **GitHub Releases tarball downloads are flaky from current network.** Already
   demonstrated: OpenSSL 52 MB pulls fine via `curl -L --retry 10` (24 MB/s here)
   but CMake's `file(DOWNLOAD)` has no retry and aborts on first stall. Fix:
   pre-stage tarballs into `.cxx/<hash>/<abi>/<pkg>-prefix/src/<file>.tar.gz`
   with the exact filename CMake expects — CMake will verify SHA256 and skip the
   download step.
4. **OpenH264 uses GNU Make + perl + nasm.** The NDK 27 `make.exe` works, but
   the OpenH264 makefile path was also hit by the `:` PATH separator bug. For
   M2 verification we disabled OpenH264 (`-DWITH_OPENH264=OFF`) — FreeRDP falls
   back to RFX / NSCodec, which still works with Windows 10/11 hosts though
   without H.264/AVC 444 hardware acceleration. Re-enable later.

## Recommended completion path

**A. Local Windows finish (~1-2 hours):**
1. Install Strawberry Perl Portable, prepend `<strawberry>\perl\bin` to User PATH.
2. Apply the `:` → `;` (or "drop PATH=") patches to FreeRDP's `client/Android/cmake/External*.cmake`.
3. Uncomment the `externalNativeBuild` block in `core-rdp/build.gradle.kts`.
4. Pre-stage `openssl-3.6.2.tar.gz` and `v1.7.19.tar.gz` (cjson) and
   `uriparser-1.0.0.tar.gz` to the matching `.cxx/<hash>/arm64-v8a/<pkg>-prefix/src/`.
5. `./gradlew :core-rdp:externalNativeBuildDebug`.

**B. WSL2 / Linux (~30 min, no Windows tooling issues):**
- `apt install build-essential pkg-config perl nasm` then run the same Gradle
  command — Linux has all of perl/make/pkg-config/path-sep correct out of the
  box. This is the upstream "happy path".

**C. GitHub Actions Ubuntu runner:**
- Build `.so` files on CI, drop them into
  `core-rdp/src/main/jniLibs/arm64-v8a/` as prebuilt artefacts, then the Gradle
  build doesn't need any native toolchain at all on dev machines.

## Environment variables (already set in User PATH)

- `%LOCALAPPDATA%\Android\Sdk\ndk\27.1.12297006\prebuilt\windows-x86_64\bin` (make.exe)
- `%LOCALAPPDATA%\pkg-config\pkg-config-lite-0.28-1\bin` (pkg-config 0.28)
- `C:\Program Files\Git\usr\bin` (perl 5.42.2 — needs Strawberry replacement for OpenSSL)
