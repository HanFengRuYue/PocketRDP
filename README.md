# PocketRDP

PocketRDP is an Android RDP client built with Kotlin, Jetpack Compose, Room,
Hilt, and FreeRDP.

## Project Layout

### Android app modules

| Path | Purpose |
| --- | --- |
| `app/` | Android application entry point, navigation shell, settings, app resources, and release packaging rules. |
| `feature-connections/` | Connection list and connection edit screens. |
| `feature-session/` | Live RDP session UI, gestures, IME bridge, session service, and status UI. |
| `core-data/` | Room database, repositories, preferences, credential encryption, and thumbnail storage. |
| `core-rdp/` | FreeRDP JNI bridge, RDP client wrapper, rendering buffer, and prebuilt native `.so` libraries for the four Android ABIs. |
| `core-ui/` | Shared Compose theme and UI styling. |
| `core-logging/` | Shared logging helper. |

### Build and dependency infrastructure

| Path | Purpose |
| --- | --- |
| `gradle/` | Gradle wrapper and version catalog. Keep this tracked. |
| `scripts/` | Native build helper scripts, mainly for WSL2 FreeRDP/FFmpeg/OpenSSL builds. |
| `third_party/` | Git submodules for external source dependencies. Currently this contains the pinned `FreeRDP` submodule used by the native Android bridge. Do not move it without updating `.gitmodules`, native build scripts, and CMake/Gradle paths. |

### Project assets and docs

| Path | Purpose |
| --- | --- |
| `artwork/` | Source artwork for the app logo and launcher assets. |
| `AGENTS.md` | Codex/agent working guide for this repository. |
| `NATIVE_BUILD_NOTES.md` | Notes about the native FreeRDP build strategy. |
| `detekt.yml`, `detekt-baseline.xml` | Static-analysis configuration and baseline. |

### Local-only files

These files or directories are intentionally ignored and must not be committed:

| Path | Purpose |
| --- | --- |
| `local.properties` | Local Android SDK path. |
| `local.properties.windows-backup` | Temporary backup made by the WSL native build script. |
| `keystore.properties` | Local release-signing configuration. |
| `keystore/` | Local signing keys such as `.jks` files. |
| `.gradle/`, `.kotlin/`, `build/`, `**/build/`, `.cxx/` | Generated Gradle, Kotlin, Android, and native build outputs. |
| `.agents/`, `.codex/`, `.codex-remote-attachments/` | Local Codex runtime state and uploaded attachment cache. |
