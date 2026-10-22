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
| `app` | Android application (Jetpack Compose) | Not started. Needs an Android SDK; added to `settings.gradle.kts` when one is available |

## Build

Requires JDK 17+ (the build was set up with JDK 21). No toolchain is pinned.

```powershell
.\gradlew.bat build
```

## Pinned versions

| Dependency | Version | Note |
|---|---|---|
| `com.microsoft.agenthostprotocol:agent-host-protocol` | 1.0.0 | Offers protocol `1.0.0` and `0.9.0`; the VS Code host negotiated `0.9.0` on 2026-10-21 |
| Kotlin | 2.4.21 | |
| OkHttp | 5.5.1 | HTTP and WebSocket |
| Gradle | 9.8.2 | Wrapper checked in |

## Security

The client never stores or logs a token in clear text, and Phase 1 sends only read-tier AHP methods
(enforced by `CapabilityPolicy`). See the spec's Security section before changing either.
