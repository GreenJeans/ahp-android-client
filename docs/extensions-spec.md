# Host extensions and a second host kind

> **Status:** E1, E3, E4, E5, E6 and E7 are built and covered by JVM tests (E6 live-checked on the Pixel against `ahpd` 0.14.0). E0 is done except AC-E0.2 (the twelve-field table against `ahpc`'s `CONFORMANCE.md`). E2 is built for branch and `PullRequest` model/SafeLinks; pull-request UI chips await a host that sends PR fields.
> **Date:** 2026-10-27
> **Parent:** [`docs/spec.md`](spec.md). That spec holds the product phases. [`docs/hardening-spec.md`](hardening-spec.md) holds the battery, network, memory and recovery work; this one holds what the app can learn from other hosts and clients. Tick boxes here as work lands and add a row to the Progress Log at the bottom; note the commit in the row.
> **Source:** the "Private extensions in the wild" section of [`docs/CONFORMANCE.md`](https://github.com/softov/ahpc/blob/main/docs/CONFORMANCE.md) in the `ahpc` repo (a terminal AHP client by the author of `ahpd`), read 2026-10-26, and a comparison with this app's code at commit `c682a67`. The `ahpc` document was captured against AHP 0.9.0 on 2026-09-13 and reached me through a summarising fetch, so **read the original before relying on a field name**; Item E0 exists to check each one against real captures.

## Overview

Real AHP hosts send fields the protocol does not declare. `ahpc` records twelve of them, found by validating live captures from two hosts: VS Code's agent host and `ahpd`. It reads about five and leaves the rest alone. This app reads none: the only `_meta` handling is in `SessionCatalog.kt`, which replaces it whole and ignores it.

Most of the fields are cheap, read-only and useful on a phone: the git branch, the pull request and its state, the "what is it doing now" text of a running tool call, and the flag that tells a client to hide a message from the transcript. None needs a new capability tier.

The same document points at a second thing. `ahpc` talks to `ahpd` over a plain WebSocket with a connection token and no Dev Tunnel, GitHub sign-in or tunnel discovery. This app only knows how to reach a Remote Tunnel Access host. A second **host kind** is much smaller than the concurrent multi-host support we deferred (parent spec, open question 6), and it would give us a safe host to test against: our notes record that tool-approval requests have not yet been seen live.

### Goals

- Read the useful extensions through one small module that absorbs the differences between hosts, so screens never see host-specific spellings.
- Show branch and pull-request state on a session, a progress message on a running step, and hide the messages VS Code hides.
- Find out, with evidence and not by reading someone else's list, which extensions each host we can reach actually sends.
- Settle whether a host that omits `ActionEnvelope.origin` breaks the way this app matches an echo to a send.
- Let the host list hold a directly addressed host (`ahpd`) next to the tunnel hosts, with its own consent.

### Non-goals

- Concurrent connections to several hosts (still deferred, parent spec open question 6).
- Any write capability the tiers do not already allow. Customization toggles, `authenticate`, `terminal/input` and the rest stay out (parent spec, "Capability allowlist").
- Serving completions, or the completion-attachment `_meta` contract (it only matters if we ever add `@` or `/` completion in the message box).
- Mirroring `ahpc`'s tool server (`ahpc mcp`), recording, automations or telemetry.
- Reading fields that exist only to serve write features: `argumentHint`, customization `enabled`/`nonce`/`childEnablement`, config `scope`, root `sessionMutable`.

### Order of work

| # | Item | Theme | Size | Depends on |
|---|---|---|---|---|
| E0 | Extension census and fixtures | Evidence | S | — |
| E1 | `HostExtras` reader | Foundation | M | E0 |
| E2 | Branch and pull-request chips | Feature | M | E1 |
| E3 | Progress message on a running step | Feature | S | E1 |
| E4 | Hide messages hidden from the transcript | Fidelity | S | E1 |
| E5 | Match an echo without `origin` | Conformance | M | E0 |
| E6 | A directly addressed host kind (`ahpd`) | Multi-host | L | E0, E5 |
| E7 | Protocol version fallback | Compatibility | S | E0 |

E0 comes first because everything else rests on facts about hosts we have not captured. After it, E2, E3 and E4 are independent and can run in parallel sessions once E1 is in. E5 can start as soon as E0 says whether any reachable host omits `origin`. E6 is the largest and shares files (host list, `AppController`, `AppContainer`) with the hardening work, so start it only when nothing else is touching them.

---

## E0 - Extension census and fixtures

### Problem

We know twelve extension fields by name from a third party's notes, for one protocol version, from hosts we have not run. We have live captures of one host (VS Code's, through a tunnel), redacted into `core-ahp/src/test/resources/live/`. We do not know which of the twelve it sends to us at protocol 1.0.0, what `ahpd` sends, or whether the generated Kotlin types expose `_meta` on session state, tool calls and messages at all (`SessionSummary` does; the rest is unchecked).

### Design

- Add a `--census` option to `:core-ahp:probe` (`ProbeMain.kt`). It connects as today, subscribes to the root, to every listed session up to a limit, and to the newest chat of each, reads for a fixed time, and writes a report of every **key path that is not in the generated types**: for each frame kind (snapshot, action, notification) the set of JSON paths seen under `_meta`, and any other key the generated decoder drops. Values are not recorded, only paths, types and counts, so the report holds no titles, branch names or URLs.
- Compare the report to the twelve fields in the source document: for each, `seen`, `not seen` or `seen under another name`.
- Check the library: for `SessionState`, `ToolCallState`, the message and turn types and `ActionEnvelope`, say whether the generated Kotlin type has a `_meta` property. Where it does not, E1 reads the raw frame the way `ConfigFacts` does.
- Run it against the VS Code host through the tunnel, and, when one can be started, against a local `ahpd`. For `ahpd`, first find out (from its README and by trying) how it is addressed and authenticated; record that in `docs/verification/extensions.md`, because E5 and E6 need it.
- From the captures, add redacted fixtures under `core-ahp/src/test/resources/live/` (using `tools/redact-capture.mjs`) that carry the extension fields, one set per host. The redaction check (`tools/redaction-check.mjs`) must pass on them.

### Acceptance criteria

- [x] **AC-E0.1** `:core-ahp:probe --census` writes a report that lists the unknown key paths per frame kind and holds no values from the host. _Done 2026-10-28 against `ahpd` 0.14.0: see `docs/verification/extensions.md`._
- [ ] **AC-E0.2** `docs/verification/extensions.md` says, for each of the twelve fields and for each host reached, seen / not seen / other name, with the date and protocol version the host chose.
- [x] **AC-E0.3** The same file says which generated types have a `_meta` property and which do not. _Done 2026-10-28._
- [x] **AC-E0.4** Redacted fixtures with the extension fields are checked in and pass the redaction check. _Done 2026-10-28: `live/vscode-session-meta.json` for VS Code git `_meta` (`tools/redact-meta.mjs`) and `live/ahpd-tool-meta.json` for `ahpd` / VS Code tool-call `_meta` (`toolKind`, `ahpd.durationMs`, `mcpServerName`, `subagentAgentName`), both verified by `findLeaks` in `tools/redaction-check.test.mjs`._
- [x] **AC-E0.5** The file records how `ahpd` is addressed and authenticated, or says plainly that it could not be reached. _Done 2026-10-27: see `docs/verification/extensions.md`._

### Tests

A JVM test for the census's path collector over a fixture frame (known keys excluded, unknown paths found, no values in the output); the redaction check on the new fixtures.

---

## E1 - `HostExtras` reader

### Problem

The useful extensions arrive under different names depending on the host: `branchName` or `branch` for the git branch, `SessionState.model` or `_meta.model`, and so on. If each screen read its own spelling, every new host would mean edits across the UI.

### Design

- A pure module in `core-ahp` (`state/HostExtras.kt`) that takes the decoded state, plus the raw frame where the generated type drops `_meta`, and returns a small model per kind:
  - `SessionExtras(branch: String?, ahead: Int?, behind: Int?, pullRequests: List<PullRequest>, model: String?)`, where `PullRequest(url, state, branch)`.
  - `StepExtras(progressMessage: String?)` for a tool call.
  - `MessageExtras(hiddenFromTranscript: Boolean)` for a message or request.
- It accepts every spelling the source document lists and treats every field as optional: wrong type, missing, or from a host that does not send it all give `null` or the default, never an exception. Unknown values (a pull-request state we have not seen) are kept as text, not dropped.
- Strings are bounded (a branch name or message over a fixed length is cut) and URLs are carried as text only; E2 decides which may be opened.
- Where the raw frame is needed, extend `ConfigFacts`-style reading in `AhpConnection` (the facts are recorded as a reply is read and picked up when the snapshot is stored) rather than a second parsing pass. Only the keys this module needs are kept, and the number kept is bounded like `MAX_CONFIG_FACTS`.
- The stores expose the extras next to their state (for example `SessionStore.extras: StateFlow<SessionExtras>`), so a screen reads a model and never a JSON path.

### Acceptance criteria

- [~] **AC-E1.1** Given the E0 fixtures from each host, `HostExtras` returns the same `SessionExtras` model whichever spelling the host used.
- [x] **AC-E1.2** Missing, mistyped, oversized or hostile values (a branch of a million characters, a URL with a control character, a number where text is expected) produce defaults and no exception. _Done 2026-10-28 for the fields built (branch and counts): `HostExtrasTest`._
- [x] **AC-E1.3** The extras update when the host changes them (a `sessionSummaryChanged`, or an action that replaces `_meta`), and clear when the host removes them. _Done 2026-10-28: `SessionCatalog.merge` replaces `_meta` whole on the session list, and `SessionStore.apply` reduces `session/metaChanged` on an open session (`HostExtrasTest`)._
- [x] **AC-E1.4** No outbound method or action changes: the capability allowlist test passes unchanged. _Nothing outbound was touched; the 1,249 JVM tests pass, `CapabilityPolicyTest` among them._

### As built (2026-10-28, branch and counts only)

Built from the census, not from the twelve-field document: only what a real host was seen to send is read, so E1 today is **branch and its counts** and nothing else.

- `state/HostExtras.kt`: `HostExtras.session(meta)` returns `SessionExtras(branch, outgoing, incoming, uncommitted, detachedHead)`; `SessionSummary.extras` and `SessionState.extras` read it from the decoded `_meta`. No raw-frame reading was needed, because `SessionSummary` and `SessionState` both declare `_meta` (AC-E0.3).
- Spellings read: `git.branchName`, `git.outgoingChanges`, `git.incomingChanges`, `git.uncommittedChanges`, `git.isDetachedHead`, as a nested object or as dotted keys (the census cannot tell which a host uses, and `vscode.*` keys were seen as dotted keys), and `gitData.<folder>.*` for a multi-root workspace (first folder with a branch; at most 16 folders looked at). `git` wins over `gitData`.
- Names are the host's own. `outgoing` and `incoming` are not called ahead and behind until a host's meaning is confirmed.
- Text is stripped of control characters, trimmed and cut to 200 characters; counts outside 0 to 1,000,000, or not numbers, are dropped; a string where a number or a flag belongs is ignored.
- **Also in `HostExtras`:** `PullRequest(url, state, branch)` on `SessionExtras.pullRequests` (with `PullRequest.openableUrl` gated by `SafeLinks.isSafeVerificationUri`), `StepExtras(progressMessage)` on running `ToolCallState` (`ToolCallState.extras`, E3), and `MessageExtras(hiddenFromTranscript)` on `Message` (`Message.extras`, E4).
- Tests: ten cases use shapes written by hand from the census; one reads `live/vscode-session-meta.json`, a redacted capture of the VS Code host (twelve sessions, one per distinct key set, including a multi-root `gitData` and sessions with no git), and checks the reader against each. The real capture confirmed `git` is a nested object and `vscode.*` keys are dotted.
- **AC-E1.1 is met for branch and counts on the VS Code host only.** `ahpd` sends no git data, so there is nothing to show for the "whichever spelling the host used" half until a second host sends one.

### Tests

`HostExtrasTest` over the E0 fixtures and a table of malformed values; store tests for update and clear; the existing `CapabilityPolicyTest` untouched.

---

## E2 - Branch and pull-request chips

### What

A session shows its git branch (and how far it has drifted, when the host says) and, when the host reports pull requests, a chip for each: open, merged or closed. Tapping a pull-request chip opens it through the existing link confirmation (`SafeLinks` allows `github.com`, and the dialog shows the real address before leaving the app).

### Design

- Show them where a session is already described: the drawer row (branch only, one line), the session screen's header or Details sheet (branch, drift, pull requests), and the inbox cards (pull-request state only if it fits).
- Pull-request chips use the pull-request URLs from `SessionExtras`, passed through `SafeLinks`; a URL that is not a plain `https://github.com/...` address is shown as text, not as a link. Nothing is fetched from GitHub: the state is whatever the host last said.
- A state the app does not know is shown as its text, in the neutral style. Colour is never the only carrier (the chip carries a word).
- A host that sends none of this shows none of it: no empty chip, no placeholder.

### Acceptance criteria

- [~] **AC-E2.1** A session whose host sends a branch shows it on its drawer row and on the session screen; one that does not shows neither. _Built 2026-10-28; JVM-tested, and the UI tests pass on the API 36 emulator._
- [ ] **AC-E2.2** A session with a pull request shows a chip with its state; tapping it opens the link confirmation with the real address, and an address that is not on `github.com` is not made tappable.
- [ ] **AC-E2.3** The chips pass the accessibility test class (labels, contrast, touch size) in both themes.
- [ ] **AC-E2.4** On the phone, against the VS Code host, a session with a branch and a pull request shows both within a few seconds of subscribing, and a session without shows no empty chip.

### As built (2026-10-28, branch only)

No host has sent a pull-request field (E0), so only the branch half is built.

- **Drawer row:** `SessionRow.branch` (from `SessionSummary.extras.branchLabel`) shows as a small label on the second line between the subtitle and the change count. It is host text, so it is one line and ellipsised, and it shortens before the change count does. The row's spoken description adds "branch <name>" after the subtitle. No branch, no label.
- **Details sheet:** two more tiles in the facts list: **Branch** (the name, or "Detached HEAD") and **Git status** (the non-zero counts in the host's words, for example "2 outgoing, 3 uncommitted"; absent when all are zero). No tile when the host sent nothing.
- **Not done:** the session screen's header (the Details sheet is the session screen's place for it for now), the inbox cards, drift worded as ahead and behind (the host's `outgoing` and `incoming` are shown as sent), and any pull-request chip.
- **Checked:** 3 new JVM cases (`HostExtrasTest`) over the row, the detail and the wording; 1,252 JVM tests pass; the app and instrumented tests compile; lint shows only its 3 baseline warnings. **Run on the `ahp-api36` emulator:** all of `SessionScreensTest` passes, 57 tests, including the 3 new cases (a row with and without a branch, the Details tiles with and without git data). The first attempt through `run-device-tests.ps1` ended with `Process crashed` and no stack trace; a second run of the same instrumentation by hand passed, so that crash is unexplained. **The Pixel on Android 17 cannot run any Compose UI test at present:** Espresso's idle check calls `InputManager.getInstance`, which that Android version no longer has (9 of 57 tests ran and all failed that way, before reaching app code). **Not run:** AC-E2.3's accessibility class for the new label.

### Tests

Compose UI tests for the chips (present, absent, unknown state, hostile URL), added to `AccessibilityTest`; `SafeLinks` cases for the URLs `HostExtras` can carry. AC-E2.4 needs the phone.

---

## E3 - Progress message on a running step

### What

While a tool call is running, the host may say what it is doing ("Running tests", "Searching the repo"). Show it on the running step in the transcript, and in the live status line when the step is collapsed, in place of the bare tool name.

### Design

- `StepExtras.progressMessage`, read while the tool call is `running` only (the source document reads it only then); once the call finishes the normal result view takes over.
- The transcript model (`TranscriptRowModel`) carries it on the step row; the collapsed "steps" row uses the newest running step's message as its one line.
- The text is plain: no markdown, trimmed to one line, bounded, and never put in a notification (it may name files or commands).

### Acceptance criteria

- [x] **AC-E3.1** A running tool call with a progress message shows it on its step row; a finished one does not. _(`HostExtrasTest` and `TranscriptTest`.)_
- [x] **AC-E3.2** The collapsed steps row shows the newest running step's message and falls back to today's text when there is none. _(`TranscriptRowModelTest`.)_
- [x] **AC-E3.3** The message never reaches a notification, the monitor status line or a log. _(Read only in `Transcript` and `TranscriptRowModel`; never read by `InputNeeded`, `WaitingRequests`, `MonitorStatus` or `MethodLoggingSocketOpener`.)_

### Tests

`TranscriptRowModelTest` cases (running, finished, absent, oversized, multi-line); a screen test for the collapsed row.

---

## E4 - Hide messages hidden from the transcript

### Problem

VS Code marks some messages (synthetic or system prompts) as hidden from the transcript. This app renders everything it receives, so a phone can show text that the VS Code panel does not, which is a fidelity bug rather than a feature.

### Design

- `MessageExtras.hiddenFromTranscript` from `_meta['vscode.chat.hiddenFromTranscript']` and `requestHiddenFromTranscript`, plus the text-prefix spellings `ahpc` also reads (confirm the exact prefixes against the original document and the E0 captures; do not guess them).
- The transcript model drops a hidden message and any turn that becomes empty because of it. Nothing is deleted from the store, so a later "show hidden" switch stays possible.
- A message hidden by the host is still counted where a count matters to correctness (paging cursors, turn counts used for "older turns"); only the rendering changes.

### Acceptance criteria

- [x] **AC-E4.1** A message flagged hidden by either spelling is not rendered; the same chat without the flag renders it. _(`HostExtrasTest` and `TranscriptTest`.)_
- [x] **AC-E4.2** A turn whose only content is hidden does not leave an empty bubble. _(`TranscriptTest`: neither a `User` row nor an orphan `TurnEnd` marker is emitted for an empty hidden turn.)_
- [x] **AC-E4.3** Paging older turns, the "working" and "stopped" markers and subagent links behave as before on a chat that contains hidden messages. _(`TranscriptTest`: `hasOlder`, `running`, `waitingOnYou` and `TurnEnd` markers hold on a chat with hidden messages, and `ChatStore` keeps all turns untouched.)_

### Tests

`TranscriptTest`/`TranscriptRowModelTest` cases built from a captured chat with hidden messages; a paging test over a chat that has them.

---

## E5 - Match an echo without `origin`

### Problem

`ActionEnvelope.origin` is declared required, but `ahpc` reports that hosts omit it. This app waits for the echo of its own action by matching `origin.clientId` and `origin.clientSeq` (`HostSession.resolveDispatch`, which returns at once when `origin` is null). Against a host that omits it, an accepted message, approval or answer would never resolve, and the person would be told "No answer from the host yet" even though it worked. Nothing is wrong in the code for the VS Code host; this is a risk for any other host, and E6 would add one.

### Design

- E0 says whether any host we can reach omits `origin`. If none does, record that and stop: add the fixture and a test that pins today's behaviour, and keep this item as a watch.
- If one does, add a fallback that is safe to use without `origin`: an echo whose channel and action type match the oldest unanswered dispatch on that channel, and whose action equals the one sent (compared as the JSON we encoded), resolves that dispatch. Never resolve on channel alone. If several identical dispatches are outstanding, resolve in order.
- A rejected echo with no `origin` is still never applied locally.
- Surface the case in the debug log (`AhpConn`) so a run on a real device shows which path was used.

### Acceptance criteria

- [x] **AC-E5.1** A fixture host that omits `origin` on echoes resolves a sent message as `Accepted`, and the chat shows it once. _(`HostSessionDispatchTest`.)_
- [x] **AC-E5.2** An echo that matches no outstanding dispatch resolves nothing and is applied as an ordinary action from someone else. _(`HostSessionDispatchTest`.)_
- [x] **AC-E5.3** Two identical dispatches in flight resolve in order and neither is lost or resolved twice. _(`HostSessionDispatchTest`.)_
- [x] **AC-E5.4** A host that does send `origin` behaves exactly as before (the existing `HostSessionDispatchTest` cases pass unchanged). _(`HostSessionDispatchTest`, plus `AhpConn` debug log note when an echo without `origin` answers a dispatch.)_

### Tests

`HostSessionDispatchTest` additions with a `HostScript` whose echoes drop `origin`; a duplicate-action test; a mutation check that removing the action comparison fails a test.

---

## E6 - A directly addressed host kind (`ahpd`)

### Problem

The host list only knows tunnel hosts: `HostEntry` wraps a `TunnelInfo`, `TunnelHostConnector` resolves a connect token through the Dev Tunnels service and the gateway handshake, and sign-in is GitHub-only. `ahpd` and VS Code's `code agent host` listen on a plain WebSocket and take a connection token. The seam for a second connector already exists (`HostConnector`).

### Design (to be settled after E0 shows how `ahpd` is addressed and authenticated)

- **Model.** Generalise the host list entry to a sealed type: `TunnelHost(TunnelInfo)` and `DirectHost(id, name, url)`. The saved direct hosts live in a small store (address and display name in preferences; the connection token in the Keystore-backed vault under its own AAD purpose, not in preferences, never in a log). Adding and removing a direct host is a screen on the host list; a token is typed or scanned once.
- **Connector.** `DirectHostConnector(url, token)` implements `HostConnector`: open the socket (the existing `SocketOpener`, so the debug wire log and the buffer limits from H4 apply), send the token the way the host expects, and hand the socket to `AhpConnection` under the policy of the current consent tier. No GitHub token and no Dev Tunnels call.
- **Consent per host.** `InteractionConsent` is global today. A direct host must start at the read tier regardless of what is allowed for the tunnel host, and "Act for me" and "Manage sessions" are chosen per host. Sign-out of GitHub does not forget direct hosts; removing a direct host removes its token, its cached session list (H7 keys by host id already) and its consent.
- **Transport safety.** Android blocks cleartext by default at our `targetSdk`. Require `wss://` for any address that is not a loopback or private-range address, and for `ws://` addresses show the person what that means before the first connection and refuse them in a release build unless they are on a private range (10/8, 172.16/12, 192.168/16, 100.64/10 for mesh VPNs, `localhost`, and the emulator's `10.0.2.2`). A network-security configuration scoped to those ranges, not a blanket allow.
- **Everything downstream is host-agnostic already**: `HostSession`, the stores, monitoring, alerts and the park/network logic work on any `HostConnector`. Auto-connect (H6) stays tied to the **last used** host; if that is a direct host, its saved address and token are used.
- **Concurrent connections stay out of scope**: one live host at a time, switched from the host list as today.

### Acceptance criteria

- [x] **AC-E6.1** A direct host can be added (address, name, token), listed beside tunnel hosts, connected to, and removed; its token is in the vault and absent from preferences, logs and backups.
- [x] **AC-E6.2** A new direct host starts at the read tier; turning on "Act for me" for the tunnel host does not turn it on for the direct one, and the reverse.
- [x] **AC-E6.3** A `ws://` address outside the allowed ranges is refused with a clear message; an allowed one connects; `wss://` connects.
- [x] **AC-E6.4** Removing a direct host removes its token, cached list and consent; signing out of GitHub leaves direct hosts and their tokens alone.
- [x] **AC-E6.5** A direct host and a tunnel host cannot be live at once, and a cached session list is never shown for the wrong one.
- [x] **AC-E6.6** On the emulator, against a local `ahpd`, the app connects, lists sessions and shows a chat; with "Act for me" on it receives and decides a tool-approval request. Record the run in `docs/verification/extensions.md`. _Done 2026-10-27 on the Pixel (Android 17) against `ahpd` 0.14.0 in a LAN VM, not the emulator: see `docs/verification/extensions.md`. Starting the session needed the "No folder" choice below._

### Tests

`DirectHostConnector` against a local `MockWebServer`; the address-policy table; the host store and vault round trips with the existing fakes; `AppController` cases for adding, connecting, switching and removing; per-host consent cases in `InteractionConsentTest`; UI tests for the add screen. AC-E6.6 needs a running `ahpd` and the emulator.

### As built (2026-10-27)

What differs from the design above, and why:

- **No GitHub sign-in is needed.** A direct host is reachable with nobody signed in: the sign-in screen has "Connect to a host by address", the app opens on the host list when hosts were added and no one is signed in, and the last host used, if it is a direct one, is reconnected to by itself under the same Monitoring rule as a tunnel host. `AppState.Hosts` carries the tunnel list (`list`, which may be `NotSignedIn`, `Loading` or `Failed`) and the direct hosts (`direct`) separately, so a failed tunnel list never hides them. Signing out of GitHub leaves the hosts and their tokens alone; it does end a live connection (of either kind) and wipes the one saved session list.
- **The model** is `HostRef` (`Tunnel(TunnelInfo)` or `Direct(DirectHost)`) on `AppState.Connected.host`, not a sealed host-list entry: `HostEntry` and `DirectEntry` stay two plain rows, which kept every existing screen and test as they were. Ids of direct hosts start `direct:`, so the one "last host" setting holds either.
- **The token** is sent as `Authorization: Bearer`, never in the address (an address that carries `?tkn=`, a fragment or credentials is refused). It is kept in `DirectHostVault`: one sealed blob of all tokens, with its own Keystore key (`ahp-android-client.direct-hosts-key.v1`), its own DataStore file (`ahp_direct_tokens`) and its own AAD, because signing out of GitHub destroys the GitHub vault's key and these must survive that. A blank token keeps nothing (a host started with `--without-connection-token`).
- **Consent per host.** The tunnel hosts keep today's two saved choices. Each direct host has two of its own (`allow_interaction.<id>`, `allow_management.<id>`), off until turned on while connected to it. `AppController.interactionAllowed`, `managementAllowed`, `setInteraction` and `setManagement` all follow the host that is connected (the tunnel hosts' when none is), and Settings says which one it is acting on. Removing a host removes both keys.
- **Transport.** `HostAddress.check` accepts `wss://` anywhere, and `ws://` only to loopback, 10/8, 172.16/12, 192.168/16, 100.64/10, IPv6 loopback and unique-local, and `localhost`; a `ws://` to any other name is refused, because a name cannot be known to be private. It runs when a host is added, when it is loaded, and again on every connection. **The network-security configuration the design asked for cannot be written:** it names domains, not address ranges. Cleartext is therefore allowed in the manifest and the rule above is what keeps it to private networks; so that this is not a way round the old protection, the shared HTTP client no longer follows a redirect between https and http.
- **Per-host cached list.** The session list cache is keyed by the host id, as H7 built it, so a list saved for one host is never shown for another; it is one slot, so switching hosts drops the other's list.
- **A bad token or address ends the retrying** (`DirectHostException`, terminal like an unsupported protocol version) with a sentence in the person's words; a host that is only down keeps the normal backoff.
- **Host quirk found on the first live run.** `ahpd` 0.14.0 leaves `description` out of an agent that has none (its `copilot` agent), which the pinned library requires, so the `initialize` reply would not decode and the app could not have connected at all. `HostQuirks` fills an absent description with an empty one, on the fixed paths where agents appear (the root snapshot in `initialize`, `reconnect` and `subscribe`, and `root/agentsChanged`). Nothing else is changed, and a present value is never touched.
- **The probe** takes `--direct <address>` with the token in `AHPD_TOKEN` and runs the production connector at the read tier, checking the methods on the wire.
- **A new session can have no folder** (found on the first phone run). A host with no sessions has no folders to offer, because the dialog took them only from the host's own sessions, so nothing could be started. `SessionManager.newSession` and `sessionSettings` now take a nullable folder: null sends no `workingDirectories` and no `workingDirectory`, the protocol's workspace-less session, and the host chooses where the agent works. It is a choice of its own in the dialog ("No folder (the host chooses)", chosen already when there are no folders), always valid because nothing is sent for it, and still behind the manage switch, an agent the host lists and the device check, which says "No folder: the host chooses". A named folder must still be one the host's own sessions use.

### Open design questions

- ~~How does `ahpd` take the token? Does it speak TLS itself?~~ Answered 2026-10-27: a Bearer header or `?tkn=`; no TLS (see `docs/verification/extensions.md`).
- Does it need the app to appear as an active client, which our allowlist forbids? (If so, that is a new decision, not part of this item.) Not at the read tier: the live probe listed sessions with no such need.
- Echoes without `origin` (E5): **`ahpd` always sends it** (read from its source on 2026-10-28; see `docs/verification/extensions.md`), so E5 is a watch item unless the VS Code host omits it. Earlier note: `ActionEnvelope.origin` is nullable in the pinned library, so the census cannot tell; it now counts envelopes with and without one, which is only suggestive. The phone run saw no failure; a frame capture of an echo of the app's own action would settle it.

---

## E7 - Protocol version fallback

### Problem

`ahpc` prefers 1.0.0 and falls back to 0.9.0, 0.8.0 and 0.7.0. This app offers whatever the pinned library supports (1.0.0 and 0.9.0). A host that speaks only older versions answers with `-32005` and we show an unsupported-version failure.

### Design

- E0 records which versions `ahpd` and a stock `code agent host` choose. If both choose 1.0.0 or 0.9.0, close this item as not needed and keep the failure message as it is.
- If an older version is needed, check whether the pinned library can decode it at all; if not, the fix is a library upgrade (a separate decision), not code here. Do not hand-roll decoding for old versions.

### Acceptance criteria

- [x] **AC-E7.1** The versions each reachable host chooses are recorded in `docs/verification/extensions.md`. _Done 2026-10-28: both `ahpd` 0.14.0 and the VS Code tunnel host chose `1.0.0` when offered `[1.0.0, 0.9.0]`._
- [x] **AC-E7.2** Either the item is closed as not needed, with that evidence, or a follow-up is written naming the library version that would cover the gap. _Done 2026-10-28: closed as not needed, as both reachable hosts speak `1.0.0`._

---

## Verification on the phone

Items with a phone criterion (E2.4, E6.6) get a run recorded in `docs/verification/extensions.md`: date, build commit, Android version, host kind and version, what was done, what was seen. The `AhpWire` and `AhpConn` logcat tags already log methods and state changes without tokens; the census (E0) is the way to look at frame contents, and it records no values.

## Open questions

1. Which of the twelve fields does the VS Code host actually send us at protocol 1.0.0? (E0.)
2. How is `ahpd` addressed and authenticated, and what does it speak? (E0.)
3. Does any reachable host omit `origin` on echoes? (E0, then E5.)
4. Is it right to read pull-request state from the host at all, with no GitHub call of our own? It keeps the app token-free for GitHub data and shows what the host last knew; the cost is that it can be stale. This spec says yes; revisit if a stale "open" misleads.
5. Should hidden messages be reachable through a "show hidden" switch (E4)? Left out here; the store keeps them so it can be added.
6. Is the `Updating…` stale-list behaviour from H7 right for a direct host that is offline at launch? Probably, but E6 should confirm with the person using it.

## Progress Log

| Date | Entry |
|---|---|
| 2026-10-27 | Spec drafted from the `ahpc` conformance document and a read of the code at `c682a67`. Nothing started. |
| 2026-10-27 | **E6 built, and the one piece of E0 it needed (how `ahpd` is addressed).** See "As built" under E6 and `docs/verification/extensions.md`. A direct host can be added by address and token, listed beside the tunnel hosts, connected to with no GitHub sign-in, allowed to act on its own terms and removed. **First live run against the real `ahpd` 0.14.0 on this laptop (read-only probe, `--direct`):** connected with the Bearer header, protocol 1.0.0, `listSessions` returned 758, only `initialize` and `listSessions` on the wire. It first failed to decode, because `ahpd` omits `AgentInfo.description` for its copilot agent; `HostQuirks` now covers that. **Checked:** 1,223 JVM tests pass; lint at its 3 baseline warnings. Not done: the on-device run (AC-E6.6) on the emulator with "Act for me", the phone (it cannot reach a loopback-only `ahpd`), E0 (census, fixtures), E1 to E5, E7. |
| 2026-10-27 | **AC-E6.6 done on the Pixel (Android 17) against `ahpd` 0.14.0 in a LAN VM (Claude Code signed in there).** Connected by address and token with no GitHub sign-in, started a session, sent a message and decided a tool-approval request from the phone with the device check. The first attempt found that a host with no sessions offered no folder, so no session could be started: a new session can now have **no folder** (a workspace-less session, nothing sent for it, the host chooses; see "As built"). **Checked:** 1,228 JVM tests pass, the app and instrumented tests compile; the two new `ManageUiTest` cases are not run. E5 not seen to fail but not checked frame by frame. Not committed. |
| 2026-10-27 | **E0 census built (`:core-ahp:probe --census`), not yet run against a live host.** `ExtensionCensus` (in `probe/`, fed by `FrameLog`) records, per kind of frame (`result:<method>`, `action:<type>`, `notification:<method>`), the paths under `_meta` and the paths the generated types drop (a frame is decoded with the pinned library and written out again; a path that is in the frame and not in the copy is dropped). Values are never kept, and keys that could be titles, addresses or ids print as `<key>`. 10 `ExtensionCensusTest` cases pass with the rest of the JVM suite. Still open: the live runs against `ahpd` and the VS Code host (AC-E0.1, AC-E0.2), the fixtures (AC-E0.4). **AC-E0.3 from the library's sources (1.0.0):** `_meta` is a property of `SessionState`, `SessionSummary`, `ChatState`, `RootState`, `Message`, every `ToolCall*State` and most `chat/*` and `session/metaChanged` actions; it is not a property of `ActionEnvelope`, `ChatSummary`, `AgentInfo` or the chat-input types. Not yet written into `docs/verification/extensions.md`. |
| 2026-10-28 | **E0 census run live against `ahpd` 0.14.0 (LAN VM, protocol 1.0.0); AC-E0.1 and AC-E0.3 done.** Read-only on the wire; every frame decoded; 2 idle sessions, no `action` frame in the 20 s window. `_meta` seen on the root (`ahpd.channels/grants/methods/resourceProviders`, `vscode.*` flags, `hostBuild`, host resources), on customization children (`ahpd.argumentHint`) and on tool calls (`toolKind`, `ahpd.startedAt/endedAt/durationMs`); none on sessions, messages or turns, so no branch or pull request from this host. Dropped by the types: `argumentHint` and the session snapshot's `resource`. Written up in `docs/verification/extensions.md`. **Open:** AC-E0.2 (needs the twelve fields from `ahpc`'s `CONFORMANCE.md`, not in this repo), AC-E0.4 (fixtures), the VS Code host run, and E5. Not committed. |
| 2026-10-28 | **Second `ahpd` census, 60 s with a session in use.** 41 action frames of 18 kinds, all decoded. New `_meta`: `toolKind` and `ahpd.startedAt/endedAt/durationMs` on tool-call actions, `ahpd.cacheWriteTokens` and `ahpd.cost` on `chat/usage`, `ahpd.model` on `root/sessionSummaryChanged`. Still no branch, pull request, progress message or hidden flag. **Correction:** `ActionEnvelope.origin` is nullable in the pinned library, so the census could never show a missing `origin` as undecodable; it now counts envelopes with and without one (`envelope:*`; 11 `ExtensionCensusTest` cases pass). E5 stays open. |
| 2026-10-28 | **Census run against the VS Code host through the tunnel (60 s, 20 sessions, read-only).** 1,383 action envelopes of 24 kinds, none undecodable. **Branch is there**: `_meta.git.{branchName,baseBranchName,defaultBranchName,upstreamBranchName,outgoingChanges,incomingChanges,uncommittedChanges,hasGitHubRemote,…}` on the session list, snapshots, `sessionSummaryChanged` and `session/metaChanged` (`_meta.gitData.<key>.*` for multi-root). **No pull-request field by that name** (candidate: `items[]._meta.<key>[]` with `type`, `link`, `isGitHub`; needs a look at the values). **No progress message and no hidden-from-transcript flag** from either host, so E3 and E4 have no evidence at 1.0.0. `argumentHint` is `vscode.skill.argumentHint` here and `ahpd.argumentHint` on `ahpd`, which is the E1 spelling-list case. Dropped by the types: `_vscodeEditAttribution`, `childEnablement`, `nonce`, config-schema `chatMutable`/`sessionMutable`, `chats[].archived`. `origin`: 7 envelopes with, 1,376 without (host-started actions); E5 still a watch. Protocol 1.0.0 chosen (offered 1.0.0 and 0.9.0), 303 sessions listed; the host's own version is not known (the probe printed `?`). **Open:** AC-E0.2, AC-E0.4. |
| 2026-10-28 | **E1 started: `HostExtras` for branch and counts only.** `SessionExtras(branch, outgoing, incoming, uncommitted, detachedHead)` read from `_meta.git.*` (nested or dotted) and `gitData.<folder>.*`, bounded and never throwing; `SessionSummary.extras` and `SessionState.extras`. 10 `HostExtrasTest` cases; first run caught a real miss (a dotted `git.branchName` key at the top of `_meta` was not read), fixed. 1,249 JVM tests pass. Pull requests, progress message and hidden flag are not built: no host sent them. AC-E1.3 open for an open session's `session/metaChanged`. |
| 2026-10-28 | **Captures taken from both hosts; first redacted fixture (AC-E0.4 partly).** New `tools/redact-meta.mjs` keeps only the git `_meta` (whitelist, branch names replaced, `findLeaks` must pass) where the other redactors drop `_meta` whole; `live/vscode-session-meta.json` holds 12 sessions' shapes from the VS Code host (304 listed), including multi-root `gitData`. `HostExtrasTest` now checks the reader against every one (11 cases pass). Real capture showed `git` is nested and `vscode.*` keys are dotted. `ahpd` (2 sessions listed, 64 frames) sent no git data, so it has no fixture. 12 redaction-tool tests pass. |
| 2026-10-28 | **E2 started: branch only.** A branch label on the drawer row and **Branch** and **Git status** tiles in the Details sheet, from `HostExtras`; nothing shown for a host that sends no git data. No pull-request chip: no host sent one. 1,252 JVM tests pass, app and instrumented tests compile, lint at its 3 baseline warnings. The 3 new instrumented cases pass on the API 36 emulator (57 in `SessionScreensTest`); the Android 17 phone cannot run Compose tests (Espresso `InputManager.getInstance`). The code is in `db8285f`; this note is not committed. |
| 2026-10-28 | **AC-E1.3 and E7 closed; `MonitorWatchdogWorker` cast fixed.** Added `session/metaChanged` store test in `HostExtrasTest` (AC-E1.3 done). Closed E7 (AC-E7.1, AC-E7.2) as not needed because both `ahpd` 0.14.0 and the VS Code tunnel host chose protocol `1.0.0`. Fixed `MonitorWatchdogWorker` and `MonitorWatchdogInstrumentedTest` `applicationContext` cast (`ContextImpl cannot be cast to AhpApp`). |
| 2026-10-28 | **E1 (`PullRequest`, `StepExtras`, `MessageExtras`), E3, E4 and E5 completed (`bbe0032`).** `HostExtras` now reads `PullRequest` (`openableUrl` gated by `SafeLinks.isSafeVerificationUri`), `StepExtras.progressMessage` on running `ToolCallState` (cleaned via `MarkdownPlain` to one plain line, capped at 200 chars), and `MessageExtras.hiddenFromTranscript` (`vscode.chat.hiddenFromTranscript` and `requestHiddenFromTranscript`). `Transcript` and `TranscriptRowModel` show running step progress on the step row and collapsed steps detail (AC-E3.1 to E3.3), and omit hidden user messages and empty hidden turns without affecting paging or `waitingOnYou` (AC-E4.1 to E4.3). `HostSession` surfaces origin-less echo matches to `AhpConn` via `note` (AC-E5.1 to E5.4). |
| 2026-10-28 | **AC-E0.4 completed and `StepExtras` extended for live tool-call `_meta`.** Added `live/ahpd-tool-meta.json` with redacted tool-call `_meta` from `ahpd` 0.14.0 and the VS Code host (`toolKind`, `ahpd.durationMs`, `mcpServerName`, `subagentAgentName`, `subagentDescription`), verified by `findLeaks` in `tools/redaction-check.test.mjs`. Extended `StepExtras` in `HostExtras` with `toolKind`, `durationMs` / `durationText` (completed tool calls only), `mcpServerName`, `subagentAgentName` and `subagentDescription`, and wired `ToolDetail` to fall back to `call.extras.subagentAgentName` / `subagentDescription` when `ToolResultContent.Subagent` fields are blank. |
