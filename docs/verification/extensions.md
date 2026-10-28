# Host extensions and direct hosts: what was seen

Evidence for [`docs/extensions-spec.md`](../extensions-spec.md). Each entry says what was done, on which date, and what was seen. Nothing here holds a token, a session title or a path from a host.

## E0 (part): how `ahpd` is addressed and authenticated

**2026-10-27, `ahpd` 0.14.0 (`@ahpd/server`) on the same Windows laptop, run with `ahpd` and its default `config.json`** (host `127.0.0.1`, port `9187`, a `connectionTokenFile`, the `agent-claude`, `agent-acp` and `web` plugins). Read-only probe from Node 22 (`initialize`, `listSessions`, then closed); no action was sent.

| Question | Seen |
|---|---|
| Transport | Plain WebSocket, `ws://127.0.0.1:9187`, text frames of JSON-RPC 2.0. No Dev Tunnel, no gateway handshake, no `/agent-host/select` path |
| Authentication | With a connection token configured, a connection with none is refused at the HTTP upgrade (not a `101`). Accepted both as `Authorization: Bearer <token>` and as `?tkn=<token>` on the URL. This app sends the header, so the token is never in an address, a log or a history |
| TLS | None. `ahpd` speaks `ws://` itself; `wss://` would need something in front of it (a reverse proxy or a tunnel). Hence the address rules in `HostAddress`: `ws://` only to loopback and private ranges |
| Reachability | It binds to loopback by default, so a phone cannot reach it. For a phone it must be started with `--host 0.0.0.0 --connection-token-file <file>` (the README says binding anywhere else needs a token), or reached through a private-range address such as a mesh VPN. The emulator reaches the laptop's loopback as `10.0.2.2` |
| Protocol version | Offered `1.0.0` and `0.9.0`; the host chose **`1.0.0`** (its README badge says `0.9.0`, so it can speak both). E7 is not needed for it |
| `initialize` reply | `protocolVersion`, `serverSeq`, `serverInfo {name, version}`, and the root snapshot (`agents` with models, `protectedResources`, `customizations`, `capabilities`; `activeSessions`; `config`) |
| `listSessions` | 758 sessions on this machine. Each item: `resource`, `provider`, `title`, `status`, `createdAt`, `modifiedAt`, `workingDirectories`, `chats[]` (`resource`, `title`, `origin.kind`, `interactivity`, `status`), `defaultChat`, `project {uri, displayName}`. No `_meta`, no branch or pull-request fields at this level |
| Notifications in 3 s idle | None |

**Not done (still open in E0):** the twelve-field comparison (AC-E0.2) and the redacted `ahpd` fixtures (AC-E0.4); the census itself and the `_meta` property check on the generated types (AC-E0.3) are below. **E5 is not settled:** whether `ahpd` echoes an action with `origin` was not tested, because that needs an action to be sent to the host. The first live run of "Act for me" against `ahpd` answers it: a message that is accepted but reported as "No answer from the host yet" is this case.

## E0: census of `ahpd` (AC-E0.1)

**2026-10-28, `ahpd` 0.14.0 in a Debian 12 VM on the LAN** (`ws://<VM address>:9187`, token in `AHPD_TOKEN`), `:core-ahp:probe --direct ... --census --census-sessions 15 --watch 20`. Protocol the host chose: **1.0.0** (`serverSeq` 94). Methods on the wire: `initialize`, `listSessions`, `subscribe`, `unsubscribe` (read-only, checked). The report holds paths, types and counts only.

Frames seen: `result:initialize` x1, `result:listSessions` x1, `result:subscribe` x4 (2 sessions and the main chat of each), `notification:root/sessionSummaryChanged` x2. **Every frame decoded with the pinned library; none was undecodable.** The host had 2 idle sessions and nobody used it during the 20 s, so **no `action` frame was seen**: a tool call appeared only inside the two chat snapshots.

**Under `_meta`:**

| Where | Paths (all optional for us) |
|---|---|
| `initialize` result and root snapshot | `_meta.ahpd.channels[]` (`scheme`, `title`, `description`, `grant`, 6 entries); `_meta.ahpd.grants.<name>` (`title`, `description`, `operations[]`, `groups.read[]`, `groups.write[]`, 16 grants: account, agent, automation, chat, config, connector, container, diagnostics, file, plugin, policy, proxy, session, terminal, trust, usage); `_meta.ahpd.methods[]` (`method`, `title`, `description`, `grant`, 23 entries); `_meta.ahpd.resourceProviders.policy` and `.usage` (a JSON-schema `manifest`, `operations[]`, `root`) |
| `initialize` result | `_meta.vscode.ahpSessionUris`, `autonomousAutomations`, `detachedWorktrees`, `removeSessionArtifact`, `getAgentHostSessionStateFile.chat` (all boolean) |
| root snapshot | `_meta.hostBuild.version`; `_meta.vscode.remoteSessions` (boolean); `_meta.vscode.agentHost.resources.{architecture, platform, cpuCount, memoryBytes}` |
| customization children | `_meta.ahpd.argumentHint` (string), on `agents[].customizations[].children[]` in the root snapshot and on `customizations[].children[]` in a session snapshot |
| tool call in a chat snapshot | `_meta.toolKind` (string); `_meta.ahpd.startedAt`, `endedAt` (strings), `durationMs` (number) |

**Not seen under `_meta`, on this host and these sessions:** anything on `SessionSummary` or the session snapshot (no branch, no pull request, no model), anything on a message or a turn (no hidden-from-transcript flag), and any progress message on a tool call. These two sessions ran a shell command and a tool needing approval, with no git context, and `ahpd` is not VS Code; the VS Code host is still to be run.

**Dropped by the generated types, outside `_meta`:** `argumentHint` (string) on the same customization children, and `resource` (string) on the session snapshot's state. Neither is read by anything today.

**Host fact worth keeping:** `ahpd` publishes a catalog of its own methods with the grant each needs (`_meta.ahpd.methods`, `grants`). It is not in the protocol, so nothing may rely on it; it could later tell the app which tiers a host would accept.

**Why E5 is still open:** with no action frame in the window, `origin` on an echo was not seen either way.

### E0: census of `ahpd` with a session in use (2026-10-28)

Same host and version, `--watch 60`, with a session being used from another client during the window. The first attempt was refused (`The host refused the connection token`, a stale token in the shell) and sent nothing; the second connected. Still read-only.

Frames seen: 41 actions of 18 kinds, 11 of them `chat/*` (`activityChanged`, `delta`, `responsePart`, `toolCallStart`, `toolCallDelta`, `toolCallReady`, `toolCallConfirmed`, `toolCallComplete`, `usage`, `turnStarted`, `turnComplete`), `session/*` (`activityChanged`, `chatUpdated`, `customizationsChanged`, `inputNeededSet`, `inputNeededRemoved`, `ready`), `root/activeSessionsChanged`, and 14 `root/sessionSummaryChanged` notifications. **None was undecodable.**

New `_meta` paths, beyond the idle run:

| Where | Path |
|---|---|
| `chat/toolCallStart`, `toolCallReady`, `toolCallConfirmed`, `toolCallComplete` actions | `_meta.toolKind` (string); `_meta.ahpd.startedAt` (not on `Start`); `_meta.ahpd.endedAt` and `durationMs` on `Complete` |
| `chat/usage` action | `usage._meta.ahpd.cacheWriteTokens` (number), `usage._meta.ahpd.cost.amount` (number) and `.currency` (string, on one of three) |
| `session/inputNeededSet` | `request.toolCall._meta.toolKind`, `.ahpd.startedAt` |
| `root/sessionSummaryChanged` | `changes._meta.ahpd.model` (string, 10 of 14) |
| `session/customizationsChanged` | `customizations[].children[]._meta.ahpd.argumentHint` |

Still none: no branch, pull request, progress message or hidden-from-transcript flag, on any frame. `ahpd` puts the model in `changes._meta.ahpd.model` on a summary change, which is an "other name" case for the model field. Dropped outside `_meta`: the same `argumentHint` (now also on `session/customizationsChanged`) and `snapshot.state.resource`.

**E5 for `ahpd`: settled from its source, 2026-10-28** (`@ahpd/server` 0.14.0, `@ahpd/sdk` `dist/host.js` and `dist/host/actions.js`). For every client `dispatchAction`, `ahpd` builds `origin = {clientId: connection.clientId || 'anonymous', clientSeq: <the number sent, else 0>}` and holds it while the action is applied. The echo, a refusal (`rejectionReason`) and every action the dispatch causes (a `chat/turnStarted` makes the turn's own actions) all go out carrying that same origin, and an action that changes nothing still gets an echo. So `ahpd` does not omit `origin`, and several envelopes can share one. `HostSession.route` applies each envelope first and then `resolveDispatch` removes the waiting dispatch by `clientSeq`, so the later envelopes with the same origin find nothing to resolve and are harmless. Whether a different host omits `origin` is still unknown (the VS Code host has not been run). Read from the installed package, not from a wire capture.

**Why this census cannot show it as built.** `ActionEnvelope.origin` is nullable with a default in the pinned library, so a missing `origin` still decodes and was never going to show under "could not decode" (an earlier note here said it would; that was wrong). The census now counts action envelopes `with-origin` and `no-origin` (`envelope:*` under "frames seen"). Even that is only suggestive: a host sends no `origin` on actions it starts itself, and an echo of this app's own action can only be seen by the app. The real test is the app's own `AhpWire` log from the phone run, where the message and approval were `Accepted`; no "No answer from the host yet" appeared.

### E0: census of the VS Code host through the tunnel (2026-10-28)

`--census --census-sessions 20 --watch 60`, VS Code agent sessions in use during the window. Read-only on the wire. The report is `captures/census-vscode.txt` (not checked in). From the run's console: the host chose **protocol 1.0.0** (it was offered 1.0.0 and 0.9.0), the tunnel reported `protocolv6`, and `listSessions` returned 303 sessions (298 idle, 4 in progress, 1 error). The probe printed the server version as `?`, so the host's own version is not known from the console; `_meta.hostBuild.version` is on `initialize` but the report keeps no values.

Frames seen: 1,383 action envelopes of 24 kinds (730 `chat/reasoning`, 273 `chat/delta`, 97 `session/metaChanged`, 66 `session/customizationUpdated`, 66 `chat/usage`, …), 103 `root/sessionSummaryChanged`, 40 `subscribe` results, 26 `notifications/tools/list_changed`, one `initialize` and one `listSessions` (303 sessions carry `_meta`). **None was undecodable.**

What the spec's extension features would read:

| Feature | Seen on this host | Where |
|---|---|---|
| Branch (E2) | **Yes**, as `_meta.git.*` | `branchName`, `baseBranchName`, `defaultBranchName`, `defaultRemoteBranchName`, `upstreamBranchName` (strings); `outgoingChanges`, `incomingChanges`, `uncommittedChanges` (numbers); `hasGitHubRemote`, `hasGitRemote`, `hasBaseBranchChanges`, `isDetachedHead` (booleans). On `listSessions` items, `subscribe` snapshots, `root/sessionSummaryChanged` and `session/metaChanged`. A multi-root session has `_meta.gitData.<key>.*` with the same names instead, one object per folder. |
| Pull request (E2) | **No field by that name.** | The nearest is `items[]._meta.<key>[]` on `listSessions`: objects with `chat`, `id`, `label`, `type`, `uri`, `link`, `isArtifact`, `isGitHub` (5 objects). Whether any of them is a pull request needs a look at the values, which the census does not keep. |
| Progress message (E3) | **No**, in 18 tool starts | Tool-call `_meta` carries `toolKind`, `language`, `mcpServerName`, `mcpToolName`, `subagentAgentName`, `subagentDescription`, `autoApproveBySetting`, `vscode.toolInputContract`. |
| Hidden from transcript (E4) | **No** | There is no `_meta` at all under any message or turn. |
| Echo `origin` (E5) | 7 of 1,383 envelopes carry it | See below. |

Other host-specific paths worth knowing before E1:

- **Same idea, different key.** A customization child's argument hint is `_meta.vscode.skill.argumentHint` here and `_meta.ahpd.argumentHint` on `ahpd`. E1 needs a per-host list of spellings, as the spec already says.
- **Model prices**, on `initialize`'s agent models: `_meta.inputCost`, `outputCost`, `cacheCost`, `cacheWriteCost`, `priceCategory`, `discountPercent`, and `longContext*` variants.
- **Usage**, on `chat/usage`: `_meta.cost` (a number, where `ahpd` sends `ahpd.cost.{amount,currency}`), `copilotUsage`, `quotaSnapshots.{chat,completions,premium_interactions}`, `turnTokenTotals[]`, `vscode.modelCall.*` and `contextAttribution` (a per-entry token breakdown of the context window; 14,777 entries in the snapshots).
- **Session state**: `vscode.chatUsage.<key>`, `vscode.sandboxState`, `vscode.resolvedSandboxPolicy`, `vscode.promptCache.{cacheExpiresAt,modelId}`, `vscode.session.initiator.name`, `workingDirectoryKeys`, `workingDirectoryScopeIds`, `workspaceless`, `vscode.external`.
- **Root, on `initialize`**: `vscode.*` capability flags (`importSession`, `sessionMove`, `readSavedChat`, `detachedWorktrees`, …), `hostBuild.{commit,date,quality,version}`, `vscode.agentHost.resources`, `vscode.agentCustomizationSettings[]`.

Dropped by the generated types, outside `_meta` (all decoded without error):

| Path | Where |
|---|---|
| `_vscodeEditAttribution` (object) | tool-call result content, in actions and snapshots |
| `childEnablement` (object), `nonce` (string) | customizations, in actions and snapshots |
| `chatMutable`, `sessionMutable` (booleans) | config schema properties on `initialize` and `subscribe` |
| `chats[].archived` (boolean) | `listSessions` items (1 chat) |

`chats[].archived` is the one that looks like state a person would want to see (an archived chat); nothing uses it yet.

`notifications/tools/list_changed` is an MCP-style method, not an AHP one. `RootStore.applyNotification` returns `false` for any method it does not handle and `HostSession` ignores that, so these 26 frames do nothing; they are not counted as undecodable.

**E5 on this host: suggestive only.** 7 envelopes carry `origin` and 1,376 do not. The probe sends no action, so the 7 are other clients' actions (the VS Code window using the session), which suggests the host sets `origin` on a client's dispatch; the 1,376 are actions the host started itself, which have no origin to send. Neither says what an echo of this app's own action looks like. The phone's `AhpWire` log is still the test.

Across both hosts: branch data comes only from the VS Code host (`ahpd` sent none), and neither host sent a progress message or a hidden-from-transcript flag. E3 and E4 have no evidence yet.

**Second pair of runs, with `--capture` (same day).** The VS Code run (5 sessions subscribed, 318 envelopes: 11 with `origin`, 307 without) and an `ahpd` run (42 envelopes: 6 with `origin`, 36 without) both decoded whole. New paths against the earlier runs: model-list `_meta` on an agents-changed action (`rowWarning`, `warningText.model_degraded_github`) and `summary._meta` on a session-added notification, nothing about pull requests, progress or hidden messages. `ahpd` again sent no git data. The VS Code capture (304 listed sessions) is the source of `live/vscode-session-meta.json`: `git` is a nested object and the `vscode.*` keys are dotted keys, as the reader assumes.

### AC-E0.3: which generated types carry `_meta`

From the library's sources (1.0.0), checked 2026-10-27:

| Has `_meta` (`Map<String, JsonElement>?`) | Has none |
|---|---|
| `SessionState`, `SessionSummary`, `ChatState`, `RootState`, `Message`, every `ToolCall*State`, most `chat/*` actions, `session/metaChanged` | `ActionEnvelope`, `ChatSummary`, `AgentInfo`, the chat-input types |

So E1 can read `_meta` from the decoded state for sessions, chats, messages and tool calls, and needs the raw frame only for `ChatSummary` and `AgentInfo`. The census lists every path under `_meta` whether or not the type declares it, so it does not say which of those the decoder kept.

**Still to do for AC-E0.2:** compare with the twelve fields in `ahpc`'s `CONFORMANCE.md`, which is not in this repository; the field names must be taken from that document, not from memory. Only `toolKind` is likely to match one of them by name, and that is unconfirmed.

## E6: a directly addressed host

Built 2026-10-27, on the commit after `61247da`.

| Date | Device | Host | What was done | Seen |
|---|---|---|---|---|
| 2026-10-27 | JVM | scripted hosts | `:core-ahp:test` | 1,223 tests pass (about 90 are new: address rules, the saved list, the token vault, the connector, `HostQuirks`, and the controller with direct hosts). Lint at its 3 baseline warnings |
| 2026-10-27 | Emulator API 36 (`ahp-api36`) | none (screens only) | Whole instrumented suite, `tools\run-device-tests.ps1` | 506 tests: the 10 new `DirectHostsUiTest` cases pass (list, connect, remove after asking, add with a hidden token, a refused address stays open with its reason, sign-in skipped for a host by address). 4 failures, none in code this change touches; see below |
| 2026-10-27 | This laptop (JVM probe) | `ahpd` 0.14.0 at `ws://127.0.0.1:9187`, token required | `:core-ahp:probe --args="--direct ws://127.0.0.1:9187"` with the token in `AHPD_TOKEN` | Connected with `Authorization: Bearer`; host chose protocol 1.0.0, server `ahpd 0.14.0`; `listSessions` returned 758 sessions (757 idle, 1 error); only `initialize` and `listSessions` were sent, checked on the wire. Without the `HostQuirks` repair the same run failed at `initialize` (`AgentInfo.description` missing) |

**The 4 emulator failures are not from this change.** Three are `MonitorWatchdogInstrumentedTest` (`ClassCastException: ContextImpl cannot be cast to AhpApp` in the worker), and they fail the same way on the untouched base commit `61247da`, run in a throwaway worktree on the same emulator. The fourth, `InteractionUiTest.hostDefaultSendsNoModelAtAll` (expected `[null]`, got `[]`), passed on the base run, where a different test of the same class (`aModelWithNoSettingChosenSendsJustTheModel`) failed instead: that class is order- or timing-sensitive on this emulator, and neither test is near what changed. Not investigated further.

### AC-E6.6: on the phone, against a LAN `ahpd`

| Date | Device | Host | What was done | Seen |
|---|---|---|---|---|
| 2026-10-27 | Pixel 10, Android 17, debug build of `1102a66` plus the uncommitted "No folder" change | `ahpd` 0.14.0 (`@ahpd/server`) in a Debian 12 VM on the LAN, `ws://<VM address>:9187` (a 192.168/16 address, so `ws://` is allowed), token required, `@ahpd/agent-claude` with Claude Code signed in on the VM, one served path with no sessions yet | Added the host by address and token on the phone, connected, turned on "Act for me" and "look after sessions" for that host, started a session, sent a message, and approved a tool call from the phone with the device check | Connected and listed sessions with no GitHub sign-in. The agent answered and the tool-approval request appeared and was decided from the phone. `AhpWire` for the run: `resolveSessionConfig`, `createSession`, `subscribe`, then a `dispatchAction` for the message and another for the approval, all marked `NOT-READ` as expected with "Act for me" on. The person reported it "worked great"; no "No answer from the host yet" was reported (see E5 below) |

**A finding this run made: a host with no sessions offered no way to start one.** The new-session dialog took its folders only from the host's own sessions, so a fresh `ahpd` showed "No folders yet. Start a session in VS Code first." and Start stayed off. The protocol makes `workingDirectories` (`createSession`) and `workingDirectory` (`resolveSessionConfig`) optional, a workspace-less session, and `ahpd` accepts both missing. The dialog now has "No folder (the host chooses)", chosen by itself when there are no folders; the device check says "No folder: the host chooses". Covered by JVM tests (1,228 pass) and a wire test; the two new `ManageUiTest` cases compile and were not run.

**E5 (echo without `origin`):** the message and the approval went through against `ahpd`, and the person saw no "No answer from the host yet". That is consistent with `ahpd` sending `origin` on echoes, but it was not checked frame by frame. The census cannot settle it (see "E0: census of `ahpd` with a session in use"); a capture of the phone's own run (`--capture` style frames of an echo) would.

**E5, settled 2026-10-28:** a later run did show "No answer from the host yet" for a message the agent then answered. `ahpd` 0.14.0's own source (`@ahpd/sdk` `host/chatactions.js`, "A turn on a session this host is not running yet") explains it: a `chat/turnStarted` for a session that has to be woken is begun after the dispatch has returned, so its echo carries no `origin`. `HostSession.resolveDispatch` now matches an echo without `origin` by `EchoKey`: a turn by its `turnId` on any channel, anything else by its exact JSON on the same channel, oldest dispatch first. Covered by three new `HostSessionDispatchTest` cases (AC-E5.1 to E5.3); the existing cases pass unchanged (AC-E5.4). Not yet re-checked on the phone.

**Not done:** the instrumented suite was not re-run (the phone was attached, and the README says not to run it there), and the 4 emulator failures noted above are unchanged.
