# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

PocketRDP — Android RDP client. Kotlin + Jetpack Compose Material 3 + Hilt + Room. Native RDP protocol via FreeRDP (git submodule at `third_party/FreeRDP`).

Module layout: `:app` / `:feature-session` / `:feature-connections` / `:core-rdp` / `:core-data` / `:core-ui` / `:core-logging`. Plan & decisions are in `C:\Users\huang\.claude\plans\pocketrdp-rpd-rpd-giggly-parnas.md`.

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

**Active via prebuilt jniLibs.** `core-rdp/src/main/jniLibs/arm64-v8a/` ships 9 .so files compiled in WSL2 Ubuntu: `libfreerdp-android` + `libfreerdp3` + `libfreerdp-client3` + `libwinpr3` + `libssl` + `libcrypto` + `libcjson` + `liburiparser` + `libc++_shared`. `core-rdp/build.gradle.kts` has `externalNativeBuild` commented out — Windows-side gradle just packages the prebuilts, no native toolchain needed. **`libcjson.so` and `liburiparser.so` are mandatory** — they are `DT_NEEDED` of `libwinpr3.so`; without them `dlopen(libfreerdp-android.so)` fails on the recursive dependency resolve. They must also stay in `packaging { jniLibs { pickFirsts } }` so AGP doesn't drop them at merge time.

**Prebuilt .so are minimal feature set**: `WITH_OPENH264=OFF`, `WITH_OPUS=OFF`, `WITH_FFMPEG=OFF`, `WITH_WEBP=OFF`, `WITH_JPEG=OFF`, `WITH_PNG=OFF`. `LibFreeRDP.hasH264Support()` returns false — RDP CLI args that depend on those codecs must be guarded in `RdpClient.kt` before being passed to `freerdp_parse_arguments`. Already done for `/gfx:AVC444`. `/scale` is quantised to the nearest of {100, 140, 180} in `buildCommandLine()` because FreeRDP 3.x `parse_scale_options` rejects anything else; do not relax this without also handling `COMMAND_LINE_ERROR_UNEXPECTED_VALUE`. When `parse_arguments` fails the snackbar reads "freerdp_parse_arguments failed: Success" because `last_error_code` is only populated during `freerdp_connect`, not during argument parsing — look at the redacted `Connecting with args:` logcat line instead.

**16 KB page-size is mandatory** for Android 15+ (SDK 35+) devices and the `sdk_gphone16k_*` emulators — dlopen rejects any .so whose LOAD segments are 4 KB-aligned with `program alignment (4096) cannot be smaller than system page size (16384)`. NDK 27.1 only injects `-Wl,-z,max-page-size=16384` when `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` is passed to cmake, AND ExternalProject children don't inherit init linker flags so each child needs the flag explicitly forwarded. The fix lives in three places and **must** stay synced when re-enabling native build:
1. `core-rdp/build.gradle.kts`: cmake `arguments` block (currently commented inside the disabled native-build region — uncomment alongside the rest).
2. `third_party/FreeRDP/client/Android/Studio/freeRDPCore/src/main/cpp/CMakeLists.txt`: `ANDROID_CMAKE_ARGS` forwards the flags to cjson/uriparser/openssl ExternalProjects (local patch on top of the pinned submodule).
3. `third_party/FreeRDP/client/Android/cmake/ExternalFreeRDP.cmake`: `FREERDP_LINKER_FLAGS` includes `-Wl,-z,max-page-size=16384` because that line explicitly overrides `CMAKE_SHARED_LINKER_FLAGS` for the freerdp3/freerdp-client3/winpr3 child build.

Verify with `llvm-readelf -l <so> | grep LOAD` — every .so's LOAD segments must show `0x4000`, not `0x1000`. (cjson/uriparser produce 16 KB-aligned binaries in their *own* build dirs but the merged_native_libs step can be re-poisoned by stale 4 KB copies in jniLibs/, so always re-verify the files actually under `core-rdp/src/main/jniLibs/arm64-v8a/`.)

**To rebuild native** (e.g. after FreeRDP submodule bump): run `scripts/build-native-in-wsl.sh` from WSL2 Ubuntu. Script installs JDK 21 + Linux NDK 27 + cmake via sdkmanager, runs `:core-rdp:externalNativeBuildDebug` (~13 min after deps cached), then copies `.so` back to jniLibs. See `@NATIVE_BUILD_NOTES.md` for the why-not-Windows-native rationale (6 layers of perl/path mismatches in OpenSSL Configure).

While that script runs it backs `local.properties` up to `local.properties.windows-backup` (gitignored) and rewrites `local.properties` to point at the Linux SDK; restores at exit. If `local.properties.windows-backup` shows up in `git status` after an interrupted run, **don't delete it** — copy it back over `local.properties` to recover the Windows SDK path.

**Don't attempt Windows-native rebuild** — every successful patch uncovers a deeper one, abandoned at OpenSSL Configure's out-of-tree path resolution.

## Hard constraints — don't break these

- **Don't upgrade past AGP 8.13.0 / Hilt 2.55** — AGP 9.x triggers a 3-way deadlock (verified 2026-05): (1) KSP refuses AGP's built-in Kotlin (`KSP is not compatible with AGP built-in Kotlin`, both KSP1 and KSP2 modes); (2) disabling built-in Kotlin (`android.builtInKotlin=false`) + applying `org.jetbrains.kotlin.android` plugin explicitly fails on `BaseExtension cannot be cast` (AGP 9 rewrote the DSL, KGP 2.2.x still references the old base type); (3) Hilt 2.56+ hard-requires AGP 9.0+. Until KSP/Hilt/AGP reconcile, hold AGP 8.13.0 + Hilt 2.55 + Gradle 8.13. Compose BOM 2026.05.01 and Kotlin 2.2.21 *do* work on AGP 8.13.
- **`com.freerdp.freerdpcore.services.LibFreeRDP` class FQN is locked** by `third_party/FreeRDP/.../android_freerdp_jni.h:JAVA_LIBFREERDP_CLASS`. Don't rename or move the file. The shim at `core-rdp/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java` deliberately keeps this path while dropping upstream's BookmarkBase/SessionState/ApplicationSettingsActivity deps.
- **Do NOT re-add `:freeRDPCore` to `settings.gradle.kts`**. Already attempted in commit history — pulls in androidx.appcompat / room 2.8.4 / sqlcipher / preference / recyclerview which conflict with our Compose + Room 2.6.1 stack. The current strategy (compile cpp + minimal LibFreeRDP.java in `:core-rdp`) is deliberate.
- **FreeRDP submodule is pinned at master commit `9b04e3b`** (FreeRDP 3.x). Don't `git submodule update --remote` without verifying the CMake superbuild still works — upstream's `client/Android/cmake/External*.cmake` files have known Windows portability bugs (Unix `:` path separators) that we'd have to re-patch.
- **`app/proguard-rules.pro` must keep `com.freerdp.freerdpcore.**`** — the FreeRDP JNI bridge calls Java callbacks (`OnConnectionSuccess`, `OnGraphicsUpdate`, `OnAuthenticate`, …) via reflection. Without the keep rule, R8 strips them and release builds crash on first connect with `NoSuchMethodError`. The Hilt and Room keep rules in that file are also load-bearing — don't trim "unused" entries.

## Rendering & input — load-bearing invariants

- `feature-session/.../render/RdpSurface.kt` is a **plain `View`**, NOT a `SurfaceView`. Earlier SurfaceView + Choreographer + lockCanvas iteration was abandoned — Compose AndroidView wrap + cross-thread schedule from native worker had too many race modes. Current strategy: `onDraw` re-schedules itself via `scheduleNextFrame()` as long as the view is attached — either VSync-paced (`postInvalidateOnAnimation`, when the connection's `targetFrameRate == 0` / "auto") or throttled to a fixed fps via `postInvalidateDelayed` (target clamped to the device screen refresh rate). This self-loop must NEVER stop while attached — it is the load-bearing guard against the "connected but black screen" bug; throttling only DELAYS the next frame, never cancels it. `markDirty()` is consequently a **no-op** (it must NOT `postInvalidate` — a fast content-update rate would otherwise bypass the throttle and unfix the frame rate); a pixel change shows up on the next scheduled frame because `drawBitmap` reads the bitmap's live pixels every frame. Don't reintroduce SurfaceView without a hard reason — it'll bring back "connected but black screen" bugs.
- `core-rdp/.../BitmapBuffer.kt` MUST NOT `recycle()` the old bitmap in `resize()` or `release()`. Native FreeRDP worker thread calls resize; UI thread's `RdpSurface.onDraw` may still hold the old reference for 1–2 VSync frames until Compose's update lambda swaps it. Calling recycle → `drawBitmap()` hits freed pixel storage → SIGSEGV, no logcat survives. Let GC reclaim — 8 MB transient duplication is fine.
- `feature-session/.../input/ScancodeMap.kt` — VK constants for extended keys (LWIN/RWIN/APPS/arrows/INSERT/DELETE/HOME/END/PgUp/PgDn/RCTRL/RALT) MUST be pre-ORed with `KBDEXT = 0x0100`. FreeRDP's `GetVirtualScanCodeFromVirtualKeyCode` uses the KBDEXT bit to pick `KBD4T` vs `KBD4X` — tables don't overlap. Without KBDEXT on an extended-only VK, reverse-lookup returns VK_NONE → scancode 0 → server gets nothing. Also: never pass PS/2 scancodes to `sendKeyEvent` directly — the JNI runs `GetVirtualScanCodeFromVirtualKeyCode(arg, 4)` internally and will double-translate them into garbage.
- `RdpClient.sendMonitorLayout(w, h)` returns `Boolean` and **callers must respect it**. FreeRDP's DRDYNVC Display Control sub-channel comes up several seconds AFTER `OnConnectionSuccess` — sending before that returns `false` and the PDU is silently dropped. `SessionViewModel.scheduleMonitorLayoutRetry()` runs an exponential backoff (0/0.5/1/2/3/5/5/5/5 s) and stops only when a matching `OnGraphicsResize` arrives (±8 px tolerance for server-side clamping). Don't replace this with a single-shot send.
- `feature-session/.../input/SessionGestures.kt` — the `awaitEachGesture` block MUST wrap the gesture loop in `try { ... } finally { if (doubleTapDragHeld) controller.endDragHold(); if (touchDragInFlight) controller.releaseTouchDrag() }`. If a finger leaves the view mid-drag we never see the up event, and without the finally the remote stays with BUTTON1 held down forever — the desktop becomes unusable until reconnect. Also: `RdpInputController.toggleMode/setMode` defensively call `endDragHold` first so switching modes mid-drag doesn't leak a held button; don't remove that guard.
- `feature-session/.../input/SessionImeBridge.kt` — IME input goes through a **hidden** `BasicTextField` (NOT `BasicTextField2`) anchored by a U+200B zero-width-space sentinel. Most Android IMEs (especially Chinese pinyin) call `InputConnection.commitText()` instead of emitting `KEYCODE_*` events, so the only way to capture them is to host a real text-editing widget. The sentinel keeps the buffer non-empty so backspace behaviour is predictable across vendors; never remove it. `onValueChange` diffs against the sentinel and forwards the fresh substring to `onUnicodeText` → `SessionViewModel.typeText` → `TextInputEncoder` (per-char routing, see next bullet); physical-key paths (Backspace/Enter/arrows/F-keys/modifiers) go through `onPreviewKeyEvent → ScancodeMap.vkFor → sendKeyEvent`. Both paths must coexist — pure-VK would lose Chinese; pure-Unicode would lose Ctrl+A.
- `feature-session/.../input/TextInputEncoder.kt` — committed IME text MUST NOT be blasted out as raw unicode key events. **Sending a unicode keyboard event to a server that did not negotiate `INPUT_FLAG_UNICODE` is fatal to the whole session**: `freerdp_input_send_unicode_keyboard_event` returns FALSE, and `android_event.c:android_process_event` treats *any* input-send failure as grounds to break out of the main connection loop — so the first character typed instantly disconnects (field log: `OnDisconnecting` ~6 ms after the first `sendUnicodeKey`). The encoder routes **printable ASCII → scancode/VK path** (`ScancodeMap.asciiVkFor`, US-QWERTY, Shift-wrapped) which has no unicode dependency, and falls back to unicode only for non-ASCII (CJK/emoji) **gated on `RdpClient.isUnicodeInputSupported()`** — dropping the char (throttled log) rather than disconnecting when the server lacks it. Don't revert `typeText` (in both `SessionViewModel` and `RdpInputController`) to a bare `sendUnicodeKey` loop. Defence-in-depth: `android_event.c` was also patched so the KEY/KEY_UNICODE/CURSOR cases log a warning and force `rc = TRUE` instead of tearing the session down — but that only takes effect after a WSL native rebuild; the Kotlin-side routing is what fixes it on the current prebuilt `.so`.
- `feature-session/.../SessionScreen.kt` — `Modifier.sessionGestures(...)` lives on the **outer Box**, the `graphicsLayer { scaleX = userZoom; translationX = userPan.x; ... }` lives on the **inner `AndroidView`**. PointerInput sits ABOVE graphicsLayer in the modifier chain, so Compose delivers pointer coordinates pre-transform; `RdpInputController.toRemote` deliberately only undoes the fit-to-view transform, not the user zoom. If you ever move pointerInput beneath graphicsLayer in the modifier chain, you must undo the zoom/pan in `toRemote` too — otherwise touches fall hundreds of pixels off after a pinch.
- `SessionViewModel.kt` — FPS and connection duration are written into UI state by a 1 Hz `startMetricsTicker` coroutine, NOT per frame (writing `_state` 30–60×/s would recompose the TopAppBar that often and visibly hitch the framebuffer). The frame counter is ticked from `RdpSurface.onFrameRendered` (one *render* frame, via `SessionViewModel.onFrameRendered`), NOT from the `RdpEvent.GraphicsUpdated` collector — so `fps` reflects the steady render / configured target rate and no longer drops to 0 when the remote screen is idle. The ticker is the sole writer of `fps` / `durationSec` into UI state. Same pattern applies if any other high-frequency metric is added later.

## Common commands

- Debug build: `./gradlew.bat :app:assembleDebug --no-configuration-cache --console=plain --no-daemon` — produces `app/build/outputs/apk/debug/app-debug.apk` (~90 MB, signed with the release keystore via `keystore.properties`; size dominated by bundled FreeRDP+OpenSSL .so).
- Release build: `./gradlew.bat :app:assembleRelease --no-configuration-cache --console=plain --no-daemon` — produces `app/build/outputs/apk/release/app-release.apk` (R8-minified Kotlin, v3-signed; native .so are not shrunk).
- Full clean: `./gradlew.bat clean` (do NOT delete `~/.gradle/caches` — Compose BOM / Hilt artifacts will redownload from Aliyun and take minutes).
- Configuration-cache off (`--no-configuration-cache`) is recommended while iterating on `build.gradle.kts` — it caches aggressively and stale entries cause silent build skips.
- To pull runtime logs without ADB: open the app → main screen TopBar → log icon → share. File lands as `pocketrdp-YYYY-MM-DD.log` via FileProvider, can be sent over QQ/WeChat/email/etc.

## App signing

- Both debug and release builds are signed with the same PocketRDP keystore (kept out of git via `.gitignore`). Config lives in `keystore.properties` (also gitignored); see `keystore.properties.template` for the schema. The .jks file is in `keystore/pocketrdp-release.jks`.
- If `keystore.properties` is missing (fresh clone), `app/build.gradle.kts` falls back to the Android debug signing config so builds still work — but the resulting APK won't match the stable signing identity. Regenerate the keystore with `keytool -genkeypair -keyalg RSA -keysize 2048 -validity 36500 ...` and update the properties file.

## Credentials & encryption

- Connection passwords are encrypted with `core-data/security/CredentialCipher.kt` using Android Keystore alias `pocketrdp_master_v1` (AES/GCM/NoPadding, 256-bit, no user auth required). Plain passwords never hit DB.
- M6 in the original plan was "add encryption" — already done in M1, so skip that milestone label. Plain `password` column does NOT exist; use `passwordCipher: ByteArray` + `passwordIv: ByteArray`.

## Diagnostics & in-app logging

- `:core-logging` provides `PocketLogger` (Kotlin object singleton, no Hilt). Installed in `PocketRdpApplication.onCreate`. Three sinks: (1) `Log.d/i/w/e` mirror so `adb logcat -s PocketRDP` works; (2) 1000-entry in-memory ring exposed as `StateFlow<List<Entry>>` for `LogScreen` to subscribe; (3) async channel → `<filesDir>/logs/pocketrdp.log` with 1.5 MB rotate + `.1` backup. The channel decouples native FreeRDP worker thread from file I/O — DO NOT make the writer synchronous, you'll stall the protocol thread.
- In-app log viewer: `app/src/main/kotlin/.../logs/LogScreen.kt`, navigated to via the icon in `ConnectionListScreen`'s TopAppBar. Filter chips, monospace + colour-by-level, share via `FileProvider` (authority `${applicationId}.fileprovider`, paths in `app/src/main/res/xml/file_provider_paths.xml`, exports `cacheDir/logs/`).
- When users report a bug, ask them to share the log file rather than guess. Crashes that kill the process before the async writer flushes will still lose in-flight entries — stabilise the crash first (e.g. the BitmapBuffer recycle case above), then ask for logs.

## What's missing (intentionally, not gaps to fill silently)

No unit tests, no instrumented tests, no CI workflow. detekt (`detekt.yml` + `detekt-baseline.xml`) and `.editorconfig` are in place. Don't add the missing pieces without explicit ask — keep the project minimal until M3+ stabilizes the input/render layer.

detekt is configured with `maxIssues: 0` plus a baseline — any new finding fails the build. The check task is `./gradlew detekt` (there is **no** `detektMain` task — this is one root `detekt {}` over an explicit `source.setFrom` list, not per-module). To accept legitimate new findings (e.g. after a refactor): `./gradlew detektBaseline` regenerates `detekt-baseline.xml`. Prefer that over scattering `@Suppress` annotations.

## Milestone status

- **M1** (commit `0990e75`): app skeleton + Compose UI + Room + Keystore-encrypted credentials — done.
- **M2** (commits `b5cf13b` → `0e08b16` + uncommitted WSL native): Kotlin/UI layer for the session done, plus FreeRDP arm64-v8a .so built in WSL2 and staged in `core-rdp/src/main/jniLibs/arm64-v8a/`. Real RDP connections work.
- **M3** (uncommitted, on working tree): full text input (IME bridge with Unicode + VK dual paths), immersive full-screen toggle with TopAppBar/BottomAppBar `AnimatedVisibility` + exit FAB, multi-finger gestures (1=left / 2=right / 3=middle clicks, 2-finger scroll, pinch-to-zoom, double-tap-then-drag with held BUTTON1), local pinch-zoom via `graphicsLayer` (1×–4×), status badges (state dot + remote resolution + duration + FPS + mode) with expandable detail menu — on top of the earlier-uncommitted on-screen toolbar keys, trackpad cursor overlay, letterbox-compensated touch coords, dynamic-resolution retry-until-acknowledged, and in-app log viewer + FileProvider export. Still TODO post-M3: clipboard text bridge, file redirection, Foreground Service for multi-session.
