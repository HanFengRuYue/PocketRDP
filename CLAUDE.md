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

- `compileSdk = 37` / `targetSdk = 37`. **Do NOT switch to `compileSdkPreview` codenames** — preview SDK writes the codename string into the APK's `minSdkVersion`/`targetSdkVersion`, and stable-build Android devices return `packageInfo is null (33)` on install. Always pin to the latest **numeric stable** API level.
- `minSdk = 31` (Android 12). Don't lower without checking — Foreground Service type and Keystore APIs assume 31+.
- `applicationId = "com.hanfengruyue.pocketrdp"` (release) / `com.hanfengruyue.pocketrdp.debug` (debug, suffix added by `applicationIdSuffix`). All Kotlin source under `com.hanfengruyue.pocketrdp.*`. Exception: `com.freerdp.freerdpcore.services.LibFreeRDP` (Java shim, see Hard constraints).
- Kotlin 2.2.21, AGP 8.13.0, **Gradle 8.13**, Hilt 2.55, KSP 2.2.21-2.0.5, Compose BOM 2026.05.01, JVM target 17, NDK 27.1.12297006.
- **KSP1 only** — `ksp.useKSP2=false` in `gradle.properties`. KSP2 historically broke Hilt/Room on Kotlin 2.1.20 ('unexpected jvm signature V'). Even on Kotlin 2.2.21 it remains incompatible with AGP 9's built-in Kotlin — see the AGP-upgrade deadlock in Hard constraints. Don't flip without re-validating both vectors.
- No `kotlin { jvmToolchain(N) }` — use launcher JBR 21 to emit Java 17 bytecode. Each module declares `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`.

## Native (FreeRDP) build

**Active via prebuilt jniLibs.** `core-rdp/src/main/jniLibs/arm64-v8a/` ships 7 .so files compiled in WSL2 Ubuntu (`libfreerdp-android` + `libfreerdp3` + `libfreerdp-client3` + `libwinpr3` + `libssl` + `libcrypto` + `libc++_shared`). `core-rdp/build.gradle.kts` has `externalNativeBuild` commented out — Windows-side gradle just packages the prebuilts, no native toolchain needed.

**To rebuild native** (e.g. after FreeRDP submodule bump): run `scripts/build-native-in-wsl.sh` from WSL2 Ubuntu. Script installs JDK 21 + Linux NDK 27 + cmake via sdkmanager, runs `:core-rdp:externalNativeBuildDebug` (~13 min after deps cached), then copies `.so` back to jniLibs. See `@NATIVE_BUILD_NOTES.md` for the why-not-Windows-native rationale (6 layers of perl/path mismatches in OpenSSL Configure).

**Don't attempt Windows-native rebuild** — every successful patch uncovers a deeper one, abandoned at OpenSSL Configure's out-of-tree path resolution.

## Hard constraints — don't break these

- **Don't upgrade past AGP 8.13.0 / Hilt 2.55** — AGP 9.x triggers a 3-way deadlock (verified 2026-05): (1) KSP refuses AGP's built-in Kotlin (`KSP is not compatible with AGP built-in Kotlin`, both KSP1 and KSP2 modes); (2) disabling built-in Kotlin (`android.builtInKotlin=false`) + applying `org.jetbrains.kotlin.android` plugin explicitly fails on `BaseExtension cannot be cast` (AGP 9 rewrote the DSL, KGP 2.2.x still references the old base type); (3) Hilt 2.56+ hard-requires AGP 9.0+. Until KSP/Hilt/AGP reconcile, hold AGP 8.13.0 + Hilt 2.55 + Gradle 8.13. Compose BOM 2026.05.01 and Kotlin 2.2.21 *do* work on AGP 8.13.
- **`com.freerdp.freerdpcore.services.LibFreeRDP` class FQN is locked** by `third_party/FreeRDP/.../android_freerdp_jni.h:JAVA_LIBFREERDP_CLASS`. Don't rename or move the file. The shim at `core-rdp/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java` deliberately keeps this path while dropping upstream's BookmarkBase/SessionState/ApplicationSettingsActivity deps.
- **Do NOT re-add `:freeRDPCore` to `settings.gradle.kts`**. Already attempted in commit history — pulls in androidx.appcompat / room 2.8.4 / sqlcipher / preference / recyclerview which conflict with our Compose + Room 2.6.1 stack. The current strategy (compile cpp + minimal LibFreeRDP.java in `:core-rdp`) is deliberate.
- **FreeRDP submodule is pinned at master commit `9b04e3b`** (FreeRDP 3.x). Don't `git submodule update --remote` without verifying the CMake superbuild still works — upstream's `client/Android/cmake/External*.cmake` files have known Windows portability bugs (Unix `:` path separators) that we'd have to re-patch.

## Common commands

- Debug build: `./gradlew.bat :app:assembleDebug --no-configuration-cache --console=plain --no-daemon` — produces `app/build/outputs/apk/debug/app-debug.apk` (~90 MB, signed with the release keystore via `keystore.properties`; size dominated by bundled FreeRDP+OpenSSL .so).
- Release build: `./gradlew.bat :app:assembleRelease --no-configuration-cache --console=plain --no-daemon` — produces `app/build/outputs/apk/release/app-release.apk` (R8-minified Kotlin, v3-signed; native .so are not shrunk).
- Full clean: `./gradlew.bat clean` (do NOT delete `~/.gradle/caches` — Compose BOM / Hilt artifacts will redownload from Aliyun and take minutes).
- Configuration-cache off (`--no-configuration-cache`) is recommended while iterating on `build.gradle.kts` — it caches aggressively and stale entries cause silent build skips.

## App signing

- Both debug and release builds are signed with the same PocketRDP keystore (kept out of git via `.gitignore`). Config lives in `keystore.properties` (also gitignored); see `keystore.properties.template` for the schema. The .jks file is in `keystore/pocketrdp-release.jks`.
- If `keystore.properties` is missing (fresh clone), `app/build.gradle.kts` falls back to the Android debug signing config so builds still work — but the resulting APK won't match the stable signing identity. Regenerate the keystore with `keytool -genkeypair -keyalg RSA -keysize 2048 -validity 36500 ...` and update the properties file.

## Credentials & encryption

- Connection passwords are encrypted with `core-data/security/CredentialCipher.kt` using Android Keystore alias `pocketrdp_master_v1` (AES/GCM/NoPadding, 256-bit, no user auth required). Plain passwords never hit DB.
- M6 in the original plan was "add encryption" — already done in M1, so skip that milestone label. Plain `password` column does NOT exist; use `passwordCipher: ByteArray` + `passwordIv: ByteArray`.

## What's missing (intentionally, not gaps to fill silently)

No unit tests, no instrumented tests, no CI workflow. detekt (`detekt.yml` + `detekt-baseline.xml`) and `.editorconfig` are in place. Don't add the missing pieces without explicit ask — keep the project minimal until M3+ stabilizes the input/render layer.

## Milestone status

- **M1** (commit `0990e75`): app skeleton + Compose UI + Room + Keystore-encrypted credentials — done.
- **M2** (commits `b5cf13b` → `0e08b16` + uncommitted WSL native): Kotlin/UI layer for the session done, plus FreeRDP arm64-v8a .so built in WSL2 and staged in `core-rdp/src/main/jniLibs/arm64-v8a/`. Real RDP connections work.
- **M3+** not started: input control polish, dynamic resolution, clipboard/file redirection, Foreground Service for multi-session — see plan file referenced above.
