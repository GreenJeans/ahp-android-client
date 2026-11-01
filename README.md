# ahp-android-client

Native Android client for VS Code agent sessions, built on the open
[Agent Host Protocol](https://github.com/microsoft/agent-host-protocol) (AHP) and the same Dev Tunnel
that backs **Remote Tunnel Access** (the blue **A** in VS Code) and `vscode.dev/agents`.

Phases 1 and 2 are a **read-only monitor** with a read-only chat transcript. Phase 3 adds acting for the user (send a message,
answer a question, approve or deny a tool call, stop the agent), **off by default** and behind an explicit switch. Phase 4 adds
looking after sessions (mark read, archive, new chat, new session, remove), behind a second switch that is also off by default,
and Phase 5 adds a session's changed files (the list, a line-by-line diff of each file, and a "Viewed" box on the manage tier); the long-term goal is a near-full replacement for the VS Code Agents panel. The authoritative plan, acceptance criteria and
progress log live in this repo: [`docs/spec.md`](docs/spec.md) and [`docs/prerelease-monetization-spec.md`](docs/prerelease-monetization-spec.md).

## Modules

| Module | Type | Status |
|---|---|---|
| `core-ahp` | Pure Kotlin/JVM library: GitHub device-flow auth, Dev Tunnels REST, tunnel socket and gateway selection, direct WebSocket host connector (`ahpd`), AHP connection, state stores (sessions and chats, with older-turn paging), host extension readers (`HostExtras`: session git branch/counts, pull requests and model; tool-call progress, timing, MCP server and subagent metadata; hidden-from-transcript message flags), session list and detail models, the transcript and tool-detail models, background-monitoring logic, the capability allowlist, `ProEntitlement`, and the write path (action builders, the interactor, the question form model, the consent settings), the session manager, the changeset view model, the line diff and the model options | Phase 1 complete, live-verified except where `docs/verification/phase1-readonly.md` says otherwise; Phase 2 built; Phase 3 built and live-tested; Phases 4 and 5 built and tested, not yet live-tested; `ProEntitlement` built and tested |
| `app` | Android application (Jetpack Compose): sign-in, a side menu (host, new session, the sessions with search and each one's changes, settings), a hosts sheet (switch host, add one by address), a session screen (a tab for each chat, the changes in the bar, a menu, a Details sheet), a chat transcript in the manner of the chat apps (what you said and each thing the agent wrote as bubbles, each run of its steps folded to one line that opens, a marker for when the agent has stopped, pinch to resize the text) with markdown and collapsible reasoning, tool-call and result blocks (selectable text, "Show all", subagent transcripts), opt-in background monitoring with a foreground service, needs-input notifications, and "finished" notifications for the sessions you pick with the bell in a session's menu (or all of them, in Settings), and, when the user turns it on (Android 11+), a message box with steering, tool and tool-result approval behind the phone's own device check (also from a notification), and a form for the agent's questions with draft sync; with a second switch, mark read, archive, new chat, new session and remove; and a session's changed files (a line diff of each, and a "Viewed" box), a model picker for messages, the chat's mode (Interactive, Plan, Autopilot) and approvals (Manual, Assisted, Allow all) beside its custom agent, with the phone's own check before giving the agent more freedom, an inbox of the sessions that wait on you and the ones that finished unread, and the settings the host offers for a new session | Phases 1–5 built; design pass 4 done; R8-shrunk release APK (`:app:assembleRelease`) and debug APK |

## Hosts added by address (ahpd)

Besides the VS Code hosts that have Remote Tunnel Access on, the hosts sheet takes **a host added by its address**, such as [`ahpd`](https://github.com/softov/ahpd), which listens on a plain WebSocket and wants a connection token. No GitHub sign-in is needed for it: the sign-in screen has "Connect to a host by address", and with hosts added the app opens on the host list.

- **Adding one:** the hosts sheet, "Add a host by address": the address (`192.168.1.20:9187`; the port is 9187 when left out), an optional name, and the connection token. The token is sent as `Authorization: Bearer` and kept encrypted in its own vault (its own Keystore key, so signing out of GitHub never removes it); it is never in the address, in preferences, in a log or in a backup. Removing the host removes the token, its permissions and the saved session list.
- **Which addresses:** `wss://` to anything; `ws://` (not encrypted) only to this phone or a private network address: `localhost`, 127/8, 10/8, 172.16/12, 192.168/16, 100.64/10 (mesh VPNs), IPv6 loopback and unique-local. The emulator reaches the laptop as `10.0.2.2`. Cleartext is allowed in the manifest because a network-security configuration cannot name address ranges, so the rule is enforced in code (`HostAddress`) when a host is added and on every connection.
- **Permissions are per host.** A new host starts read only; "Let this app act for me" and "look after sessions" are set while connected to it, apply to it alone, and Settings names which host they are for.
- **Running `ahpd` for a phone:** it binds to loopback by default, which a phone cannot reach. Start it with `ahpd --host 0.0.0.0 --connection-token-file <file>` (or reach it over a mesh VPN address) and give the app that token. `ahpd` leaves `description` out of an agent that has none; the app reads that as empty (`HostQuirks`).
- **Live check:** `$env:AHPD_TOKEN = '<token>'; .\gradlew.bat -q --console=plain :core-ahp:probe --args="--direct ws://127.0.0.1:9187"` connects with the production connector at the read tier and checks the methods on the wire. Results are in [`docs/verification/extensions.md`](docs/verification/extensions.md).

## Look and feel

Material 3 with the system's dynamic colour (Android 12+; a fixed scheme below that), in the manner of Obtainium: a large app
bar that collapses as a list scrolls, content held to 720 dp, rounded stacked cards (24 dp outer corners, 6 dp between, a 3 dp gap)
instead of ruled lists, tonal banners, pill fields and buttons, an extended button for the main action, and a bottom sheet for
detail. The shared pieces are in `ui/Design.kt` (`ScreenScaffold`, `TileCard`, `connectedShape`, `SectionHeader`, `EmptyState`) and
`ui/Icons.kt`, which draws the handful of icons used from path data, so the app takes no icons library. Where things are:

| Screen | What is on it |
|---|---|
| Main page | The side menu is the home page: with no session open it is open and cannot be closed, over an empty background; with nothing connected it offers Choose a host, and a first start with no last host opens the hosts sheet. A session opens over it and a hamburger in its bar brings the menu back. Back closes the session (the menu opens), then leaves the app (it goes to the background and does not disconnect) |
| Side menu | The host (its name and connection, a button that opens the hosts sheet), one row of icon buttons under it (Inbox with its count, an Archived toggle, New session only when managing is on, and Settings; each with a spoken name and a tooltip), and the sessions (needs-you first, a summary line, search; each row has a state mark, a short age and its changes, `(+1072, −8)`) |
| Hosts sheet | A sheet over the side menu, refreshed when it opens: the connected host(s) with Disconnect, every other host as one list to connect or switch to with a tap (tunnel hosts online, hosts added by address, tunnel hosts offline; "last used" and "not encrypted" marked), Add a host by address, Sign in with GitHub when not signed in (which opens a device-code dialog in place when already connected to a direct host), and a Manage hosts link to Settings. A failed tunnel list says so and leaves the hosts added by address |
| Settings | Hosts (the GitHub sign-in, with Sign out, which asks first, or Sign in with GitHub; it says the sign-in renews itself, since GitHub user tokens last eight hours and the app trades the refresh token for a new pair before one runs out, and saves it first, or names the hour a sign-in that cannot be renewed ends; and the hosts you added by address, each with its address and a remove button that asks first, and Add a host by address; choosing a host is the sheet's), Display (text size with Reset, theme System/Light/Dark, keep the screen on while an agent works), Quick replies (Off / Auto / On demand, with Onboard Gemini Nano via AICore or a BYOK cloud provider: Google Gemini, OpenAI, Anthropic, or OpenAI-compatible custom endpoint with encrypted API key storage and live model discovery), Background (monitoring), Permissions (the two switches), each with the words that say what it does |
| A session | A bar with the menu button, the title with its state and project, the changes `(+N, −M)` (a button to the files when there is one set to open), and the session menu; a tab for each chat (the main chat first, a dot on one waiting on you; a chat you have looked at stays subscribed, up to three besides the one on screen, so going back to its tab is instant), the chat, and in the menu: Details, then (only when managing is on) mark read, archive, new chat, remove this chat, remove session |
| Details | A bottom sheet: what the session is doing, what it waits on, any pull requests the host reports (with link confirmation for `github.com` addresses), and where it runs (agent, model, project, folder, branch, git status). The changes moved to the bar |
| A chat | As in the Agents panel, Claude and ChatGPT: what you said is a bubble on the right (a screen reader says "You"; synthetic or system prompts flagged hidden by the host are omitted), each thing the agent wrote is a bubble across the width (so one message is told from the next, and a long answer is plain to see), and its work between them is one card per run of steps: folded it reads "Used 4 tools" with the tool names under it, "Working…" with the newest running tool's progress message (or tool name) while one runs, "Waiting on you" (open, and not foldable) while one waits for approval; it opens to the steps, each of which opens in turn (with completed duration and MCP server metadata carried on the model). Notices sit centred between two rules. A turn ends with a line, "Done · 1m 4s", "Stopped" or "Failed", so it is plain when the agent has stopped; while it works, three pulsing dots and a thin bar run, and while it waits for you they stop and say "Waiting for you". Links in the agent's text open only if a phone can use them: https pages (in a Custom Tab, in the phone's default browser; the dialog also has Share, which opens the system share sheet with the address), mailto and tel; paths on the host's disk and other schemes show as plain text. Two fingers resize the text (it is kept in Settings). The message box is one rounded card: the text, then on the left the custom agent (its name, or "Default agent"; it opens a list to change it, and a message keeps the one the chat is on unless you pick another) and the model (the one in use: the one you picked, else the one the last turn or draft used, or the session's model from `SessionExtras.model`; "Default model" only when none is reported) and the buttons on the right, an arrow to send (it queues while the agent works), a square to stop it, and "Steer now" once there is text to steer with. A round arrow brings you back to the latest message |
| A long chat | The host's snapshot of a chat is asked for with its last 10 turns only, after the connection is up (older turns load by the button or by scrolling). **The host ignores that ask** (live, 2026-10-25: a chat came back whole, 9.8 million characters, in about 13 s), as the protocol allows, so a very long chat cannot be made smaller from the phone. If a chat has not arrived after 30 s (or the connection drops three times while it loads) the chat says so with a "Try again" button instead of reconnecting for ever, the host is told to stop building it, and the rest of the session keeps working. A chat that is taking a while says "This chat is long, so it can take a while." after 6 seconds. The longest chat tried (this one, with screenshots in its history) never arrived: the host did not answer in time, and the connection was closed after 30 s by the keep-alive or, with the keep-alive off, after exactly 100 s by the other end |

## Build

Requires JDK 17+ (set up with JDK 21; no toolchain is pinned) and, for `:app`, an Android SDK with platform
`android-37.0` (the Compose BOM needs `compileSdk` 37; `targetSdk` stays 36) and `build-tools/36.0.0`.
Point Gradle at the SDK with a gitignored `local.properties`:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

```powershell
.\gradlew.bat build                # core-ahp tests + app lint and debug/release checks
.\gradlew.bat :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:assembleRelease # app/build/outputs/apk/release/app-release-unsigned.apk (R8-shrunk)
.\gradlew.bat :core-ahp:test       # JVM-only, no Android SDK needed; also writes the coverage report
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

Options: `--direct <ws-or-wss-url>` (with token in `AHPD_TOKEN`), `--tunnel <name|id>`, `--instance <id>`, `--ping-seconds <n>` (default 30; `0` disables WebSocket pings and logs the elapsed idle duration and close code/reason when the relay closes the socket), `--watch <seconds>` (print notifications for a while),
`--session <title-or-uri>` (subscribe to one session and print which clients the host lists as active; this client
must not be among them), `--chat <title-or-uri>` (subscribe to a session's chat, decode its snapshot and apply every chat
action with the library's reducer, reporting counts and type names only), `--census` (with `--census-sessions <n>`, default 10, and `--census-out <file>`: subscribe to the newest sessions and the main chat of each, watch for `--watch` seconds, default 10, and report per kind of frame every path under `_meta` and every other path the generated types drop; paths, JSON types and counts only, never a value, and a key that looks like a title or an address is shown as `<key>`), `--capture <file>` (raw frames as JSON lines). Capture files contain session titles and local paths, so they stay
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
`CapabilityPolicy`, with three tiers: `READ` (the default: connect, subscribe, list, page through history, and read the content of a changed file), `INTERACT`
(`READ` plus `dispatchAction` for exactly eight chat action types), and `MANAGE` (`INTERACT` plus four commands, one query and three more
action types). See the spec's Security section before changing any of them.

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
a new decision: `terminal/input`, file writes, and signing in to anything on the user's behalf.

**Looking after sessions is a second switch**, "Let this app look after sessions", also off by default, with its own dialog. It
is only ever on while acting for the user is on, is cleared with it (on screen and on disk), is ignored on load without it, and
is saved before it counts; turning it on or off reconnects under the new tier. It adds `session/isReadChanged`,
`session/isArchivedChanged`, `session/titleChanged` and `changeset/filesReviewChanged` (mark read, rename session and mark a file viewed need no check; archiving asks first, because this host may remove the session's working copy with it, but needs no device check) and five commands: `createChat` (only in a session
the host reports as ready, no check), `invokeChangesetOperation` (for changeset-scoped operations the host lists; operations with a confirmation prompt ask first and go through the device check, re-checked after the prompt), and `createSession`, `disposeSession` and `disposeChat`, which **do** go through the same
device check, looked up again after the prompt. Removing asks first and offers archiving as the gentler alternative. A new session
can only use an agent the host lists and either no folder (nothing is sent for it and the host chooses where the agent works, which the device check says; it is the only choice on a host with no sessions yet) or a folder one of the host's own sessions already uses (most recent first, eight shown and the rest one tap away, never a working copy of a project: its project folder is offered instead), so nothing the host did not offer is
sent. None of the controls exist on a connection that is not on the `MANAGE` tier. A session's main chat (and a lone chat) has no "Remove this chat…" menu item, because the host refuses it, but only after it has shut the chat down. The three switches (monitoring, acting,
managing) are on a Settings screen, behind the Settings button in the side menu's action row.

**A new session's settings** come from the host: once an agent and folder are chosen, the dialog asks it (`resolveSessionConfig`,
the one query on the manage tier; the read and interact tiers refuse it) which settings it offers and shows each as a short list
(a list of text values, or on/off), asking again after every choice. Free text, numbers, read-only settings and "complete as you
type" settings are not shown, only counted; the host keeps its own value for them. With choices made, Start first asks the host
once more: every choice must be an option it lists now and be echoed back as the value it holds, a required setting needs a value,
and what is sent is the host's own resolved values with the choices in them. The device check then names the chosen settings
("Thinking: Thorough"), settings first and counted if they do not all fit, so what is confirmed is what is sent. With no choices,
nothing extra is asked or sent. The app cannot know what a setting means (one could make the agent ask less before acting), so it
shows the host's own words, repeats them at the device check, and keeps the whole start behind the manage switch.

**Changed files** open as a list of paths with added, modified or deleted, line counts and the reviewed flag (at most 500 rows).
Tapping a file reads its two sides with `resourceRead` (the one read of file content, allowed at every tier) and shows a line
diff worked out on the phone. The app asks only for the two content addresses the open changeset's own file entry names, and the
method that reads is internal to the core module. It is bounded (256 KiB a side by the host's own hint, 5,000 lines a side,
1,000 changed lines, 2,000 lines shown, 500 characters a line), shows no file that has a NUL character or bytes that are not
UTF-8, swaps characters that reorder text on screen for a visible mark, and shows everything as plain text. A "Viewed" box per
file appears only when the host says the changeset is reviewable and the connection is on the manage tier; changeset-scoped operations the host lists (`operations`) also appear as buttons on the manage tier.

**Terminals** are watched, never typed into. A tool result that ran a command in a terminal gets an "Open live output" button, which
shows that terminal's output as it arrives: plain text, the last 2,000 lines, with a Latest button when you scroll away. The pinned
library mis-decodes a terminal snapshot as a session (pinned by `LibraryGapsTest`), so `AhpConnection.subscribeTerminal` reads that one
reply by hand and gives only the terminal state to the library. For the same reason a terminal is never named in `initialize` or
`reconnect` (their replies would carry the snapshot and fail to decode as a whole): it is subscribed afresh on every connection. It is
still `subscribe`, a read-tier method, and `terminal/input` stays refused at every tier. Only an address that a tool result in the open
chat names is opened, and it is checked again as a terminal's (a real one is `agenthost-terminal://shell/...`). The output is shown as plain text
only: escape sequences are dropped, a carriage return or backspace overwrites as a terminal would, other control characters are dropped,
characters that reorder text are swapped for a visible mark, and only the end of a very long output (256 KiB) is read. Cursor movement
is not replayed, so a program that redraws several lines in place shows what it printed in order.

**Choosing a model** is done per message, from the models the host lists for the chat's agent (a Model button in the message
box). It rides on the message actions the interact tier already allows and adds no method. Only a model, and settings, that are
exactly among the options the host listed are sent, and a pick the host stops offering is dropped instead of sent.

**Approving from a notification.** While monitoring is on, the "needs your input" notification for a session that waits on
exactly one tool call in a chat the session lists has buttons: a plain Deny and Approve when the host offered no options of its own,
or else the host's own options in its own words (at most three, the refusal first), on its lock-screen version too, and only while acting for the user is on. The buttons carry only which call and option
to decide; the live chat is what says whether it can still be decided, through the same `ChatInteractor` checks as on screen.
Deny needs no check and works from the lock screen. Approve starts a screenless activity so the device check has somewhere to
appear (after unlocking, on a locked phone), and the prompt names the tool before anything is sent. The activity and the Deny
receiver are not exported and their intents are immutable, so only this app's own notification can reach them. When a session waits on a single free-text question in a listed chat (with acting for the user on), the notification gets an **Answer** button with inline reply (`RemoteInput`, `AnswerReceiver`), which checks the live chat and form bounds through `ChatInteractor` without a biometric prompt and updates the notification to "Sent" (or the refusal reason) until the host's echo clears it; multi-field or choice questions,
a session waiting on more than one thing (its notification says how many, and names the first, with no buttons), and a tool's output are decided in the app. An option is only sent if the host offers it now and means what the button says, and the device check names it ("Your choice: Allow in this session"). A Quick Settings tile (`MonitorTileService`) shows how many sessions wait on you while monitoring runs ("2 waiting", "Connected", or "Monitoring off") and opens the inbox when tapped.

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
