# ahp-android-client

Native Android client for VS Code agent sessions, built on the open
[Agent Host Protocol](https://github.com/microsoft/agent-host-protocol) (AHP) and the same Dev Tunnel
that backs **Remote Tunnel Access** (the blue **A** in VS Code) and `vscode.dev/agents`.

Phase 1 is a **read-only monitor**; the long-term goal is a near-full replacement for the VS Code
Agents panel. The authoritative plan, acceptance criteria and progress log are tracked separately.

## Modules

| Module | Type | Status |
|---|---|---|
| `core-ahp` | Pure Kotlin/JVM library: GitHub device-flow auth, Dev Tunnels REST, tunnel socket and gateway selection, AHP connection, state stores, capability allowlist | In progress |
| `app` | Android application (Jetpack Compose). Placeholder screen that links `core-ahp`; builds a debug APK | Scaffold only |

## Build

Requires JDK 17+ (set up with JDK 21; no toolchain is pinned) and, for `:app`, an Android SDK with platform
`android-37.0` (the Compose BOM needs `compileSdk` 37; `targetSdk` stays 36) and `build-tools/36.0.0`.
Point Gradle at the SDK with a gitignored `local.properties`:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

```powershell
.\gradlew.bat build              # core-ahp tests + app lint and debug/release checks
.\gradlew.bat :app:assembleDebug # app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :core-ahp:test     # JVM-only, no Android SDK needed
```

The daemon heap is set to 3 GB in `gradle.properties`; the default 512 MB runs out building the app.

## Pinned versions

| Dependency | Version | Note |
|---|---|---|
| `com.microsoft.agenthostprotocol:agent-host-protocol` | 1.0.0 | Offers protocol `1.0.0` and `0.9.0`; the VS Code host negotiated `0.9.0` on 2026-10-21 |
| Kotlin | 2.4.21 | |
| OkHttp | 5.5.1 | HTTP and WebSocket |
| Gradle | 9.8.2 | Wrapper checked in |
| Android Gradle Plugin | 9.4.1 | Built-in Kotlin; only the Compose compiler plugin is applied |
| Compose BOM | 2026.09.00 | Needs `compileSdk` 37 |

## Security

The client never stores or logs a token in clear text, and Phase 1 sends only read-tier AHP methods
(enforced by `CapabilityPolicy`). See the spec's Security section before changing either.
