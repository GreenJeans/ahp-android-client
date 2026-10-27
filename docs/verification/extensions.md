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

**Not done (still open in E0):** the `--census` option of the probe, the twelve-field comparison (AC-E0.2), the `_meta` property check on the generated types (AC-E0.3) and the redacted `ahpd` fixtures (AC-E0.4). **E5 is not settled:** whether `ahpd` echoes an action with `origin` was not tested, because that needs an action to be sent to the host. The first live run of "Act for me" against `ahpd` answers it: a message that is accepted but reported as "No answer from the host yet" is this case.

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

**E5 (echo without `origin`):** the message and the approval went through against `ahpd`, and the person saw no "No answer from the host yet". That is consistent with `ahpd` sending `origin` on echoes, but it was not checked frame by frame; the census (E0) would settle it.

**Not done:** the instrumented suite was not re-run (the phone was attached, and the README says not to run it there), and the 4 emulator failures noted above are unchanged.
