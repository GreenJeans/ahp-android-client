# Hardening: battery, network, memory and recovery

> **Status:** In progress. H1 and H2 are built and covered by JVM tests; their on-phone checks (AC-H1.5, AC-H1.6, AC-H2.6) are still to do. H3 to H8, F1 and F2 are not started.
> **Date:** 2026-10-26
> **Parent:** [`docs/spec.md`](spec.md). That spec holds the product phases; this one holds the work that makes the app behave well over a long day in a pocket. Tick boxes here as work lands and add a row to the Progress Log at the bottom; note the commit in the row.
> **Source:** a review of the code at commit `a8bb8ed` (concurrency, state, persistence, battery), 2026-10-26.

## Overview

The foundations are sound: one `AppState` value owned by `AppController`, reference-counted subscriptions in `HostSession`, resume with `reconnect`, epochs that throw away a connection made under old permissions, a single-flight token renewal that cannot be cancelled halfway, nothing applied before the host echoes it, and the token sealed with the Keystore and kept out of backups.

What is weak is how the app behaves in the background over hours: it retries blind with no network, keeps whole chat transcripts streaming to a phone nobody is looking at, buffers incoming frames with no limit, and does not come back after Android kills it. This spec fixes those, in an order that takes the biggest battery and data costs first, and adds three features that build on the result.

### Goals

- No connection attempts while the phone has no usable network; reconnect within a second of one returning or changing.
- In the background, only what alerts and the inbox need is subscribed.
- A slow consumer or a huge burst of frames cannot run the app out of memory.
- After Android kills the process, monitoring either comes back by itself or says plainly that it stopped.
- A day of monitoring on Android 15 and newer is not cut off at six hours.

### Non-goals

- Push through a relay server (needs a backend holding tokens; out of scope by the parent spec).
- Caching transcripts on the phone.
- Several hosts at once (deferred by decision, parent spec open question 6).
- Changing what any capability tier allows. Nothing here sends a new kind of action to the host.

### Order of work

| # | Item | Theme | Size | Depends on |
|---|---|---|---|---|
| H1 | Network-aware reconnect, with jitter | Battery, responsiveness | M | — |
| H2 | Release chats and channels in the background | Battery, data | M | — |
| H3 | Ping interval from a measurement | Battery | S | — |
| H4 | Bounded frame and notification buffers | Memory | S | — |
| H5 | Close the `connect()` race | Concurrency | S | — |
| H6 | Come back after process death | Recovery | L | H1 (uses `WaitingForNetwork`) |
| H7 | Encrypted cache of the session list | Cold start | M | H6 (shares the cold-start path) |
| H8 | Foreground service without the six-hour cut | Battery policy | S | — |
| F1 | Answer a question from the notification | Feature | M | H6 (works when the process was dead) |
| F2 | Quick Settings tile | Feature | S | H6 |

H1, H2, H4 and H5 touch different files and can be done in any order or in parallel sessions. H6 and H7 share `AppController`'s cold-start path; do them one after the other.

---

## H1 — Network-aware reconnect, with jitter

### Problem

`HostSession.supervise()` ([HostSession.kt](../core-ahp/src/main/kotlin/dev/ahpclient/state/HostSession.kt)) retries forever with `Backoff` (1 s doubling to 30 s, no jitter) and knows nothing of the network. Offline, each attempt wakes the phone, asks the Dev Tunnels service for a connect token and fails on DNS. When the phone moves between Wi-Fi and mobile data the socket on the old network dies silently, and nothing notices until OkHttp's 30 s ping goes unanswered: up to about a minute of showing "Online" while deaf.

### Design

**core-ahp**

- New `state/NetworkState.kt`:
  ```kotlin
  sealed interface NetworkState {
      data object Unavailable : NetworkState
      /** [id] changes whenever the default network does (Wi-Fi to mobile, a new Wi-Fi). */
      data class Available(val id: String) : NetworkState
  }
  ```
- A companion `NetworkState.ALWAYS_AVAILABLE` (`Available("default")`, never changes) is for callers with no monitor.
- `HostSession` takes `network: StateFlow<NetworkState> = NetworkState.ALWAYS_AVAILABLE` so the probe and existing tests are unchanged.
- New `ConnState.WaitingForNetwork`. It is not a failure and does not count as an attempt.
- In `supervise()`:
  - Before each connect, if the network is `Unavailable`, set `WaitingForNetwork` and suspend until it is `Available`. Reset `attempt` to 0 when it returns.
  - The network is read just before each connect (`usedNetwork`). A failure that ends with the network `Unavailable` goes back to the top of the loop and waits, uncounted. A failure that ends with a different network than `usedNetwork` belongs to the old one: it is retried at once with `attempt` back at 0.
  - Replace the bare `delay(backoff.delayMs(attempt))` with a race between the delay and any change of `network` (`withTimeoutOrNull(delay) { network.first { it != now } }`). Whichever comes first ends the wait; a change also starts the schedule again from attempt 0.
  - While `Online`, `routeWhileOnNetwork` runs a child coroutine beside the notification collector. When `network` stops being `usedNetwork` (a new id, or `Unavailable`), it closes the current connection the way `restart()` does but **without** bumping `epoch` (the tier has not changed, so the next connect resumes with `reconnect`).
- `Backoff` takes `jitter: Double = 0.2` and `random: Random = Random.Default` and returns `delay × (1 ± jitter)`, still capped at `maxMs`. A `jitter` of 0 gives the plain schedule.
- `MonitorStatus.from` ([InputNeeded.kt](../core-ahp/src/main/kotlin/dev/ahpclient/state/InputNeeded.kt)) shows "Waiting for network" for the new state.
- The `when`s over `ConnState` that are exhaustive, and so gained the case, are in `InputNeeded.kt` and `ui/Components.kt`. The debug log in `AppContainer.kt` has an `else` and prints the state's class name; `AppController.kt` and the drawer test for single states only.

**app**

- `AndroidNetworkMonitor(context)` wraps `ConnectivityManager.registerDefaultNetworkCallback` and holds the result in a `StateFlow`. It takes no scope. `AppContainer` creates it lazily, once, when the first session is built, and passes `network.state` to every `HostSession`; it lives as long as the process and is never unregistered.
- `Available(network.toString())` when the default network has `NET_CAPABILITY_INTERNET`; `Unavailable` when that network is lost (a loss of some other network is ignored) or when the default network no longer has `NET_CAPABILITY_INTERNET`.
- **It does not require `NET_CAPABILITY_VALIDATED`, on purpose.** A network that blocks Android's connectivity probe is never validated, and the session would sit in `WaitingForNetwork` on a network that reaches the host. The cost: a Wi-Fi with no real internet (a captive portal) counts as `Available`, so it shows `Reconnecting` with backoff rather than `WaitingForNetwork`. Anything that needs "no route at all" (H6 included) must not assume a no-internet Wi-Fi is `Unavailable`.
- It fails open: if the callback cannot be registered, the state is `Available("unknown")` and the session retries as it did before.
- Manifest: add `android.permission.ACCESS_NETWORK_STATE`.
- The connection banner and the drawer both go through `describe()` in `ui/Components.kt`, which says "Waiting for network…" rather than a retry count.

### Acceptance criteria

- [x] **AC-H1.1** With the network `Unavailable`, `HostSession` makes no `connect` calls, and the state is `WaitingForNetwork`.
- [x] **AC-H1.2** When the network comes back, the first connect starts within 1 s, whatever backoff was pending.
- [x] **AC-H1.3** A change of network id while `Online` closes the connection and the next one uses `reconnect` (resume), not `initialize`.
- [x] **AC-H1.4** Backoff delays stay within ±20 % of the base schedule and never exceed `maxMs`.
- [ ] **AC-H1.5** On the phone: airplane mode on for 5 minutes with monitoring running gives no `AhpConn` "reconnecting" lines while it is on, and the notification says "Waiting for network". Turning it off reaches Online without waiting out a backoff.
- [ ] **AC-H1.6** On the phone: walking off Wi-Fi onto mobile data reconnects within a few seconds, not after a ping timeout.

### Tests

- `HostSessionNetworkTest` (JVM) with a `MutableStateFlow<NetworkState>` and the fake connector in `HostTestKit`: count connect calls offline; flip to available and assert the time to the connect call with the test scheduler; change id while online and assert `reconnect` is the next request on `FakeSocket`. It also covers a change during a backoff wait, a failure on a network that has since changed, losing the network while online, and `restart()` and `stop()` while waiting.
- `BackoffTest`: seeded `Random`, bounds and cap.

---

## H2 — Release chats and channels in the background

### Problem

With monitoring on and the app in the background, the open session's chat, up to three warm chats (`MAX_WARM_CHATS`), and any open terminal or changeset stay subscribed. A working agent streams every token to the phone. Every reconnect asks for those chats again, and the host ignores the turn limit in `view` (parent spec, 2026-10-25), so each reconnect can pull several whole transcripts of tens of MB each.

The background only needs the root channel (session list, statuses) for alerts and the inbox. `WaitingRequests` already does its own short `subscribe` and `unsubscribe` when it needs a session's detail.

### Design

- `HostSession.park()` and `unpark()`, both idempotent and safe from any thread. Each sets a `@Volatile parked` flag at once, then does its work on the session's scope under `subscribeLock`, which reads the flag again first, so the last call wins when they overlap.
  - `park()`: for every chat and aux store currently subscribed, send `unsubscribe` and `invalidate()` the store (it keeps showing what it has and needs a subscribe again). Session stores stay subscribed: they are small and the open one feeds `ChatConfig` and the custom agents. A chat held open in the background (below) is left as it is.
  - `unpark()`: run the same resubscribe the connection does after it comes online (`restoreChats()`, plus `auxNeedingSubscribe()`). With no live connection it does nothing; the next connection subscribes because the flag is clear.
  - While parked, `openChannels()` (what `initialize` and `reconnect` name to the host) leaves out the changesets, `attach()` invalidates any aux store left out, and the post-online `restoreChats()` skips every chat that parking let go of and nothing holds in the background (`stayingParked`). A reconnect while parked therefore names only the root and the sessions.
  - A connection that is being made while `park()` or `unpark()` is called has nothing live to act on, yet its handshake already named (or left out) the changesets. `settleChannels()` runs once the connection is live and lets go of, or subscribes, whatever the flag asks for.
- **An explicit open still works while parked.** `decideToolCall` from a notification calls `openChat`, and that must subscribe. As built: `openChat(uri, background = false)` and `closeChat(uri, background = false)`. A chat opened with `background = true` counts in `ChatStore.backgroundOpeners`, survives `park()`, and is subscribed again by a reconnect while parked. When the last background closer leaves a chat that a screen still holds, and the session is parked, the chat is unsubscribed and invalidated again. `decideToolCall` opens and closes with `background = true`. Changesets and terminals opened while parked are subscribed at once and have no background hold: nothing opens one out of sight.
- **A chat that is subscribed again keeps its old state, so the store must say when that state is stale.** `ChatStore.subscribeNeeded` is `needsSubscribe` as a `StateFlow`, true until a snapshot arrives and again after `invalidate()`. While it is true, `apply()` holds actions in the early buffer, so none is lost between the request and the new snapshot. The snapshot clears it last, after `state` is published. `decideToolCall` waits for `subscribeNeeded` to turn false and `state` to be non-null before it acts, and refuses if the store reports an error, so an approval is never decided against a transcript from before the park.
- **Who parks.** `ParkScheduler` (`core-ahp/.../app/ParkScheduler.kt`, plain Kotlin so it runs under virtual time) takes `visible(on)`, parks `graceMs` (`DEFAULT_GRACE_MS`, 30 s) after the app leaves sight, and unparks on the next return. A return inside the wait cancels it and calls nothing, and repeated stops never stack waits. `AppController` owns one: `setAppVisible(visible)`, with `parkGraceMs` as a constructor parameter, parking whichever `HostSession` is `Connected`. `connect()` asks `ifParked` to park a session created while the app is already out of sight. The grace period covers quick hops: the biometric prompt, the browser for GitHub sign-in, a glance at another app. `AhpApp.onCreate` registers a `ProcessLifecycleOwner` observer (`androidx.lifecycle:lifecycle-process`, version-catalog alias `lifecycle-process`) that calls `setAppVisible(true)` on `ON_START` and `false` on `ON_STOP`. The process lifecycle rather than `MainActivity` means `ApproveActivity` in front counts as visible, and a rotation, which the process reports a moment late, is not a stop. The container is built lazily, so the observer reaches it only when an event arrives.
- `AppState` does not change. The UI still shows the open chat. When it returns, the chat says it is loading the way it does after a reconnect.

### Acceptance criteria

- [x] **AC-H2.1** 30 s after the app leaves the screen, the wire log shows `unsubscribe` for each chat, terminal and changeset that was subscribed, and nothing else changes.
- [x] **AC-H2.2** A reconnect while parked names only the root and session channels.
- [x] **AC-H2.3** Coming back resubscribes them. The transcript on screen stays until the new snapshot replaces it, and its expansion state is kept. (The tests pin the first two; the expansion state is held by the screen, not the store, so parking cannot touch it, and no test covers it.)
- [x] **AC-H2.4** Approve and Deny from a notification while parked still work, and leave the chat unsubscribed afterwards.
- [x] **AC-H2.5** Leaving and coming back within 30 s sends no `unsubscribe`.
- [ ] **AC-H2.6** On the phone: 30 minutes of monitoring with the screen off while an agent works uses clearly less mobile data than before (measure with `adb shell dumpsys netstats detail` for the app's UID, before and after). Record the numbers in `docs/verification/`.

### Tests

- `HostSessionParkTest` (JVM): park and unpark against `FakeSocket`, checking the frames; a transient `openChat` while parked; `reconnect` params while parked; overlapping park and unpark; a park that comes while a connection is being made.
- `ParkSchedulerTest` (JVM, virtual time): grace period, a quick return, repeated stops.
- `ChatStoreTest`: actions held while a snapshot is awaited, and `subscribeNeeded` staying up until the new state is published.
- `AppControllerTest`: `setAppVisible` parking and unparking the live session, including one connected while the app is out of sight; `decideToolCall` while parked subscribes for the decision, acts on the new snapshot, and refuses a chat found gone.

---

## H3 — Ping interval from a measurement

### Problem

`AppContainer` builds one `OkHttpClient` with `pingInterval(30 s)`. While monitoring, that wakes the mobile radio about twice a minute for as long as it runs. Nobody has measured how long the tunnel relay actually lets an idle socket live.

### Design

1. Measure. Add a `--ping-seconds` option to `:core-ahp:probe` (0 means off). Connect, subscribe to nothing beyond root, stay idle, and log when the relay closes the socket and with what code. Run it three times on Wi-Fi and three on mobile data (through a phone hotspot). Record the results in `docs/verification/relay-idle.md`.
2. Set `WS_PING_SECONDS` to about 75 % of the shortest idle timeout observed, capped at 60 s, with a floor of 25 s.
3. Give the WebSocket its own client, `http.newBuilder().pingInterval(WS_PING_SECONDS)`, so it shares the connection pool but the REST calls are clearly not part of it.

### Acceptance criteria

- [ ] **AC-H3.1** The idle measurement is recorded, with the method used.
- [ ] **AC-H3.2** The chosen value is in code, with a comment that points to the measurement.
- [ ] **AC-H3.3** On the phone: two hours of idle monitoring has no more drops than at 30 s (count the `AhpConn` reconnect lines).

---

## H4 — Bounded frame and notification buffers

### Problem

Two buffers have no limit. `OkHttpTextSocket.frames` ([OkHttpSocketOpener.kt](../core-ahp/src/main/kotlin/dev/ahpclient/tunnel/OkHttpSocketOpener.kt)) holds raw text, and `AhpConnection.inbox` ([AhpConnection.kt](../core-ahp/src/main/kotlin/dev/ahpclient/ahp/AhpConnection.kt)) holds decoded notifications. A burst faster than the reducers can apply, or several whole-chat snapshots queued together, grows the heap until the process dies.

### Design

- `OkHttpTextSocket` tracks queued characters in an `AtomicLong`: added on `deliver`, taken off on `receive`. Above `MAX_QUEUED_CHARS` (64 M, which is 64 MB for ASCII on compact strings), it fails the socket with a new `ClientOverloadedException` (an `IOException`) and closes with code 1008. A single frame larger than `MAX_FRAME_CHARS` (48 M) does the same before it is queued.
- `AhpConnection.inbox` becomes `Channel(MAX_QUEUED_NOTIFICATIONS = 20_000)`. If `trySend` fails because the channel is full, the read loop ends with `ClientOverloadedException`. That closes the connection, and `HostSession` reconnects and resumes, which replays what was missed. This is the existing path for any dropped socket.
- `ClientOverloadedException` is not terminal in `HostSession.isTerminal`. Three in a row while subscribing the same chat counts toward that chat's `noteInterrupted()`, so a chat too large for the phone ends as "not loaded" instead of looping.
- The debug `describeFailure` names the cause.

### Acceptance criteria

- [ ] **AC-H4.1** Pushing more than the limit through a fake socket ends the connection with `ClientOverloadedException`, and `HostSession` goes to `Reconnecting`, not `Failed`.
- [ ] **AC-H4.2** A single oversized frame is refused before it is queued.
- [ ] **AC-H4.3** Normal traffic, including the 275 KB `listSessions` reply in the fixtures, is unaffected.
- [ ] **AC-H4.4** A chat that overloads the connection three times in a row is marked not loaded and is not subscribed again until the person asks.

### Tests

`OkHttpTextSocketTest` (with `MockWebServer`) for the character accounting; `AhpConnectionRobustnessTest` gains overflow cases; `HostSessionTest` for the reconnect and the not-loaded outcome.

---

## H5 — Close the `connect()` race

### Problem

`AppController.connect()` ([AppController.kt:214](../core-ahp/src/main/kotlin/dev/ahpclient/app/AppController.kt)) checks `state is Hosts` and later writes `Connected` with a plain assignment. If `endSignIn` (from an expired token on the host-list job) runs in between, `Connected` overwrites `SignedOut`. The app then shows a live session with no token in memory, which fails at once with a confusing state.

### Design

- Make `connect()` `@Synchronized`, and replace the assignment with `compareAndSet(current, Connected(...))`, where `current` is the `Hosts` value read at the top. If the swap fails, `session.stop()` and return.
- Make `disconnect()` `@Synchronized` and use compare-and-set the same way.
- Leave `endSignIn` unconditional: signing out must always win.

### Acceptance criteria

- [ ] **AC-H5.1** If the state leaves `Hosts` between the check and the write, the end state is the one that won (for example `SignedOut`), and the session that was created is stopped.
- [ ] **AC-H5.2** All existing `AppControllerTest` cases pass unchanged.

### Tests

An `AppControllerTest` case with a `HostSessionFactory` whose `create()` first drives the controller to `SignedOut` (through the existing 401 path on the fake Dev Tunnels server, run on an unconfined dispatcher), then returns a session. Assert the final state and that `stop()` was called.

---

## H6 — Come back after process death

### Problem

`MonitorService` returns `START_NOT_STICKY` on purpose, because a restarted process has no connection. When Android kills the process (memory, an OEM battery manager, an update), monitoring simply stops:

- The **Monitoring** setting still says on.
- The next launch lands on the host list.
- An Approve or Deny button on a notification posted before the kill answers "not allowed", because `decideToolCall` needs `AppState.Connected`.

### Design

**1. Reconnect on a cold start when monitoring is on.** In `AppController.restore()`, after `showHosts()` has loaded the list: if monitoring is enabled, a last host is saved, and that host is in the list and online, call `connect()` on it. This needs one new constructor parameter, `autoConnect: suspend () -> Boolean`, wired in `AppContainer` to read `PrefsMonitorSettings`. Only the last host is ever auto-connected, and the tier is still the saved consent, read before anything connects as today.

**2. Connect on demand for a notification action.** Add `AppController.ensureConnected(timeoutMs: Long): Boolean`. It waits for `Restoring` to finish. If the state is `Hosts`, it waits for the list, connects to the last-used host if it is online, and then waits for `ConnState.Online`. It returns false on timeout or any failure, and never signs in or shows anything. `decideToolCall` and `readWaiting` call it first, with a budget of 10 s. `DecisionReceiver` already holds `goAsync()` for 8 s, so it becomes 12 s and the toast says "Open the app to answer" when it fails. `ApproveActivity` is in the foreground and can wait the full time with a progress indicator.

**3. Say so when it could not come back.** Add a periodic `WorkManager` job (`MonitorWatchdog`, every 15 minutes, network not required) that does one thing: if monitoring is enabled and the process has no live `HostSession` (`AppState` not `Connected`), post a single notification, "Monitoring stopped. Tap to resume.", which opens the app. Opening it triggers item 1. It never starts the foreground service itself, because Android forbids that from the background. It cancels itself when monitoring is turned off. New dependency: `androidx.work:work-runtime-ktx`, pinned in `libs.versions.toml`.

**4. Foreground service start failures are no longer silent.** `AndroidMonitorService.start()` swallows `IllegalStateException`. Make it return a result. `MonitorCoordinator` sets `status` to "Monitoring could not start. Open the app." and posts the same notification as the watchdog.

### Acceptance criteria

- [ ] **AC-H6.1** With monitoring on, a cold start (`adb shell am force-stop` then launch) goes from the splash to Connected to the last host without a tap, and the foreground notification comes back.
- [ ] **AC-H6.2** With monitoring off, a cold start stops at the host list, as it does today.
- [ ] **AC-H6.3** After the process is killed (`adb shell am kill` with the app in the background), Deny on a notification still in the shade connects and denies, or says "Open the app to answer" within 12 s. It never claims success that did not happen.
- [ ] **AC-H6.4** After the process is killed with monitoring on, the "Monitoring stopped" notification appears within 15 minutes (shortened in a debug build so it can be checked), and tapping it brings monitoring back.
- [ ] **AC-H6.5** The watchdog posts nothing while a live session exists, and nothing when monitoring is off.
- [ ] **AC-H6.6** If the last host is offline, nothing auto-connects and the host list shows as it does today.

### Tests

`AppControllerTest`: auto-connect on and off, last host offline, `ensureConnected` success, timeout and signed out. Instrumented: `MonitorWatchdog` logic with WorkManager's test helpers (`work-testing`).

---

## H7 — Encrypted cache of the session list

### Problem

A cold start shows nothing until the connection is online and `listSessions` returns, which took about 6 s on the Pixel. The drawer and the inbox are blank meanwhile.

### Design

- `SessionListCache` in `core-ahp`, behind a `BlobStore` and a `SecureBox`, the same seams `TokenVault` uses, with its own AAD purpose (`"session-list-v1"`) so a token blob can never be read as a list or the other way round.
- Content: `{ tunnelId, savedAt, items: [SessionSummary…] }`, encoded with `Ahp.json`, at most 500 items and 2 MB. Larger lists are cut to the 500 most recently changed.
- Written from `HostSession` 5 s after the catalog last changed (debounced), and once on `disconnect`. Kept in `noBackupFilesDir`; the app already sets `allowBackup="false"`.
- On `connect`, before the first `Online`, `RootStore` is seeded from the cache if the `tunnelId` matches, with a new `RootStore.stale: StateFlow<Boolean>` set to true. `refreshCatalog` replaces it and clears `stale`.
- Monitoring and alerts **ignore stale data**: `InputNeededNotifier` and `FinishedNotifier` already wait for `Online` before taking their baseline, and that must stay true.
- Cleared on sign-out (`endSignIn`), alongside the vault.
- UI: while stale, the drawer and inbox show a small "Updating…" chip, and the write actions (mark read, archive) are disabled.

### Acceptance criteria

- [ ] **AC-H7.1** A cold start that auto-connects (H6) shows the cached list within a second of the first frame, marked as updating.
- [ ] **AC-H7.2** Once online, the live list replaces it, and no "needs input" or "finished" alert fires for anything that only differed between the cache and the live list.
- [ ] **AC-H7.3** After sign-out the cache file is gone. After switching hosts, one host's list is never shown for another.
- [ ] **AC-H7.4** A corrupt or undecryptable cache is deleted and ignored, never shown and never fatal.

---

## H8 — Foreground service without the six-hour cut

### Problem

On Android 15 and newer, a `dataSync` foreground service may run about six hours in 24. `MonitorService.onTimeout` handles the cut correctly, but a working day of monitoring reaches it.

### Design

- On API 34 and above, run as `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, with the `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Live connection to the user's own coding-agent host, to alert when a session needs them" />` and the `FOREGROUND_SERVICE_SPECIAL_USE` permission. Below 34, keep `dataSync`, which has no limit there.
- Keep `onTimeout` for any timeout the platform still applies.
- Update the parent spec's open question 4: `specialUse` is fine for sideloading. Distribution through the Play Store needs its written justification reviewed first.

### Acceptance criteria

- [ ] **AC-H8.1** On the Pixel (Android 17), `dumpsys activity services dev.ahpclient` shows the service type as `specialUse`.
- [ ] **AC-H8.2** Monitoring runs for more than seven hours without `onTimeout` (an overnight run on charge).
- [ ] **AC-H8.3** On the API 26 emulator the service still starts as `dataSync`.

---

## F1 — Answer a question from the notification

### What

When a session waits on a single free-text question, the "needs input" notification gets an **Answer** action with `RemoteInput`. A question with several fields, or with choices, still opens the app.

### Design

- `NotificationInputNeededSink` adds the action only when `canDecide()` (the app may act for the user) and the request read by `WaitingRequests` is exactly one text field.
- A new `AnswerReceiver` (not exported, immutable `PendingIntent`, mutable only for the `RemoteInput` result, as Android requires) reads the text and calls a new `AppContainer.answerFromNotification(chatUri, requestId, text)`. That goes through `ensureConnected` (H6) and then the same `ChatInteractor` path the chat screen uses for answers, so every check stays in one place. The answer needs no device check, matching the in-app question form.
- The notification updates to "Sent" or the error text, and goes away when the host's echo clears the request.

### Acceptance criteria

- [ ] **AC-F1.1** The action appears only with "Act for me" on and a single text question.
- [ ] **AC-F1.2** An answer sent from the lock screen reaches the host. The notification clears on the host's echo and never before.
- [ ] **AC-F1.3** If the question was answered elsewhere first, the reply is refused with a clear message and nothing is sent.

---

## F2 — Quick Settings tile

### What

A tile showing how many sessions wait on you ("2 waiting") while monitoring runs. Tapping it opens the inbox. A long press opens the app's settings.

### Design

- `MonitorTileService` reads `container.monitor.status` and the inbox count while listening (`onStartListening` and `onStopListening`).
- A tap starts the activity on the inbox (`startActivityAndCollapse` with a `PendingIntent` on API 34+). The tile does not toggle monitoring itself, because starting a foreground service from a tile is not reliably allowed. Opening the app is enough.
- Inactive state when monitoring is off: "Monitoring off".

### Acceptance criteria

- [ ] **AC-F2.1** The tile's count matches the inbox's "Waiting for you" within a few seconds.
- [ ] **AC-F2.2** A tap opens the inbox from the lock screen after the phone's unlock.

---

## Verification on the phone

For each item that has an on-phone criterion, record the run in `docs/verification/hardening.md`: date, build commit, Android version, what was done, what was seen. For battery and data, before-and-after numbers taken the same way each time:

- Data: `adb shell dumpsys netstats detail`, filtered to the app's UID, over a fixed 30-minute window with an agent working.
- Battery: `adb shell dumpsys batterystats --reset` before the run, then `adb shell dumpsys batterystats dev.ahpclient` after, on battery (not USB power). Compare the app's estimated mAh and its radio-active time.
- Wire: the debug `AhpWire` and `AhpConn` logcat tags, which already log methods and state changes and never tokens.

## Open questions

1. How long does the relay keep an idle socket open? H3 measures it.
2. Should cold-start auto-connect be its own setting rather than following the Monitoring switch? This spec ties them together; split them if the coupling surprises in use.
3. Is 30 s the right grace before parking (H2)? Tune it after a week of use.
4. Do the host's whole-chat frames (parent spec, 2026-10-25) make the H4 caps too tight for real chats? Check against the largest chat seen on the Pixel before settling the numbers.

## Progress Log

| Date | Entry |
|---|---|
| 2026-10-26 | Spec drafted from the code review at `a8bb8ed`. Nothing started. |
| 2026-10-26 | H1 and H2 built on `95af56d`, in the commit that adds this row, with `BackoffTest`, `HostSessionNetworkTest`, `HostSessionParkTest`, `ParkSchedulerTest` and additions to `ChatStoreTest` and `AppControllerTest`. AC-H1.1 to H1.4 and AC-H2.1 to H2.5 ticked. AC-H1.5, AC-H1.6 and AC-H2.6 need the phone and are still open. The H1 and H2 designs above describe what was built, including that the network monitor does not require `NET_CAPABILITY_VALIDATED`. |
