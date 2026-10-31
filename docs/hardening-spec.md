# Hardening: battery, network, memory and recovery

> **Status:** In progress. H1, H2, H4, H5, H6, H7, F1 and F2 are built and covered by JVM tests. H8 is built and assembled. H3 client split (`wsHttp`) and `:core-ahp:probe --ping-seconds 0` idle-close logger are built; the live relay idle measurement (AC-H3.1 to H3.3) awaits a tunnel run. Open on the phone: AC-H1.5, AC-H1.6, AC-H2.6, AC-H6.1, AC-H6.3, AC-H6.4, AC-H7.1, AC-H8.2, AC-H8.3, AC-F1.2 and AC-F2.2.
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

**Socket** ([OkHttpSocketOpener.kt](../core-ahp/src/main/kotlin/dev/ahpclient/tunnel/OkHttpSocketOpener.kt))

- `OkHttpTextSocket` keeps its frame channel unbounded, because dropping a frame would corrupt the state mirror, and bounds it by counting instead. `queued` is an `AtomicLong` of characters: added in `deliver`, taken off in `receive`. Above `MAX_QUEUED_CHARS` (64 M, which is about 64 MB for ASCII, since Java keeps such text one byte to a character) the socket is overloaded. A single frame over `MAX_FRAME_CHARS` (48 M) is refused the same way before it is counted or queued.
- Overloading is one step (`overloaded()`): the failure is recorded once, the frame channel is closed and drained, `queued` goes to 0, the peer is closed with code 1008 (`TextSocket.POLICY_VIOLATION`, new) and anything delivered afterwards is ignored. `receive()` then throws the `ClientOverloadedException` even if frames were still waiting, so the failure is not held up behind what was already queued.
- `OkHttpSocketOpener` and `OkHttpTextSocket` take the two limits as constructor parameters, defaulting to the constants, so a test can use small ones.

**Connection** ([AhpConnection.kt](../core-ahp/src/main/kotlin/dev/ahpclient/ahp/AhpConnection.kt))

- `inbox` becomes `Channel(maxQueuedNotifications)`, a constructor parameter defaulting to `MAX_QUEUED_NOTIFICATIONS` (20 000, `internal`). Every path that queues a notification (actions, named notifications, undecodable frames) goes through `enqueue()`. If `trySend` fails because the buffer is full, `enqueue()` throws `ClientOverloadedException`, which ends the read loop. The collector still receives what was queued before it sees the failure, as `ConnectionClosed` with the overload as its cause, and a request waiting at the time fails with `ConnectionClosed` too.
- The limit counts notifications, not bytes. The byte bound is the socket's.
- An action advances `lastServerSeq` only **after** it is queued. An action lost to a full buffer therefore does not move the resume point, and the next `reconnect` asks for it again.
- **Not in the original design:** `HostSession.supervise()` copies the connection's `lastServerSeq` into its own `lastSeq` only if the connection reached the point where notifications are collected (`routing`). Until then `lastServerSeq` counts actions that were queued but never applied, and an overload while attaching (a burst of replayed actions arriving beside the `reconnect` reply) would otherwise skip them.

**Session**

- `ClientOverloadedException` (an `IOException`, in `tunnel/TextSocket.kt`) needs no change to `HostSession.isTerminal`: it falls to the `else` branch, so the session goes to `Reconnecting` and resumes, the existing path for any dropped socket.
- The chat rule is the existing one. `subscribeIfNeeded` for a chat already calls `noteInterrupted()` when the connection is lost while it waits for that chat's snapshot, and the overload arrives there as `ConnectionClosed`. Three in a row mark the chat "not loaded" (`interrupted = true`) and it is not subscribed again until the person retries. An overload while no chat is waiting for a snapshot counts against no chat.
- `describeFailure` (`tunnel/MethodLoggingSocket.kt`) adds the overload's message, which names only a limit and a count, to the debug line.

### Acceptance criteria

- [x] **AC-H4.1** Pushing more than the limit through a fake socket ends the connection with `ClientOverloadedException`, and `HostSession` goes to `Reconnecting`, not `Failed`. (For the notification buffer, `HostSessionOverloadTest` floods a `FakeSocket` and checks `Reconnecting` with the overload as the cause, then that the next connection resumes with `reconnect`. For the character count, `OkHttpTextSocketTest` checks the socket fails with the exception.)
- [x] **AC-H4.2** A single oversized frame is refused before it is queued.
- [x] **AC-H4.3** Normal traffic is unaffected. (`OkHttpTextSocketTest` sends a 300 000-character reply, larger than the 275 KB `listSessions` reply seen on the Pixel, and the chat snapshot fixture through the real opener at the default limits. The `listSessions` fixture itself is only 7 KB, and the rest of the suite runs with the default notification buffer.)
- [x] **AC-H4.4** A chat that overloads the connection three times in a row is marked not loaded and is not subscribed again until the person asks.

### Tests

- `OkHttpTextSocketTest` (JVM): the character accounting directly on `OkHttpTextSocket` with small limits (counted on queueing and given back on reading, so only what is waiting counts; over the limit drops what was queued; an oversized frame refused before queueing; a frame exactly at the limits accepted; a reader already waiting is woken; once overloaded it keeps failing and queues nothing), plus one `MockWebServer` case for normal traffic through `OkHttpSocketOpener` at the default limits.
- `AhpConnectionRobustnessTest`: a full buffer ends the connection with the overload as the cause; a request waiting at the time fails with `ConnectionClosed`; what was queued is still delivered before the collector sees the failure; an action lost to a full buffer does not move `lastServerSeq`; an undecodable frame is refused too; a burst that fits is not an overload.
- `HostSessionOverloadTest` (new, JVM, with `ScriptedConnector` taking a small `maxQueuedNotifications`): `Reconnecting` not `Failed`, and the next connection resumes; a burst that fits leaves the connection alone; three overloads in a row while a chat is subscribing give "not loaded" and no further subscribe; `retry()` subscribes again; an overload with no chat loading counts against none; actions queued while attaching are asked for again when the overload ends the connection before they are applied.
- `MethodLoggingSocketTest`: `describeFailure` names the overload and its limit anywhere in the chain, and an overloaded socket is logged as it ends.

---

## H5 — Close the `connect()` race

### Problem

`AppController.connect()` ([AppController.kt:214](../core-ahp/src/main/kotlin/dev/ahpclient/app/AppController.kt)) checks `state is Hosts` and later writes `Connected` with a plain assignment. If `endSignIn` (from an expired token on the host-list job) runs in between, `Connected` overwrites `SignedOut`. The app then shows a live session with no token in memory, which fails at once with a confusing state.

### Design

All in `AppController.kt`.

- `connect()` is `@Synchronized`. After it creates and starts the session, it swaps `Hosts` for `Connected` with `compareAndSet`. If the state is no longer a `Hosts`, it calls `session.stop()` and returns. **Deviation from the first design:** the swap is a loop that re-reads the state and retries, not a single compare against the `Hosts` value read at the top. A `Hosts` value is replaced by a copy whenever the list loads or the token is renewed (`_state.update { it.copy(...) }`), and refusing the connection because of that would drop a legitimate tap. Only a state that is not `Hosts` at all (a sign-out) ends the loop with a refusal.
- `disconnect()` is `@Synchronized`, and `showHosts` takes `from: AppState? = null`. With `from`, the host list replaces only that state (`compareAndSet`) and the list is not loaded if the screen has moved on. `disconnect()` passes the `Connected` it is leaving, so a sign-out that landed first stands.
- `endSignIn` stays unconditional, because signing out must always win. **Not in the first design:** it now swaps the state with `getAndUpdate`, stops the session of the `Connected` it displaced and cancels the watch job. The host list stays tappable while the disk wipe is awaited, so a `connect()` can complete inside that window and would otherwise leave a live session running under `SignedOut`.

### Acceptance criteria

- [x] **AC-H5.1** If the state leaves `Hosts` between the check and the write, the end state is the one that won (for example `SignedOut`), and the session that was created is stopped. (Two cases: a sign-out inside the factory's `create()`, and a connect made while the sign-out's wipe is still pending. Both end `SignedOut` with the session in `ConnState.Stopped`.)
- [x] **AC-H5.2** All existing `AppControllerTest` cases pass unchanged. (The shared `withRig` helper gained one defaulted parameter, `onCreate`; no existing case was edited. The whole `:core-ahp:test` run is green.)

### Tests

Four `AppControllerTest` cases:

- A sign-out that lands while the session is being made wins, and that session is stopped, never reaches the host, and `lastHost` is not saved. The sign-out runs from a new `onCreate` hook in the test factory (`controller.signOut()` then `runCurrent()`), not through the 401 path on the fake Dev Tunnels server as the first design suggested.
- A connection made while a sign-out is wiping the token (`FakeStore.deleteGate` in `VaultTestKit` holds the wipe half done) is stopped when the sign-out lands, and the state ends `SignedOut`.
- The host list being replaced while a connection is made (`controller.refreshHosts()` from `onCreate`) does not stop the connection.
- The same, forced between the read and the swap: the controller's private state is replaced by a `RacingState` wrapper that changes the state before the first `compareAndSet`, and the swap is retried to success.

---

## H6 — Come back after process death

### Problem

`MonitorService` returns `START_NOT_STICKY` on purpose, because a restarted process has no connection. When Android kills the process (memory, an OEM battery manager, an update), monitoring simply stops:

- The **Monitoring** setting still says on.
- The next launch lands on the host list.
- An Approve or Deny button on a notification posted before the kill answers "not allowed", because `decideToolCall` needs `AppState.Connected`.

### Design

**1. Reconnect on a cold start when monitoring is on** (`AppController.kt`, `AppContainer.kt`).

- New constructor parameter `autoConnect: suspend () -> Boolean` (default false), wired in `AppContainer` to `monitorSettings.enabled.first()`. The tier is still the saved consent, read before anything connects as today.
- **Deviation from the first design:** the connect is not in `restore()` itself, because `showHosts()` only starts the list load. `restore()` sets an `autoConnectPending` flag (published on `HostCatalog.autoConnectPending`) when a saved sign-in is taken with a saved last host and `autoConnect()` true. While `autoConnectPending` still needs the tunnel list (`lastId` is not a direct host), a failed load in `loadHosts()` keeps the flag and retries with `Backoff`; the first list that settles (or a direct host, or a manual refresh while the first load has failed) takes the flag (`getAndSet(false)`) and calls `connectToLastHost`. A later refresh, a return to the host list, a fresh sign-in, or a sign-in GitHub refused at the first list therefore never connects on its own. `swapToConnected` and `endSignIn` clear the flag.
- `connectToLastHost` is the one place the cold start and `ensureConnected` connect from: the list must be `Loaded`, the host the last used one and online, and `autoConnect()` true. Only the last host is ever auto-connected.
- Sign-out ends monitoring: `afterSignOut`, a new constructor parameter, runs after the user's own sign-out has taken effect (not after a sign-in that GitHub or the host refused) and `AppContainer` wires it to turn the Monitoring switch off. Without it the watchdog would keep asking the person to resume what they chose to leave.

**2. Connect on demand for a notification action** (`AppController.kt`, `InteractMessages.kt`, `DecisionReceiver.kt`, `ApproveActivity.kt`).

- `AppController.ensureConnected(timeoutMs: Long): Boolean`. It waits for `Restoring` and for a loading host list to finish; from a loaded list it connects as above, then waits for `ConnState.Online`. It returns false on timeout, when signed out, when `autoConnect` says no, when the last host is offline or not listed, or when the connection ends `Failed` or `Stopped`. It never signs in and shows nothing.
- `decideToolCall` and `readWaiting` take `background: Boolean = false`. With it set (the notification buttons), they call `ensureConnected(CONNECT_MS)` first, `CONNECT_MS` being 10 s, and `decideToolCall` bounds reaching the host plus reading the chat by `PREPARE_MS` (11 s, a little under the receiver's 12 s). The in-app inbox leaves it unset: it never connects and never waits for a connection to come back.
- New `InteractResult.NotConnected` (message "Open the app to answer."): a background caller with monitoring on that could not get a connection in time. Nothing is sent. With monitoring off the result stays `NotAllowed`. A session still showing the saved list (H7) is not acted on either.
- `AppContainer.decideFromNotification` calls `start()` first, because a process started only for the button has not restored the sign-in, and passes `background = true`.
- `DecisionReceiver` holds `goAsync()` for 12 s (was 8 s). `ApproveActivity` shows an indeterminate spinner over a dim backdrop while it waits, as it is in the foreground and can wait the full time.

**3. Say so when it could not come back** (`MonitorWatchdog.kt`, `WatchdogDecision.kt`, `MonitorNotifications.kt`, `AppContainer.kt`).

- `MonitorWatchdog` (`schedule`, `cancel`) keeps one unique periodic `WorkManager` job, `monitor-watchdog`, every 15 minutes with no network constraint. It is enqueued with `KEEP`, so opening the app does not push the next check back, and with a first delay of one period, so turning monitoring on is not the moment to say it has stopped. `MonitorWatchdogWorker` is a `CoroutineWorker` that asks `AppContainer.monitoringStopped()` and, if true, posts the warning.
- **WorkManager cannot go below 15 minutes** (`PeriodicWorkRequest` enforces that floor), so the interval cannot be shortened in a debug build. AC-H6.4 is checked either with the real interval or by running the job on demand (`MonitorWatchdogWorker` through the instrumented test, or forcing the scheduled job with `adb shell cmd jobscheduler run -f`).
- The decision is a pure function in core-ahp: `WatchdogDecision.shouldWarn(monitoringEnabled, state, appInFront)` is true when monitoring is on, the app is not in front and the state is not `Connected`. **Deviation from the first design:** the app being in front suppresses the warning (the person can see the state, and a notification would sit over it), and a state of `Restoring` counts as no live session, because a process the system started only for the job has not called `start()`.
- The warning goes through a new `MonitorProblemSink` and `NotificationMonitorProblemSink`: channel `monitor_problem` ("Monitoring problems", default importance), a fixed notification id 102 so a second one replaces the first, `setOnlyAlertOnce`, and a tap opens `MainActivity`, which connects again through item 1. `MainActivity.onStart` clears it. The text is `WatchdogDecision.STOPPED_TEXT`, "Monitoring stopped. Tap to resume." It never starts the foreground service.
- `AppContainer.start()` watches the Monitoring setting: on schedules the job, off cancels it and clears any warning.
- New dependencies, pinned in `libs.versions.toml` (`work` 2.12.0): `androidx.work:work-runtime-ktx`, and `androidx.work:work-testing` for the instrumented test.

**4. Foreground service start failures are no longer silent** (`MonitorCoordinator.kt`, `MonitorService.kt`).

- `MonitorService.start()` (the interface) returns a `Boolean`. `AndroidMonitorService.start()` returns false on the `IllegalStateException` Android 12 and newer throws for a start from the background, and on a `SecurityException`, and true otherwise.
- `MonitorCoordinator` takes an optional `problems: MonitorProblemSink`. A failed start sets `MonitorCoordinator.START_FAILED` ("Monitoring could not start. Open the app.") as the status text and posts the same notification the watchdog does. A start that works later, or monitoring ending, clears it. The first look in a new process with nothing connected does not clear a warning the watchdog already posted. `MonitorCoordinator.refreshService()` is now what `AppContainer.refreshMonitorNotification` calls, so a refresh reports a failed start too.

### Acceptance criteria

- [x] **AC-H6.1** With monitoring on, a cold start (`adb shell am force-stop` then launch) goes from the splash to Connected to the last host without a tap, and the foreground notification comes back. _Seen 2026-10-30 on the API 36 emulator (`ahp-api36`, tunnel host `re1lw1zrrff4` last used, monitoring on): both a normal cold start and a cold start with airplane mode on for 3 s then restored went to Connected on the home menu with no tap, with `MonitorService` running (`isForeground=true`, `foregroundId=100`, `types=0x40000000`); see [`verification/navigation.md`](verification/navigation.md). The controller side is covered by `AppControllerTest`._
- [x] **AC-H6.2** With monitoring off, a cold start stops at the host list, as it does today. (`AppControllerTest`: a restored sign-in with monitoring off stays on the host list.)
- [ ] **AC-H6.3** After the process is killed (`adb shell am kill` with the app in the background), Deny on a notification still in the shade connects and denies, or says "Open the app to answer" within 12 s. It never claims success that did not happen. (`AppControllerTest` covers the decision after a kill, the `NotConnected` result with nothing sent, and the 11 s budget under virtual time; the receiver, the toast and a real kill need the phone.)
- [x] **AC-H6.4** After the process is killed with monitoring on, the "Monitoring stopped" notification appears within 15 minutes, and tapping it brings monitoring back. The interval cannot be shortened (see above), so run it with the real 15 minutes or on demand. (`MonitorWatchdogInstrumentedTest` ran and passed 4/4 on the Pixel 10 `5A170DLCR002E7` and API 36 emulator `emulator-5554`, 2026-10-30.)
- [x] **AC-H6.5** The watchdog posts nothing while a live session exists, and nothing when monitoring is off. (`WatchdogDecisionTest` pins the decision for every state with monitoring on and off and the app in front or out of sight. `MonitorWatchdogInstrumentedTest` verified the worker's call into it and the notification on the Pixel 10, 2026-10-30.)
- [x] **AC-H6.6** If the last host is offline, nothing auto-connects and the host list shows as it does today. (`AppControllerTest`: a last host that is offline, not listed or not saved is not connected to; with monitoring off, a list that failed to load is not retried and a later refresh does not connect; with monitoring on, a failed cold-start list load is retried with backoff until a list loads.)

### Tests

- `AppControllerTest` (JVM): auto-connect when monitoring is on and the host is online; a failed first host-list load at cold start retries with backoff and connects, and a manual refresh while failed also connects; monitoring off stops at the host list (and does not retry a failed list load or connect on a later refresh); last host offline, unlisted or none; going back to the list and refreshing never connect; a fresh sign-in lands on the list; a sign-in refused at the first list leaves nothing waiting. `ensureConnected`: connects from the list and waits for `Online`, waits for a restore still running and makes one connection, true at once when already online, times out, gives up on a connection that failed, false when signed out, with monitoring off and with an offline host. Notification buttons after a kill: connects then decides, an approval still goes through the device check, no connection or a late one says so and sends nothing, the budget holds when the connection and the chat are both slow, monitoring off is not allowed, the inbox on screen neither connects nor waits. `readWaiting` after a kill, and when no connection can be made. `afterSignOut` runs after a sign-out and not after a refused sign-in.
- `WatchdogDecisionTest` (JVM): `shouldWarn` for `Restoring`, `SignedOut`, `Hosts` and `Connected`, with the app in front, out of sight and monitoring off.
- `MonitorCoordinatorTest`: a service that cannot start shows in the status and the sink; the first look in a process leaves an earlier warning alone; the failure stays while the connection changes and alerts still run; a start that works later clears it; ending monitoring and leaving the host clear it; `refreshService()` does nothing with monitoring off. `InteractMessagesTest`: `NotConnected` says "Open the app to answer." and never counts as done.
- `MonitorWatchdogInstrumentedTest` (instrumented, so it needs a device; written, **not run**): the worker posts one replaceable warning that opens the app when monitoring is on and nothing is live, posts nothing with monitoring off or the app in front, and the job is scheduled once, periodic, and can be cancelled.

---

## H7 — Encrypted cache of the session list

### Problem

A cold start shows nothing until the connection is online and `listSessions` returns, which took about 6 s on the Pixel. The drawer and the inbox are blank meanwhile.

### Design

**The cache** (`core-ahp/.../security/SessionListCache.kt`, `app/.../security/FileBlobStore.kt`)

- `SessionListCache(box: SecureBox, store: BlobStore)` with `load(tunnelId)`, `save(tunnelId, items)` and `clear()`, behind the same two seams `TokenVault` uses but with its own AAD (`"dev.ahpclient/session-list-v1"`, `SessionListCache.AAD`) and its own store, so a token blob can never be opened as a list nor a list as a token. One lock serialises the three.
- Content, encoded with `Ahp.json`: `{ v, tunnelId, savedAt, items: [SessionSummary…] }`. `v` (1) is an addition to the first design. At most `MAX_ITEMS` (500) sessions, the most recently changed (`modifiedAt`), and at most `MAX_BYTES` (2 MB) of encoded content: past the byte limit the oldest are dropped until it fits.
- The cache is for one host. `load` returns null, and removes the blob, when it is damaged, cannot be opened, is of another version, is not a list, or was saved for another `tunnelId`. A disk error on read is not a verdict on the blob, so that case leaves it alone and reads as absent. `load` never throws apart from cancellation. `save` throws `IOException` or `VaultCryptoException`; the caller ignores both. The plaintext buffer is zeroed after use. The class never destroys the Keystore key, which belongs to the vault.
- Production: `AndroidSessionListCache.create(context)` gives the Keystore box (`KeystoreSecureBox()`, the vault's key) over a `FileBlobStore` on `noBackupFilesDir/session_list.bin`. **Deviation from the first design:** a plain file replaced whole through `AtomicFile`, not a DataStore, so that deleting it leaves nothing behind. `AppContainer` makes one instance, lazily, and hands it to every session and to the controller; the app already sets `allowBackup="false"`.

**Writing and reading** (`HostSession.kt`, `RootStore.kt`)

- `HostSession` takes `cache: SessionListCache? = null` and `cacheKey: String? = null` (the host's tunnel id); without both it neither reads nor writes. `AppContainer` passes `tunnel.tunnelId`.
- **Seeded at `start()`, not on connect:** `start()` launches `seedFromCache` before the supervisor, so the saved list is in the store before the first connection takes a step. `RootStore.seedStale(items)` marks the store `stale` first and then shows the rows. It is ignored once the host's own list has been taken, so a slow disk never replaces live data. A cache that cannot be read is the same as none.
- `RootStore.stale: StateFlow<Boolean>` is true from the seed until `replaceCatalog` (the host's own list, taken on `initialize` or `reconnect`) clears it, after the rows are replaced. A notification arriving meanwhile does not end it. `RootStore.hasLiveCatalog` is true once the host's own list has been taken.
- Saved from `saveWhenChanged`: 5 s (`CACHE_SAVE_DEBOUNCE_MS`) after `root.sessions` last changed, not conditional on the connection being `Online`, because the list arrives before the connection is declared online and a slow subscription behind it must not let the one chance go by. Also saved straight away when a connection ends and when the session stops (`saveCatalogSoon`), which the first design called "once on `disconnect`". A save runs under `NonCancellable` and a lock, and is skipped when the list is only the seed (`hasLiveCatalog` false), unchanged since the last write, or the session was told to forget. Failures are swallowed: the cache is a convenience.
- **Sign-out** (`HostSession.stopAndForget()`, `AppController.endSignIn`): the session is stopped with `cacheForgotten` set and the call waits for a save under way; `endSignIn` stops the live session and any displaced by a connect made during the wipe, then calls `SessionListCache.clear()` (`safeClearCache`), so nothing can write the list again after it. The same wipe runs when the saved token has expired without a refresh and when the vault reports it unreadable (the key is gone with it). `HostSession.stop()` alone, as when going back to the host list, saves.
- **Alerts ignore stale data.** No change was needed in `InputNeededNotifier` or `FinishedNotifier`: they take their baseline only once the connection is `Online`, by when `replaceCatalog` has replaced the seed. `MonitorCoordinatorTest` pins it.
- **Nothing acts on a stale list.** `SessionManager`'s `allowed` is false while `stale`, and `AppController.hostFor` (used by `decideToolCall` and `readWaiting`) will not hand out a session that is stale, so a notification button does nothing then.
- **UI:** `UpdatingChip` ("Updating…", a small spinner, polite live region) in the drawer's Sessions header and in the Inbox top bar. In the Inbox the Approve, Deny and option buttons and "Mark all read" are disabled, the per-card mark-read is disabled, and the waiting sessions are not read for details. The drawer's session controls (`ManageActionsUi`) are not shown while stale, rather than shown disabled, as for a connection without the manage tier.

### Acceptance criteria

- [x] **AC-H7.1** A cold start that auto-connects (H6) shows the cached list within a second of the first frame, marked as updating. (`HostSessionCacheTest` pins that the saved list is shown, marked stale, before the first connection is online and that a list for another host is not. Verified live on the Pixel 10 `5A170DLCR002E7`, 2026-10-30: after `am force-stop` and `am start`, the home drawer immediately showed the cached session rows and the `Updating…` chip while `Connecting…`, then cleared `Updating…` once `Connected` settled.)
- [x] **AC-H7.2** Once online, the live list replaces it, and no "needs input" or "finished" alert fires for anything that only differed between the cache and the live list. (`HostSessionCacheTest` and `RootStoreTest` for the replacement and the cleared mark; `MonitorCoordinatorTest` seeds a list in which every session was working and checks neither sink posts anything once the host's list arrives.)
- [x] **AC-H7.3** After sign-out the cache file is gone. After switching hosts, one host's list is never shown for another. (`AppControllerTest`: sign-out empties the cache's store and the stopped session does not write it back; a list saved for one host is not shown for the other. The "file" is a `BlobStore` fake in those tests; `FileBlobStore` itself is Android code with no JVM test.)
- [x] **AC-H7.4** A corrupt or undecryptable cache is deleted and ignored, never shown and never fatal. (`SessionListCacheTest`: a damaged blob, one that cannot be decrypted, a token blob, another version and non-list content are removed and read as absent; `HostSessionCacheTest`: a cache that fails to read is the same as none.)

### Tests

- `SessionListCacheTest` (JVM, with the vault fakes): nothing saved is absent; a list reads back whole for its host; saving again replaces; more than 500 sessions are cut to the 500 newest; a list over 2 MB drops the oldest until it fits; `clear`; damaged, undecryptable, other-version and non-list blobs, and a list saved for another host, are removed and read as absent; a token blob offered as a list and a list offered as a token are both refused; the blob is sealed under its own AAD; a disk error on read, and a blob that cannot be removed, do not fail the load; a key that will not seal is reported and left alone.
- `RootStoreTest` (JVM): a store with nothing is not stale; seeding shows the rows and marks them; the host's own list replaces the seed and ends the mark; a seed after the host's list is ignored; a notification on stale data does not end it; a later list keeps it live.
- `HostSessionCacheTest` (JVM, virtual time): the saved list is shown, marked stale, before the first connection is online and stays so while the connection is made; the host's list replaces it and clears the mark; a list for another host is not shown; saved 5 s after the last change, a new change starts the wait again, and the list is saved before the connection is online; saved when the connection drops and when the session stops; an unchanged list is not written again; a list that is only the seed is never written back; a session stopped with `stopAndForget` writes nothing; a live list that comes before the disk answers is kept; a cache that cannot be read or written does not disturb the connection; no cache or no key means nothing is read or written.
- `AppControllerTest`: sign-out wipes the saved list and the session it stops does not write it back; an expired or unreadable saved token also removes it; a host never shows the list saved for another; a notification button does nothing while the list is only the saved one, and works once the host's has come.
- `MonitorCoordinatorTest`: a saved list that differs from the host's own raises no "needs input" or "finished" alert for the difference.
- Not covered on the JVM: `FileBlobStore` and `AndroidSessionListCache`, the "Updating…" chip, and the disabled controls. They need the phone.

---

## H8 — Foreground service without the six-hour cut

### Problem

On Android 15 and newer, a `dataSync` foreground service may run about six hours in 24. `MonitorService.onTimeout` handles the cut correctly, but a working day of monitoring reaches it.

### Design

- **Manifest** (`app/src/main/AndroidManifest.xml`): the service declares `android:foregroundServiceType="dataSync|specialUse"`, with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Live connection to the user's own coding-agent host, to alert when a session needs them" />`, and the app requests `FOREGROUND_SERVICE_SPECIAL_USE` beside the existing `FOREGROUND_SERVICE_DATA_SYNC`. Both types are declared because the type passed at run time must be one of them and the run-time choice depends on the Android version.
- **Choice of type** (`core-ahp/.../app/ForegroundKind.kt`, plain Kotlin so a JVM test can pin it): `ForegroundKind.forSdk(sdkInt)` gives `SPECIAL_USE` from API 34, `DATA_SYNC` from API 29 to 33 (no time limit there) and `NONE` below 29.
- **Start** (`MonitorService.enterForeground`): maps the kind to the matching `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` constant and calls `ServiceCompat.startForeground`, which leaves the type out below API 29, where `startForeground` does not take one. The constants are copied in at build time and `ForegroundKind` only picks one the running Android has, hence `@SuppressLint("InlinedApi")`.
- **Deviations from the first design:** the first design said to keep `dataSync` below API 34. As built, `dataSync` is for API 29 to 33 and API 26 to 28 start with no type, as before this change, since those versions have no foreground-service types. The `specialUse` branch is chosen by `Build.VERSION.SDK_INT >= 34` through `ForegroundKind`, not by a check in the service.
- `MonitorService.onTimeout` is kept for any timeout the platform still applies, and its comment now says so. It still turns monitoring off and posts "Background monitoring paused".
- The parent spec's open question 4 now says `specialUse` is fine for sideloading, and a Play Store release needs the written justification reviewed first.

### Acceptance criteria

- [x] **AC-H8.1** On the Pixel (Android 17), `dumpsys activity services dev.ahpclient` shows the service type as `specialUse`. _Seen 2026-10-29 on `6cd6615`: `MonitorService`, `isForeground=true`, `types=0x40000000` (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`); see [`verification/navigation.md`](verification/navigation.md)._
- [ ] **AC-H8.2** Monitoring runs for more than seven hours without `onTimeout` (an overnight run on charge).
- [ ] **AC-H8.3** On the API 26 emulator the service still starts as `dataSync`. (As built, API 26 has no foreground-service types, so the service starts without one there; the `dataSync` branch applies from API 29, so seeing `dataSync` needs an API 29 to 33 device or emulator. Decide which of the two this criterion means when it is run.)

### Tests

- `ForegroundKindTest` (JVM): API 25, 26 and 28 give `NONE`; 29 and 33 give `DATA_SYNC`; 34 and 35 give `SPECIAL_USE`. This pins the choice at the version boundaries, not that Android accepts the start; that is what the three criteria above check on a device.
- `MonitorInstrumentedTest.theManifestDeclaresTheServiceAsDataSyncAndSpecialUseWithItsPermissions` (instrumented, so it needs a device or emulator): the manifest carries the `dataSync` bit from API 29 and the `specialUse` bit from API 34, the service is not exported, and `FOREGROUND_SERVICE_SPECIAL_USE` is among the requested permissions.

---

## F1 — Answer a question from the notification

### What

When a session waits on a single free-text question, the "needs input" notification gets an **Answer** action with `RemoteInput`. A question with several fields, or with choices, still opens the app.

### Design

- `NotificationInputNeededSink` adds the action only when `canDecide()` (the app may act for the user) and the request read by `WaitingRequests` is exactly one text field.
- A new `AnswerReceiver` (not exported, immutable `PendingIntent`, mutable only for the `RemoteInput` result, as Android requires) reads the text and calls a new `AppContainer.answerFromNotification(chatUri, requestId, text)`. That goes through `ensureConnected` (H6) and then the same `ChatInteractor` path the chat screen uses for answers, so every check stays in one place. The answer needs no device check, matching the in-app question form.
- The notification updates to "Sent" or the error text, and goes away when the host's echo clears the request.

### Acceptance criteria

- [x] **AC-F1.1** The action appears only with "Act for me" on and a single text question. (`WaitingTextTest` for `QuestionTarget.from`, `InputNeededDetailTest`, and `NotificationDecisionInstrumentedTest`.)
- [ ] **AC-F1.2** An answer sent from the lock screen reaches the host. The notification clears on the host's echo and never before. (`AppControllerTest` and `ChatInteractorTest` cover the controller, dispatch, and `postReplyStatus`; a live lock-screen run needs the phone.)
- [x] **AC-F1.3** If the question was answered elsewhere first, the reply is refused with a clear message and nothing is sent. (`ChatInteractorTest` and `AppControllerTest`: an answered or missing request returns `NoSuchRequest`, described as `"That is no longer waiting for an answer."`, with nothing dispatched.)

---

## F2 — Quick Settings tile

### What

A tile showing how many sessions wait on you ("2 waiting") while monitoring runs. Tapping it opens the inbox. A long press opens the app's settings.

### Design

- `MonitorTileService` reads `container.monitor.status` and the inbox count while listening (`onStartListening` and `onStopListening`).
- A tap starts the activity on the inbox (`startActivityAndCollapse` with a `PendingIntent` on API 34+). The tile does not toggle monitoring itself, because starting a foreground service from a tile is not reliably allowed. Opening the app is enough.
- Inactive state when monitoring is off: "Monitoring off".

### Acceptance criteria

- [x] **AC-F2.1** The tile's count matches the inbox's "Waiting for you" within a few seconds. (`MonitorTileState.from` uses the same unarchived `NEEDS_INPUT` predicate as `Inbox.from`, tested in `WaitingTextTest`; `MonitorTileService` collects `monitor.status` and the live session's `root.sessions` and `connection` while listening.)
- [ ] **AC-F2.2** A tap opens the inbox from the lock screen after the phone's unlock. (`MonitorTileService.onClick` uses `unlockAndRun` when locked and launches `MainActivity` with `EXTRA_OPEN_INBOX`, which calls `controller.closeSession()`; a live lock-screen run needs the phone.)

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
| 2026-10-26 | H5, H4 and H8 built on `1e917e1`, in the commit that adds this row. **H5:** `connect()` and `disconnect()` are `@Synchronized` and swap state with compare-and-set (a retry loop for `connect()`, so a host list replaced meanwhile is not a refusal); `endSignIn` also stops a session that a connect made during the wipe; four new `AppControllerTest` cases. **H4:** `OkHttpTextSocket` counts queued characters (64 M, one frame 48 M, close code 1008) and `AhpConnection`'s inbox holds 20 000 notifications; past either, `ClientOverloadedException` drops the connection and the next one resumes. `HostSession` no longer moves its resume point past actions queued but not yet applied. New `OkHttpTextSocketTest` and `HostSessionOverloadTest`, additions to `AhpConnectionRobustnessTest` and `MethodLoggingSocketTest`. **H8:** the service declares `dataSync\|specialUse` and starts as `specialUse` from API 34 (`ForegroundKind`, `ForegroundKindTest`), `dataSync` on API 29 to 33 and untyped below; `docs/spec.md` open question 4 updated. `:core-ahp:test` ran green (1083 tests, no failures). AC-H4.1 to H4.4 and AC-H5.1 and H5.2 ticked. AC-H8.1 to H8.3 need the phone and are still open, as are AC-H1.5, AC-H1.6 and AC-H2.6. The H4, H5 and H8 designs above describe what was built, including where it departs from the first design. |
| 2026-10-26 | H6 and H7 built on `c682a67`, in the commit that adds this row. **H6:** a restored sign-in connects to the last host by itself when Monitoring is on and that host is online (`autoConnect`, taken once by the first host list); `AppController.ensureConnected` and a `background` mode on `decideToolCall` and `readWaiting` let a notification button connect after the process was killed, with a new `InteractResult.NotConnected` ("Open the app to answer.") and a 12 s receiver; `MonitorWatchdog` (WorkManager, every 15 minutes, which is the shortest it allows) posts "Monitoring stopped. Tap to resume." through `WatchdogDecision`; a foreground service that cannot start is said in the status and by notification; sign-out turns Monitoring off. **H7:** `SessionListCache` (own AAD, 500 sessions and 2 MB, one host) in a `FileBlobStore` file, seeded into `RootStore` as stale at `start()`, saved 5 s after a change and when a connection ends, wiped at sign-out; the drawer and inbox show "Updating…" and nothing acts on a stale list. New `WatchdogDecisionTest`, `SessionListCacheTest`, `RootStoreTest` and `HostSessionCacheTest`, additions to `AppControllerTest`, `MonitorCoordinatorTest` and `InteractMessagesTest`, and an instrumented `MonitorWatchdogInstrumentedTest` that has not been run. `AppControllerTest` (160), `MonitorCoordinatorTest` (31), `WatchdogDecisionTest` (3), `SessionListCacheTest` (17), `HostSessionCacheTest` (17) and `RootStoreTest` (6) ran with no failures. AC-H6.2, H6.5, H6.6, H7.2, H7.3 and H7.4 ticked. AC-H6.1, H6.3, H6.4 and H7.1 need the phone and are still open, as are AC-H1.5, AC-H1.6, AC-H2.6 and AC-H8.1 to H8.3. The H6 and H7 designs above describe what was built, including where it departs from the first design. |
| 2026-10-28 | **F1 built, H3 client split and `--ping-seconds` probe flag added, `MonitorWatchdogWorker` cast fixed.** **F1:** `QuestionTarget.from(SessionState)` identifies when a session is waiting on a single free-text question in a listed chat; `WaitingRequests` and `InputNeededNotifier` pass it to `NotificationInputNeededSink`, which attaches a `RemoteInput` **Answer** action (on the public version too) when `canDecide()` is true. `AnswerReceiver` (unexported, explicit component) forwards the reply to `AppContainer.answerFromNotification` -> `AppController.answerQuestion` -> `ChatInteractor.answerText`, which checks live chat state and form bounds without a biometric prompt, and updates the notification via `postReplyStatus` ("Sent" or the refusal reason) until the host's echo clears it. **H3 prep:** `AppContainer` splits `wsHttp` (`pingInterval(30s)`) from the plain HTTPS `http` client, and `:core-ahp:probe` accepts `--ping-seconds <n>`. AC-F1.1 and AC-F1.3 ticked; AC-F1.2 awaits a phone run. |
| 2026-10-28 | **F2 built (on `main` as `d390574`) and H3 `:core-ahp:probe --ping-seconds 0` idle-close logger completed (`62fdf79`).** `ProbeArgs.parse` accepts `--ping-seconds 0` (disabling OkHttp WebSocket pings) and `Probe.watch` logs the elapsed idle duration and WebSocket close code/reason when `AhpException.ConnectionClosed` arrives (`ProbeTest`). |
| 2026-10-30 | **H6 cold-start auto-connect retry on a failed first tunnel-list load (`999d280`, #10).** Fixed finding 6 from [`verification/navigation.md`](verification/navigation.md): `restore()` sets `autoConnectPending` only when a last host is saved and `autoConnect()` is true, `HostCatalog` carries `autoConnectPending`, `loadHosts()` keeps `autoConnectPending` across a failed tunnel-list load and retries with `Backoff` until the list settles (or a manual refresh loads it), and `OfferHostsOnce` holds off while `autoConnectPending` is true. **Tests:** `:core-ahp:test` 1303 passed, 0 failed (`AppControllerTest` adds retry-without-tap, manual-refresh-while-failed and monitoring-off-no-retry cases); instrumented `HomeDrawerTest` 38 passed, 0 failed on `emulator-5554` (adds two `OfferHostsOnce` cases for `autoConnectPending`). **Verified on `ahp-api36`:** cold start with airplane mode on for 3 s held off the Hosts sheet, and restoring the network connected to `re1lw1zrrff4` with no tap and started `MonitorService` (`isForeground=true`, `foregroundId=100`, `types=0x40000000`). Ticked AC-H6.1. |
