# ahp-android-client

Native Android client for VS Code agent sessions, built on the open
[Agent Host Protocol](https://github.com/microsoft/agent-host-protocol) (AHP) and the same Dev Tunnel
that backs **Remote Tunnel Access** (the blue **A** in VS Code) and `vscode.dev/agents`.

Phases 1 and 2 are a **read-only monitor** with a read-only chat transcript. Phase 3 adds acting for the user (send a message,
answer a question, approve or deny a tool call, stop the agent), **off by default** and behind an explicit switch; the
long-term goal is a near-full replacement for the VS Code Agents panel. The authoritative plan, acceptance criteria and
progress log live in this repo: [`docs/spec.md`](docs/spec.md).

## Modules

| Module | Type | Status |
|---|---|---|
| `core-ahp` | Pure Kotlin/JVM library: GitHub device-flow auth, Dev Tunnels REST, tunnel socket and gateway selection, AHP connection, state stores (sessions and chats, with older-turn paging), session list and detail models, the transcript model, background-monitoring logic, the capability allowlist, and the write path (action builders, the interactor, the question form model, the consent setting) | Phase 1 complete, live-verified except where `docs/verification/phase1-readonly.md` says otherwise; Phase 2 built; Phase 3 built and tested, not yet live-tested |
| `app` | Android application (Jetpack Compose): sign-in, host list, sessions list, read-only session detail, a chat transcript with markdown and collapsible reasoning, tool-call and result blocks (selectable text, "Show all", subagent transcripts), opt-in background monitoring with a foreground service and needs-input notifications, and, when the user turns it on (Android 11+), a message box with steering, tool and tool-result approval behind the phone's own device check (also from a notification), and a form for the agent's questions with draft sync | Phase 1 built; Phase 2 transcript built; Phase 3 built; debug APK |

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
.\gradlew.bat :core-ahp:test     # JVM-only, no Android SDK needed; also writes the coverage report
.\gradlew.bat :core-ahp:jacocoTestCoverageVerification   # fails below 80% line coverage on auth, tunnel, ahp, state
```

Coverage report: `core-ahp/build/reports/jacoco/test/html/index.html` (and `jacocoTestReport.xml`). `build` runs the
80% gate. The `probe` package is the live command-line probe and is not covered by unit tests.

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
The first instrumented run after a cold boot can also report `Process crashed` with no log; run it again.

The Compose accessibility checks (`AccessibilityTest`) need API 34 or newer and are skipped below that, because the checker
silently does nothing there. A canary test in the same class feeds it a defective screen and fails if the checker stops
catching it.

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
must not be among them), `--chat <title-or-uri>` (subscribe to a session's chat, decode its snapshot and apply every chat
action with the library's reducer, reporting counts and type names only), `--capture <file>` (raw frames as JSON lines). Capture files contain session titles and local paths, so they stay
local: `captures/` is gitignored. The device code lasts 15 minutes; approve it at the URL the probe prints.

## Pinned versions

| Dependency | Version | Note |
|---|---|---|
| `com.microsoft.agenthostprotocol:agent-host-protocol` | 1.0.0 | Offers protocol `1.0.0` and `0.9.0`; the VS Code host chose `1.0.0` in every live run on 2026-10-22 (`0.9.0` on 2026-10-21) |
| Kotlin | 2.4.21 | |
| OkHttp | 5.5.1 | HTTP and WebSocket |
| Gradle | 9.8.2 | Wrapper checked in |
| Android Gradle Plugin | 9.4.1 | Built-in Kotlin; only the Compose compiler plugin is applied |
| Compose BOM | 2026.09.00 | Needs `compileSdk` 37 |
| `com.mikepenz:multiplatform-markdown-renderer-m3` | 0.46.0 | Apache-2.0. Renders agent output. Its JetBrains Compose artifacts resolve to metadata-only redirects onto the AndroidX ones, so there is one Compose runtime. No image loader (Coil) is added on purpose |
| `androidx.biometric:biometric` | 1.1.0 | The phone's own prompt in front of tool approvals. Last stable release; the newer ones are alphas, which a security gate should not be built on |
| `androidx.fragment:fragment` | 1.9.1 | Pinned because biometric 1.1.0 pulls an old Fragment that breaks the ActivityResult API under `FragmentActivity` (lint caught it) |

## Security

The client never stores or logs a token in clear text. Its outbound AHP traffic is controlled by one gate,
`CapabilityPolicy`, with two tiers: `READ` (the default: connect, subscribe, list, page through history) and `INTERACT`
(`READ` plus `dispatchAction` for exactly eight chat action types). See the spec's Security section before changing either.

**Acting for the user is off by default.** The `INTERACT` tier is used only after the user turns on "Let this app act for me"
and confirms a dialog that says what it allows. It exists on Android 11 and newer only: below that the switch is disabled with
a reason, and a saved "yes" is ignored. Turning it off ends the live connection at once and reconnects read-only;
signing out turns it off. A saved choice that cannot be read counts as off, and a turn-on that cannot be saved is not granted.
Everything the user does goes out through `ChatInteractor`, which refuses unless the connection allows it and the host shows
the chat as interactive, and the screen shows nothing as done until the host has echoed the action back (no optimistic
state). A host's refusal is never applied to local state. What it can do: send a message (queued, or steering the running
turn), stop the turn, answer the agent's questions (with what is typed sent to the host as a draft as it is entered), and
approve or deny a tool call or what a tool produced.

**Approving a tool call, or what a tool produced, is what lets the agent run something on the user's machine**, so it alone
is behind the phone's own device check (`BiometricApprovalGate`: strong biometric or screen lock, which is why the write tier
needs Android 11+). The prompt names the tool. It is skipped for deny, refused when no screen of the app is up, and is
checked twice (before the prompt and again after it, in case the call finished meanwhile). It proves a person unlocked the
phone; it does not bind a key to the approval. A build with no gate refuses every approval. Never allowed at any tier without
a new decision: `terminal/input`, file writes, session creation or disposal, and signing in to anything on the user's behalf.

**Approving from a notification.** While monitoring is on, the "needs your input" notification for a session that waits on
exactly one plain tool call (the host offered no options of its own, and the chat is one the session lists) has Deny and
Approve buttons, on its lock-screen version too, and only while acting for the user is on. The buttons carry only which call
to decide; the live chat is what says whether it can still be decided, through the same `ChatInteractor` checks as on screen.
Deny needs no check and works from the lock screen. Approve starts a screenless activity so the device check has somewhere to
appear (after unlocking, on a locked phone), and the prompt names the tool before anything is sent. The activity and the Deny
receiver are not exported and their intents are immutable, so only this app's own notification can reach them. A question,
a tool with options of the host's own, a session waiting on more than one thing, and a tool's output are decided in the app.

When the outcome of an action is unknown (no echo in time, or the connection dropped first) the message stays in the box
with a warning not to send it twice, because resending would duplicate it.

Debug builds also write one logcat line per outbound frame under the tag `AhpWire`: the method name and nothing else
(never parameters, URLs or headers), with `NOT-READ` in front of any method outside the read tier. Release builds log
nothing. To check a run on a device: `adb logcat -d -s AhpWire`, and confirm no line contains `NOT-READ` while acting for the
user is off. With it on, a `dispatchAction` line carries `NOT-READ` by design; the log does not name the action type.

Text in a chat transcript is written by the agent or by whatever it read, so it is treated as untrusted. It is rendered as
markdown but never as HTML; no image loader is installed, so an image link is never fetched; and tapping a link opens a
confirmation that names the real host first. Only `https` links to a named ASCII host without embedded credentials are ever
launched (`SafeLinks.isSafeWebLink`); anything else is explained and refused. Chat fixtures are built by
`tools/redact-chat.mjs`, which masks all free text and refuses to write if anything from the capture survives.
