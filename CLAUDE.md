# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

PocketRDP — Android RDP client. Kotlin + Jetpack Compose Material 3 + Hilt + Room. Native RDP protocol via FreeRDP (git submodule at `third_party/FreeRDP`).

Module layout: `:app` / `:feature-session` / `:feature-connections` / `:core-rdp` / `:core-data` / `:core-ui`. Plan & decisions are in `C:\Users\huang\.claude\plans\pocketrdp-rpd-rpd-giggly-parnas.md`.

## Build environment (Windows-specific)

- **JDK must be Android Studio's JBR 21**, not the system JDK (Oracle JDK 26 in PATH breaks AGP). Always set before any Gradle invocation:
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
  ```
- Gradle wrapper distribution comes from **mirrors.cloud.tencent.com**, not services.gradle.org. Do not "fix" `gradle/wrapper/gradle-wrapper.properties` back to the upstream URL — services.gradle.org is unreachable from this network.
- Maven repos in `settings.gradle.kts` use **Aliyun mirrors** as primary. Same reason. Don't remove them.

## Versions & toolchain (non-obvious)

- `compileSdkPreview = "CinnamonBun"` (API 37.1-beta3) — **not** `compileSdk = 36`. Requires `platforms;android-CinnamonBun-ext23` (already installed). `targetSdkPreview = "CinnamonBun"` only in `:app`.
- `minSdk = 31` (Android 12). Don't lower without checking — Foreground Service type and Keystore APIs assume 31+.
- Kotlin 2.1.20, AGP 8.13.0, Compose BOM 2025.02.00, JVM target 17, NDK 27.1.12297006.
- **KSP1 only** — `ksp.useKSP2=false` in `gradle.properties`. KSP2 hits `unexpected jvm signature V` with Hilt/Room on Kotlin 2.1.20. Don't flip it.
- No `kotlin { jvmToolchain(N) }` — use launcher JBR 21 to emit Java 17 bytecode. Each module declares `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`.

## Native (FreeRDP) build status

**Native build is intentionally disabled.** `core-rdp/build.gradle.kts` has the `externalNativeBuild`, `ndk`, and `packaging.jniLibs` blocks commented out. `LibFreeRDP.java` falls back to `nativeReady = false` and the app runs in UI-stub mode (clicking Connect shows a friendly "native FreeRDP not built" error). **This is the current intended state on Windows.**

When working on RDP wire/protocol code, the .so libs need to be built first. See `@NATIVE_BUILD_NOTES.md` for the four Windows-specific blockers and the fix path. Linux/WSL2 builds work out of the box — recommend WSL2 for any native iteration.

## Hard constraints — don't break these

- **`com.freerdp.freerdpcore.services.LibFreeRDP` class FQN is locked** by `third_party/FreeRDP/.../android_freerdp_jni.h:JAVA_LIBFREERDP_CLASS`. Don't rename or move the file. The shim at `core-rdp/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java` deliberately keeps this path while dropping upstream's BookmarkBase/SessionState/ApplicationSettingsActivity deps.
- **Do NOT re-add `:freeRDPCore` to `settings.gradle.kts`**. Already attempted in commit history — pulls in androidx.appcompat / room 2.8.4 / sqlcipher / preference / recyclerview which conflict with our Compose + Room 2.6.1 stack. The current strategy (compile cpp + minimal LibFreeRDP.java in `:core-rdp`) is deliberate.
- **FreeRDP submodule is pinned at master commit `9b04e3b`** (FreeRDP 3.x). Don't `git submodule update --remote` without verifying the CMake superbuild still works — upstream's `client/Android/cmake/External*.cmake` files have known Windows portability bugs (Unix `:` path separators) that we'd have to re-patch.

## Common commands

- Debug build: `./gradlew.bat :app:assembleDebug --no-configuration-cache --console=plain --no-daemon` — produces `app/build/outputs/apk/debug/app-debug.apk` (~59 MB).
- Full clean: `./gradlew.bat clean` (do NOT delete `~/.gradle/caches` — Compose BOM / Hilt artifacts will redownload from Aliyun and take minutes).
- Configuration-cache off (`--no-configuration-cache`) is recommended while iterating on `build.gradle.kts` — it caches aggressively and stale entries cause silent build skips.

## Credentials & encryption

- Connection passwords are encrypted with `core-data/security/CredentialCipher.kt` using Android Keystore alias `pocketrdp_master_v1` (AES/GCM/NoPadding, 256-bit, no user auth required). Plain passwords never hit DB.
- M6 in the original plan was "add encryption" — already done in M1, so skip that milestone label. Plain `password` column does NOT exist; use `passwordCipher: ByteArray` + `passwordIv: ByteArray`.

## What's missing (intentionally, not gaps to fill silently)

No unit tests, no instrumented tests, no CI workflow, no ktlint/detekt config, no `.editorconfig`. Don't add scaffolding for these without explicit ask — keep the project minimal until M3+ stabilizes the input/render layer.

## Milestone status

- **M1** (commit `0990e75`): app skeleton + Compose UI + Room + Keystore-encrypted credentials — done.
- **M2** (commits `b5cf13b` → `0e08b16`): Kotlin/UI layer for the session (RdpClient, BitmapBuffer, RdpSurface, RdpInputController, SessionScreen, toolbar) — done. Native .so wiring deferred.
- **M3+** not started: input control polish, dynamic resolution, clipboard/file redirection, Foreground Service for multi-session — see plan file referenced above.
