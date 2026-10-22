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

## Emulator and device tests

The token vault's tests need a real Android Keystore, so they run on a device or emulator (`app/src/androidTest`). Install
the emulator and a system image once, create a virtual device, start it, then run the tests:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root=$sdk "emulator" "system-images;android-36;google_apis;x86_64"
& "$sdk\emulator\emulator.exe" -accel-check                      # expect: WHPX ... is installed and usable
& "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd --name ahp-api36 --package "system-images;android-36;google_apis;x86_64" --device pixel_8
& "$sdk\emulator\emulator.exe" -avd ahp-api36 -no-boot-anim -gpu swiftshader_indirect -no-window   # leave running
.\tools\run-device-tests.ps1 -Serial emulator-5554                 # one named emulator; refuses a physical phone
```

`-no-window -gpu swiftshader_indirect` is the stable way to start it here: with the windowed host-GPU default the emulator crashed
on start several times (exit `0xC0000005`, a stuck crash dialog) after earlier instances were stopped with `adb emu kill`. If it
does, stop the leftover `emulator` and `qemu-system` processes by id and delete the AVD's stale `*.lock` files first.

Do not use `.\gradlew.bat :app:connectedDebugAndroidTest` while a real phone is attached: it runs on every attached device
and uninstalls the app afterwards, which wipes the phone's sign-in. `tools\run-device-tests.ps1` installs on the one serial
you name, runs the instrumentation there and leaves the app installed.

Results land in `app/build/outputs/androidTest-results/connected/debug/` (the root element is `testsuites`).
JUnit 4 rejects test methods that return a value, so instrumented tests use the `runTest { }` helper, not `= runBlocking { }`.

## Live probe

Signs in with GitHub device flow, finds your Remote Tunnel Access host, connects through the tunnel's public
URL, and prints `initialize` and `listSessions`. It sends only READ-tier calls and checks that on the wire.
You need VS Code running with Remote Tunnel Access on, signed in with the same GitHub account.

```powershell
.\gradlew.bat -q --console=plain :core-ahp:probe --args="--watch 45 --capture captures/live-session.jsonl"
```

Options: `--tunnel <name|id>`, `--instance <id>`, `--watch <seconds>` (print notifications for a while),
`--session <title-or-uri>` (subscribe to one session and print which clients the host lists as active; this client
must not be among them), `--capture <file>` (raw frames as JSON lines). Capture files contain session titles and local paths, so they stay
local: `captures/` is gitignored. The device code lasts 15 minutes; approve it at the URL the probe prints.

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
