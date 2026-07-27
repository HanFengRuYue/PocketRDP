# PocketRDP Agent Guide

This is the repository's only agent-facing guide. Merge any still-valid guidance here and remove duplicate guides.

## Project and audited toolchain

PocketRDP is an Android RDP client built with Kotlin, Jetpack Compose Material 3, Hilt, Room, and a patched FreeRDP native core. Modules are `:app`, `:feature-session`, `:feature-connections`, `:core-rdp`, `:core-data`, `:core-ui`, and `:core-logging`.

The audited stack is AGP 9.3.1, Gradle 9.6.1, Kotlin Compose 2.4.10, KSP 2.3.10, Hilt 2.60.1, Room 2.8.4, Compose BOM 2026.06.01, compile/target SDK 37, min SDK 31, NDK 29.0.14206865, and CMake 4.1.2. AGP built-in Kotlin is active; do not apply `org.jetbrains.kotlin.android`, disable built-in Kotlin, switch to KSP1, or add `jvmToolchain`. Keep JVM target 17.

On Windows, every Gradle invocation must use Android Studio JBR 21:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Preserve the Tencent Gradle wrapper mirror and Aliyun Maven mirrors until this network constraint is explicitly revalidated.

## Native FreeRDP boundary

FreeRDP is based on official 3.30.0 commit `6b107f0aadbabc47941c5a5b893b88c01792af6d` and is integrated through PocketRDP fork commit `4ff131db41ef8159b9b234be23f625c98a4821ba`. PocketRDP changes are also stored reproducibly in `patches/freerdp/pocketrdp-3.30.patch`; verify either the clean integrated commit or the patch applied to the official base with `scripts/apply-freerdp-patches.ps1 -CheckOnly`. Do not alter the `com.freerdp.freerdpcore.services.LibFreeRDP` FQN. Keep the R8 rules for `com.freerdp.freerdpcore.**`, Hilt, and Room.

Normal Windows builds package prebuilt libraries from `core-rdp/src/main/jniLibs`. Native rebuilds are WSL2-only. Run `scripts/build-native-multiarch-in-wsl.sh` and explicitly pass all four ABIs when changing JNI or FreeRDP core behavior:

```bash
ABIS="arm64-v8a armeabi-v7a x86 x86_64" bash scripts/build-native-multiarch-in-wsl.sh
```

Do not build ABIs concurrently because OpenSSL and FFmpeg ExternalProjects share source trees. Each ABI must contain exactly nine libraries: `libfreerdp-android`, `libfreerdp3`, `libfreerdp-client3`, `libwinpr3`, `libssl`, `libcrypto`, `libcjson`, `liburiparser`, and `libc++_shared`. The 64-bit ABIs must have 16 KiB LOAD alignment. Validate the libraries extracted from the final APK as well as the source `jniLibs` copies.

MediaCodec is the primary H.264 decoder and statically linked FFmpeg is the fallback. Keep `WITH_MEDIACODEC=ON`, `WITH_FFMPEG=ON`, `WITH_OPENH264=OFF`. `/gfx:AVC444` is quality-first and `/gfx:AVC420` is latency-first; never combine an AVC mode with RFX. Desktop scaling uses `/scale-desktop:<100..300>`, not `/scale`.

## RDP-UDP invariants

UDP is per-connection opt-in and defaults off for both new and stored connections. Disabled connections must emit `-multitransport`; FreeRDP boolean options do not accept `/option:off`.

The native multitransport implementation supports RDP-UDP v1/v2, UDP2 v3, reliable UDP-R or UDP2, optional lossy UDP-L, TLS/DTLS, RDPEMT creation, Soft-Sync, and static VC-over-DVC tunneling. Use the established TCP peer's IP, address family, and port for UDP so same-port TCP/UDP FRP mappings work. Do not add RD Gateway UDP or modify FRP configuration.

All UDP sockets, timers, protocol state, and inbound channel delivery belong to the FreeRDP main event loop through `rdp_get_event_handles` and `rdp_check_fds`. Never add a UDP reader thread or call `freerdp_channels_data` from a transport worker. Keep hard limits on frames, queues, windows, reassembly, and retransmission. The UDP handshake must not block the established TCP desktop.

Keep reliable and lossy tunnel slots independent. UDP2 occupies the reliable slot; a later UDP-L request must not replace it. Before Soft-Sync, all DVC traffic stays on TCP and early UDP data remains in a bounded queue. Parse Soft-Sync lists strictly: reject duplicate IDs, unknown tunnels, missing channels, invalid lengths, and static wrappers assigned to UDP-L. Send the response on TCP and activate routes only after its write-completion barrier. Only listed DVC data PDUs move to UDP; DVC control, create, close, and Soft-Sync PDUs remain on TCP. Static VC wrappers use the reliable tunnel and restore static channel framing flags after bounded reassembly.

Before Soft-Sync, a failed tunnel receives `E_ABORT` while TCP and any other successful tunnel remain usable. After activation, never silently reroute migrated DVC data to TCP. Close all UDP tunnels, clear multitransport capability in that FreeRDP instance, and use the Auto-Reconnect Cookie to reconnect TCP-only. Allow this UDP-to-TCP downgrade once per session; app-level retries for that session remain TCP-only. A manually started new session may read the stored UDP preference again.

JNI transport state is one atomic `getTransportSnapshot(long): long[36]` call. Mask values are UDP-R `1`, UDP-L `2`, and UDP2 `4`. Kotlin must treat unknown versions or invalid lengths as safe TCP/unknown state. UI labels UDP active only after Soft-Sync and must not log hosts, cookies, certificate contents, or credentials.

## Rendering, input, and session invariants

`RdpSurface` remains a plain `View`, not `SurfaceView`. Its attached draw loop never stops: auto mode uses `postInvalidateOnAnimation`, fixed FPS uses delayed invalidation, and `markDirty` remains a no-op. Do not publish per-frame bitmaps through Compose state.

`BitmapBuffer` is double-buffered. Native rendering writes the back buffer, reapplies the stale rectangle, and commits a complete front frame. Never call `Bitmap.recycle()` during resize or release because RenderThread may still hold it.

Each retained `SessionViewModel` owns an unscoped `RdpClient`, native instance, and buffer. Do not make `RdpClient` a singleton. The singleton session registry drives one aggregate foreground notification. Home previews remain low-frequency snapshots from active memory buffers with saved JPEG fallback.

Keep extended virtual keys ORed with `KBDEXT`; JNI expects virtual-key codes, not raw PS/2 scancodes. Monitor layout calls are retried with backoff until a matching graphics resize arrives. Trackpad and RDPEI gesture branches release held buttons or contacts in `finally`. Picture zoom and pan are controlled only by their dedicated UI controls. Gesture dispatch reads the live controller mode, not a captured Compose value.

IME input uses the hidden sentinel-backed `BasicTextField`. Printable ASCII uses scancode/VK routing and non-ASCII uses Unicode only when enabled. Preserve the native post-connect Unicode force-enable and the defensive behavior that input-send failures do not tear down the session.

## Validation and delivery

For relevant changes run unit tests, Detekt, Lint, patch consistency checks, and `git diff --check`. Native transport changes additionally require deterministic wire/parser tests under ASan/UBSan, explicit four-ABI WSL rebuild, dependency and LOAD-alignment checks, and inspection of the ELF files inside the APK.

Release delivery is a signed, minified `app-release.apk`. Verify its SHA-256, signature, ZIP alignment, ABI inventory, ELF dependency closure, JNI callback retention, and 16 KiB alignment. Build success is not Windows/FRP interoperability proof. Until the user supplies a Windows 11 capture and application logs, report exactly that automation and builds passed while real Windows interoperability remains pending field confirmation.
