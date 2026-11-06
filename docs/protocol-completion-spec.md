# AHP Protocol Completion & Parity Specification

> **Status:** Drafted 2026-11-01 after PR #30 (`f56aef3`). P1 to P5 are built on `codex/p2-customization-toggles-p3`; see [Execution status](#execution-status).
> **Parent:** [`docs/spec.md`](spec.md). Complements [`docs/extensions-spec.md`](extensions-spec.md), [`docs/hardening-spec.md`](hardening-spec.md), [`docs/navigation-spec.md`](navigation-spec.md), and [`docs/prerelease-monetization-spec.md`](prerelease-monetization-spec.md).

## Overview

Through PR #30 (`f56aef3`), `ahp-android-client` covers Phases 1–5 plus host extensions (`E0`–`E7`), hardening (`H1`–`H8`), navigation (`N1`–`N6`), token usage & cost telemetry, multi-chat read/unread & archive management, background work & canvas chips, annotations summary, and MCP server lifecycle controls (`Start` / `Stop` / `Background`).

This specification audits the remaining surface of `com.microsoft.agenthostprotocol:agent-host-protocol:1.0.0` (AHP 1.0 / 1.1) against the current Android client, defines the implementation design, capability-policy rules, UI flows, and test plans for each remaining area, and establishes a recommended priority and execution order.

---

## Recommended Priority & Implementation Order

| Priority | Slice | Theme | Capability Tier | Size | Why this order |
|---|---|---|---|---|---|
| **P1** | **Slice 1: Pending Tool Call Risk Assessment & Pre-Approval Diff Preview** | Safety & Approval UX | `READ` / `INTERACT` | M | Highest day-to-day value on a phone: shows host safety assessment, MCP contributor attribution, and proposed file diffs *before* the user approves a tool call. Pure read-model + UI work; zero wire-policy changes. |
| **P2** | **Slice 2: Customization Enablement Toggles & Session Server Tools** | Session Configuration | `MANAGE` | M | Completes `CustomizationsSheet` alongside the MCP server controls landed in PR #30 (`f56aef3`): lets users toggle skills, instructions, prompts, agents, hooks, and plugins on/off per session (`session/customizationToggled`) and inspect `session.serverTools`. |
| **P3** | **Slice 3: Per-Chat Changesets & Structured Terminal Command Boundaries** | Multi-Chat & Terminal Fidelity | `READ` | S–M | Surfaces `chat.changesets` when switching chat tabs in multi-chat sessions and upgrades `TerminalScreen` to show `cwd`, exit status, and command boundaries when the host reports `TerminalCommand` parts. |
| **P4** | **Slice 4: Interactive Canvas & Annotations Channels (`CanvasState` & `AnnotationsState`)** | Channel Subscriptions | `READ` / `INTERACT` | M–L | Turns the read-only canvas and annotation summaries from PR #30 into live subscribed channels: tapping a canvas opens its `CanvasState` / URL, and tapping `Annotations` in `DetailsSheet` opens an annotations sheet with comment threads and add/remove actions. |
| **P5** | **Slice 5: Session & Chat Working Directories Management** | Workspace Management | `MANAGE` | S | Surfaces `chat.workingDirectories` alongside `session.workingDirectories` and supports adding/removing/replacing session working directories (`session/workingDirectorySet`, `session/workingDirectoryRemoved`, `session/workingDirectoryReplaced`) from `DetailsSheet`. |
| **P6** | **Slice 6: Protocol Completions (`completions` & `sessionConfigCompletions`)** | Composer & Config Ergonomics | `INTERACT` / `MANAGE` | M | Wires the AHP `completions` RPC into the chat composer (for host-backed `/` and `@` argument completions) and `sessionConfigCompletions` into `NewSessionDialog` for autocomplete config options. |
| **P7** | **Slice 7: Host Automations & Scheduled Runs (`AutomationState` & `AutomationRunLifecycle`)** | Automations | `READ` / `MANAGE` | L | Full UI and state store for AHP automations: viewing scheduled/event-triggered automations and run lifecycles, cancelling a run (`automation/runCancelRequested`), and creating/updating automations (`automation/createRequested`, `automation/updateRequested`). |
| **P8** | **Slice 8: Host Terminals & Interactive Terminal Lifecycle (Opt-In Gate)** | Host Terminals | `MANAGE` (+ separate gate) | L | Lists root-level `RootState.terminals`, supports `createTerminal` / `disposeTerminal` / `terminal/resized` / `terminal/cleared`, and evaluates whether `terminal/input` should remain permanently disallowed or gated behind a dedicated per-command biometric/credential check. |

---

## Execution status

Checked 2026-11-02 on an API 36 emulator and the JVM suite. The boxes in each slice below are ticked only for what was run. A first live run on a Pixel 10 against a VS Code dev tunnel host on 2026-11-02 is in `docs/verification/protocol-completion.md`: customizations, per-chat changesets and file diffs, and session folders load; the working-directory add was refused by the host; the risk and edit preview, the toggle write, canvas and annotations were not reached.

| Slice | State | Not yet verified |
|---|---|---|
| P1 Risk assessment and edit preview | Built, JVM and UI tests | Live host payload: the host auto-approved the one edit tried, so no approval card appeared; a real `customizationId` is shown as the server name, which may be an opaque id (the host's MCP servers have readable names) |
| P2 Customization toggles and server tools | Built, JVM and UI tests | That a live host honours `session/customizationToggled` and echoes it (the live host offered no switch on any customization) |
| P3 Per-chat changesets and terminal commands | Built, JVM and UI tests | Live terminal content with command parts. Checked live: changeset kinds, the session changeset's file list and a file diff. The host's list count was one higher than the file's real line count (a trailing line break) |
| P4 Canvas and annotations channels | Built, JVM and UI tests | Live channel subscribe; system back on the canvas screen has no automated test (no `AppRoot` harness with a host) |
| P5 Working directories | Built, JVM and UI tests | That a live host applies set and remove. The VS Code host refused the add: "Session working-directory actions require an Editor Window client", so the success path cannot be tried against it. Add and Remove are now each hidden after the host's own refusal (see Slice 5, As built) |
| P8a Host terminals, read only | Built, JVM and UI tests; live on VS Code 1.145 (2026-11-05) once VS Code's `agenthost-terminal:/` addresses were accepted | A host terminal whose output changes while watched (the ones seen were idle at a prompt). System back from a host terminal inside `AppRoot` has no automated test (same gap as P4) |
| P7 Host automations | Built, JVM and UI tests; live on VS Code 1.145 (2026-11-05): the catalogue and opening a run's session; (2026-11-06) switching one off and on | Cancelling a run, live |
| P9 Windowed delivery for chats | Built, JVM tests; live against VS Code Insiders 1.146 (2026-11-05): the host took the offer and chats loaded from `channel/snapshot` | Fragments and credit live (no chat on the test host is large enough), and a chat over 16 MB (AC-P9.4) |
| iOS | Not compiled for P1 to P5 or P8a | Kotlin/Native needs a Mac; `commonUi` changes were only reviewed for JVM-only calls |

### Decisions taken in review

- A toggle sends the session's decision first and keeps the existing workspace and global decisions behind it, because `enablement` replaces the previous list and is ordered most specific first.
- A switch appears only on customizations the session itself lists, and not on children of a disabled container: the host's agent-level customizations cannot be toggled on a session, and a child under a disabled container stays off.
- A completed risk assessment whose score cannot be read shows as a warning, never as safe.
- The last comment on an annotation cannot be removed, because the protocol requires each annotation to keep one entry. Removing any other comment asks first. Removing a whole annotation (`annotations/removed`) stays out of the policy.
- A failing command keeps its `[exit code N]` line as well as the header's exit badge, since long output can push the header out of the kept lines.

### Next slice

**N1: Land and prove P1 to P5. Landed (PRs #33 and #34); the live run is partly done, see `docs/verification/protocol-completion.md`.** Still open: P1 (needs an action the host does not auto-approve), the P2 toggle (needs a host that offers a switch), P4 (no entry point found on the live host), the P5 success path (needs an Editor Window client or a host that accepts it) and the terminal command view. Client item from the run, done: new Copilot sessions on `claude-sonnet-5.5` failed with `compaction_static_context_blocked` because the phone dropped the host's numeric `contextSize` setting and sent none; the picker now offers number settings and a message sends the host's defaults (checked live, see the verification record). Done since: a first turn with no model chosen and none reported by the host now names the host's first listed model (see Slice 5, As built). Original text: merge the branch, then run the unverified column above with `:core-ahp:probe` against a live host and on the Pixel 10, recording results in `docs/verification/protocol-completion.md`. Also: resolve the MCP contributor's `customizationId` to the server's display name through `SessionState.customizations`, and add an `AppRoot` back-navigation test for the canvas screen.

**N2: P6a, session config completions. Built, JVM and emulator tests pass; not yet checked against a live host.** Self-contained and MANAGE only:

1. Add `sessionConfigCompletions` to `MANAGE_QUERIES`, with `AhpConnection` and `HostSession` calls beside `resolveSessionConfig`.
2. Stop leaving `enumDynamic` properties out of `SessionConfigForm`; offer their `enum` values as seeds with a debounced search.
3. Accept only a value the host returned, keeping the rule that the phone never invents one.
4. Treat a host that does not implement the query (`-32601`) as "seeds only", not as an error.

As built: a picked value is sent only if the host's next `resolveSessionConfig` answer echoes it, the same rule as for fixed lists. The device check names the chosen value. A dynamic setting with no seeds is still offered. Any other refusal is shown as "Could not search" beside the field.

**N3: P6b, composer completions.** Larger than the table above suggests, so it comes after N2. Every `CompletionItem` carries a required `MessageAttachment`, and `ChatInteractor` and `ChatActions` send no attachments today, so sending them has to be built first. The range offsets are UTF-16 and the only kind defined is `USER_MESSAGE`.

*N3a, sending attachments (built, merged in #37).* A message can carry up to 5 picked files, as `embeddedResource` attachments (images and text, 5 MB each and 8 MB together; these limits are this app's, the protocol sets none). Android has a paperclip button that opens the system document chooser; iOS and the JVM stub have no chooser yet, so they show no button. An image is refused only when the host says the model in use cannot see (`supportsVision == false`); a host that says nothing lets it through. Editing or retrying a message keeps the files it went with. Fork and side chat send words only, so they are refused while files are attached rather than dropping them. Not built: attachment-only messages (a message still needs text), showing a sent message's files in the transcript, shrinking large photos (a 6 MB photo is refused), keeping files across rotation, and host-supplied attachments, which are what N3b's completions return. Live check on the VS Code host (2026-11-03, see `docs/verification/protocol-completion.md`): the host accepted the send and saved each file for the session, and a Claude-provider session read a text file and an image with its Read tool (the model gets a file path, not the content inline, so it must be allowed to read files). Not yet checked: a Copilot-provider session with tools allowed.

*N3b, host completions in the message box (built; JVM and emulator tests pass; not yet checked against a live host).* When `initialize` names trigger characters (`completionTriggerCharacters`, such as `@` and `/`), the word being typed at the end of the message is put to the host as a `completions` request on the chat, after a 250 ms pause, and only if the word starts with a trigger. `completions` is allowed for INTERACT and MANAGE and refused for READ. The suggestions show under the box; choosing one replaces the host's range with its text and adds the host's attachment as a chip. The attachment is kept whole and sent back exactly as the host wrote it, so its `_meta` survives, and a kind this app does not know goes back untouched. A mention whose words the person deletes is dropped from the message; the host's own range inside the attachment is left out once anything before the mention has changed, rather than sent wrong. A host that cannot answer, refuses, or sends an item whose range does not fit the text gives no list and never blocks typing. Limits of this slice: the cursor is taken to be at the end of the text (a mention is not completed in the middle of a message); the host's space trigger (VS Code names one) is honoured only in a message that starts with a slash command and has gone past its name, so the host is asked about a command's arguments and not after every word (decided 2026-11-03); a local slash command or agent mention that matches keeps the popup, so host completions for `/` show only where no local command matches, and the two lists are not merged; at most 20 suggestions. Unchecked: whether the VS Code host expects the chat as the `completions` channel (the request's `channel` is the target, so the chat is used), and what its attachments look like in practice.

P7 (automations) and P8 (host terminals) keep their order after these.

**P8a, host terminals read only (AC-P8.1). Built; JVM tests pass, UI tests not yet run on a device.** The side menu has a Terminals button (next to the inbox) only while the focused host lists terminals in `RootState.terminals`, kept current by `root/terminalsChanged`. It opens a sheet with one row per terminal: its title and whether it runs or how it exited (at most 50, each address once, only terminal addresses). Tapping a row opens the existing `TerminalScreen`, read only, over whatever is on screen, with or without a session; Back (the bar's or the system's) returns there. `AppController.openTerminal` now allows two sources and nothing else: an address a tool result in the open chat names (as before, opened over that chat), or one the host lists in its root state (opened as the host's, on `AppState.Connected.terminal`). The host's terminal is let go of when a session is opened (a notification tap) and when the screen moves to another connected host. Nothing sends input: `createTerminal`, `disposeTerminal`, `terminal/input`, `terminal/resized`, `terminal/cleared` and `terminal/claimed` stay out of every tier, and `CapabilityPolicyTest` says so. Not built: anything of the MANAGE part (clear, dispose). Only the focused host's terminals are listed, not those of other connected hosts.

### Corrections to the slices below

- P6: dynamic config options are marked `enumDynamic: true` on `SessionConfigPropertySchema`, not by a `complete-as-you-type` kind.
- P8: `TerminalInfo` in the root state carries no `cwd` (only address, title, claim and lifecycle), so the list cannot show a folder. The folder shows on the terminal's screen once it is open, from `TerminalState.cwd`.

---

## Slice 1 — Pending Tool Call Risk Assessment & Pre-Approval Diff Preview (P1)

### Problem

When a tool call enters `ToolCallStatePendingConfirmation`, the protocol payload (`ToolCallPendingConfirmationState`) includes:
- `riskAssessment: ToolCallRiskAssessment?`:
  - `ToolCallRiskAssessmentLoading(kind, status)` — host is still evaluating the risk of the tool call.
  - `ToolCallRiskAssessmentComplete(kind, status, reason: StringOrMarkdown?, safety: Double)` — host finished evaluating risk, providing a `safety` score, `status`, and human-readable `reason`.
- `edits: FileEditCollection?` — proposed file edits (`before` / `after` `FileEditSide` and diff counts) that the tool call will make if approved.
- `contributor: ToolCallContributor?` — whether the tool is contributed by an MCP server (`ToolCallContributorMcp` with server/tool metadata) or a client (`ToolCallContributorClient`).

Today, `ToolDetail.from` in [`ToolDetail.kt`](../core-ahp/src/main/kotlin/dev/ahpclient/state/ToolDetail.kt) extracts only `confirmationTitle` and `toolInput` for `ToolCallStatePendingConfirmation`, discarding `riskAssessment`, `edits`, and `contributor`. The user is asked to tap **Approve** or **Deny** without seeing the host's risk warning or the proposed file diff.

### Design

1. **Model (`core-ahp/src/main/kotlin/dev/ahpclient/state/ToolDetail.kt`):**
   - Add `RiskAssessmentView`:
     ```kotlin
     enum class RiskLevelView { LOADING, SAFE, WARNING, HIGH_RISK }

     data class RiskAssessmentView(
         val level: RiskLevelView,
         val statusLabel: String,
         val reason: String? = null,
         val safetyScore: Double? = null,
     )
     ```
   - Add `riskAssessment: RiskAssessmentView? = null` and `contributorLabel: String? = null` to `ToolDetail` (and surface `riskAssessment` on `TranscriptItem.Tool` so the collapsed/unfolded `ToolHeader` can also show a compact risk badge while waiting for approval).
   - Map `ToolCallPendingConfirmationState.edits` (`FileEditCollection`) into `ToolDetail.results` as `ToolResultView.FileEdit` items (reusing the existing `FileEdit` diff preview infrastructure in `ToolResultViews.kt`) so proposed edits are immediately inspectable in the approval block before the tool executes.
   - Extract `contributor` from `ToolCallContributorMcp` as a fallback for `mcpServerName` when `_meta` does not already supply one.

2. **UI (`app/src/main/kotlin/dev/ahpclient/ui/chat/TranscriptRows.kt` & `ToolResultViews.kt`):**
   - In `ToolDetailBody`, when `detail.riskAssessment != null`, render a tonal `Surface` card (`testTag("tool_risk_assessment")`):
     - `LOADING`: spinner + `"Assessing risk…"`
     - `Complete`: status badge + `reason` text (plain, bounded to 400 chars) + formatted safety indicator when applicable, styled with `errorContainer` for high risk / low safety and `secondaryContainer` otherwise.
   - Render any proposed `ToolResultView.FileEdit` entries inside the pending confirmation block so tapping a proposed file edit opens the inline diff viewer before approving.

3. **Capability Policy:**
   - No changes required (`READ` / `INTERACT` unchanged).

### Acceptance Criteria

- [x] **AC-P1.1** A `ToolCallStatePendingConfirmation` with `ToolCallRiskAssessmentLoading` or `ToolCallRiskAssessmentComplete` populates `ToolDetail.riskAssessment` with bounded plain text and renders `tool_risk_assessment` in the expanded tool block.
- [x] **AC-P1.2** A `ToolCallStatePendingConfirmation` with `edits: FileEditCollection` surfaces each proposed file edit as a `ToolResultView.FileEdit` with `before`/`after` diff preview before approval.
- [x] **AC-P1.3** `ToolCallContributorMcp` populates `mcpServerName` on `ToolDetail` and `TranscriptItem.Tool` when `_meta` does not already set one.
- [ ] **AC-P1.4** Unit tests in `ToolDetailTest` and Compose UI tests in `CollapsibleBlocksTest` / `ChatScreenTest` pass on JVM and Pixel 10. (JVM and the emulator pass, 2026-11-04; the UI tests have not been run on the Pixel 10 itself.)

---

## Slice 2 — Customization Enablement Toggles & Session Server Tools (P2)

### Problem

1. In AHP 1.0/1.1, session customizations (`SessionState.customizations`) carry `enabled: Boolean`, `nonce`, and `childEnablement: List<CustomizationEnablement>`, and the client can toggle customizations on or off for a session by dispatching `SessionCustomizationToggledAction` (`type = "session/customizationToggled"`, `id: String`, `enablement: List<CustomizationEnablement>`). Currently [`CustomizationsSheet.kt`](../app/src/main/kotlin/dev/ahpclient/ui/CustomizationsSheet.kt) renders `enabled == false` as a static `"Disabled"` label with no way to toggle it.
2. `SessionState.serverTools: List<ToolDefinition>` (`session/serverToolsChanged`) lists the tools registered on the session (name, displayName, description, inputSchema), which are not surfaced anywhere in `CustomizationsSheet` or `DetailsSheet`.

### Design

1. **Capability Policy (`core-ahp/src/main/kotlin/dev/ahpclient/ahp/CapabilityPolicy.kt`):**
   - Add `"session/customizationToggled"` to `CapabilityPolicy.MANAGE_ACTIONS`. Toggling a session customization is reversible and scoped to the session, matching `session/mcpServerStartRequested` / `StopRequested`.
2. **Action Builder & Manager (`SessionActions.kt` & `SessionManager.kt`):**
   - Add `SessionActions.customizationToggled(id: String, enabled: Boolean, children: List<CustomizationEnablement> = emptyList()): JsonObject`.
   - Add `SessionManager.toggleCustomization(sessionUri: String, customizationId: String, enabled: Boolean): ManageResult`.
3. **Model (`core-ahp/src/main/kotlin/dev/ahpclient/state/Customizations.kt`):**
   - Add `customizationId: String? = null` and `canToggle: Boolean = false` to `CustomizationRow`.
   - Add `CustomizationCategory.TOOLS("Tools", "Server tools")` (or include `SessionState.serverTools` under `CustomizationsIndex`) mapping each `ToolDefinition` to a `CustomizationRow` with `title = displayName ?: name`, `subtitle = name`, `description = description`.
4. **UI (`app/src/main/kotlin/dev/ahpclient/ui/CustomizationsSheet.kt` & `SessionScreen.kt`):**
   - On the `MANAGE` tier, render a Material 3 `Switch` (`testTag("customization_toggle_${row.id}")`) on `CustomizationRowCard` when `row.canToggle && onToggleCustomization != null`.
   - Render the `Tools` category chip when `serverTools` is non-empty.

### Acceptance Criteria

- [x] **AC-P2.1** `CapabilityPolicy.MANAGE` allows `"session/customizationToggled"`, while `READ` and `INTERACT` reject it with `CapabilityDeniedException`.
- [x] **AC-P2.2** `SessionManager.toggleCustomization` validates the customization ID against live `SessionState.customizations` and dispatches `session/customizationToggled`.
- [x] **AC-P2.3** `CustomizationsSheet` renders toggle switches for toggleable customizations on the `MANAGE` tier and lists `SessionState.serverTools` under `Tools`.
- [x] **AC-P2.4** Unit tests (`CapabilityPolicyTest`, `SessionManagerTest`, `SlashCommandsTest`) and UI tests (`SessionScreensTest`) pass.

---

## Slice 3 — Per-Chat Changesets & Structured Terminal Command Boundaries (P3)

### Problem

1. **Per-chat changesets:** `ChatState.changesets: List<Changeset>` (`chat/changesetsChanged`) and `ChatState.changes: ChangesSummary?` report changesets specific to an individual chat in a multi-chat session. Today [`SessionScreen.kt`](../app/src/main/kotlin/dev/ahpclient/ui/SessionScreen.kt) only passes `detail.changesets` (`SessionState.changesets`) to `ChangesChip`, so per-chat changesets on side chats or subagent chats are invisible.
2. **Terminal command detection & metadata:** `TerminalState` carries `cwd: String?`, `lifecycle: TerminalLifecycleState`, `supportsCommandDetection: Boolean?`, and `content: List<TerminalContentPart>` where parts can be structured `TerminalCommand` executions (`terminal/commandExecuted`, `terminal/commandFinished` with command text, cwd, exit code, and output) in addition to raw `TerminalData`. Today [`TerminalView.kt`](../core-ahp/src/main/kotlin/dev/ahpclient/state/TerminalView.kt) flattens terminal output into plain lines and ignores `cwd` and command exit boundaries.

### Design

1. **Per-Chat Changesets (`SessionDetail.kt`, `SessionScreen.kt`, `AppRoot.kt`):**
   - Extract `ChangesetEntry` list and `ChangeStats` from the active `ChatState` (`chat.changesets` / `chat.changes`) in `AppRoot.kt`, merging/deduplicating by URI with `detail.changesets` so `ChangesChip` shows the active chat's changesets when present and falls back to session-level changesets.
2. **Terminal View (`TerminalView.kt` & `TerminalScreen.kt`):**
   - Expose `cwd: String?` (formatted with `displayPath`) and `commands: List<TerminalCommandView>` on `TerminalView`.
   - Show `cwd` in the `TerminalScreen` subtitle alongside the running/exited status (`Running · /repo/app` or `Exited (0) · /repo/app`).
   - When `TerminalContentPart` contains command boundaries, render a subtle command header divider (`$ <command>` and exit badge `Exit 0` / `Exit 1`) between command outputs.

### Acceptance Criteria

- [x] **AC-P3.1** When the active `ChatState` advertises `changesets` not already in `SessionState.changesets`, `ChangesChip` includes them and opens their diff view on tap.
- [x] **AC-P3.2** `TerminalView` exposes decoded `cwd` and structured command boundaries; `TerminalScreen` displays `cwd` in its subtitle and renders command headers when present.
- [x] **AC-P3.3** Unit tests in `TerminalViewTest` / `ChangesetViewTest` and UI tests in `SessionScreensTest` pass.

---

## Slice 4 — Interactive Canvas & Annotations Channels (`CanvasState` & `AnnotationsState`) (P4)

### Problem

PR #30 (`f56aef3`) surfaced `chat.canvases[]` (`CanvasReference`) in `BackgroundWorkChip` / `DetailsSheet` and `session.annotations` (`AnnotationsSummary`) in `DetailsSheet`. However:
- Each `CanvasReference.resource` is a subscribable AHP channel whose state is `CanvasState(instanceId, extensionId, extensionName, canvasId, title, status, url)`, updated by `canvas/stateChanged`. Currently, canvas chips are static text labels and cannot be opened.
- `AnnotationsSummary.resource` is a subscribable AHP channel whose state is `AnnotationsState(annotations: List<Annotation>)`, updated by `annotations/set`, `annotations/updated`, `annotations/removed`, `annotations/entrySet`, and `annotations/entryRemoved`. Currently, the user can see `"3 annotations · 1 unresolved"` in `DetailsSheet`, but cannot read the annotation comments or add/remove an annotation entry.

### Design

1. **Canvas Store & Viewer (`CanvasStore.kt`, `HostSession.kt`, `ChatScreen.kt`):**
   - Add `CanvasStore` folding `CanvasState` with `CanvasReducer` (`canvas/stateChanged`).
   - In `BackgroundWorkChip` (and `DetailsSheet`), make each canvas item tappable (`testTag("canvas_open_${canvas.label}")`): subscribing to or reading the canvas channel shows its `title`, `extensionName`, `status`, and if `url` is a safe web link (`SafeLinks.isSafeWebLink`), opens `LinkConfirmDialog` to launch or share it (or opens `RichViewerModal` if backed by an embedded/host resource).
2. **Annotations Store & Sheet (`AnnotationsStore.kt`, `HostSession.kt`, `SessionScreen.kt`):**
   - Add `AnnotationsStore` folding `AnnotationsState` with `AnnotationsReducer`.
   - Make the `Annotations` tile in `DetailsSheet` tappable (`testTag("details_open_annotations")`), opening an `AnnotationsSheet` that lists each `Annotation` target, resolution status, and its `AnnotationEntry` comment thread.
   - On the `INTERACT` tier, allow adding a comment entry (`annotations/entrySet`) or removing one (`annotations/entryRemoved`).

### Acceptance Criteria

- [x] **AC-P4.1** `CanvasStore` and `AnnotationsStore` decode initial snapshots and apply `canvas/*` and `annotations/*` actions via the protocol reducers.
- [x] **AC-P4.2** Tapping a canvas in `BackgroundWorkChip` or `DetailsSheet` displays its `CanvasState` details and offers to open/share a valid `https://` URL via `LinkConfirmDialog`.
- [x] **AC-P4.3** Tapping the `Annotations` tile in `DetailsSheet` opens `AnnotationsSheet` displaying the session's annotations and their comment entries.

---

## Slice 5 — Session & Chat Working Directories Management (P5)

### Problem

`SessionState.workingDirectories` and `ChatState.workingDirectories` list the workspace folders active for the session and chat, and the protocol defines `session/workingDirectorySet`, `session/workingDirectoryRemoved`, and `session/workingDirectoryReplaced` actions (`chat/workingDirectorySet`, `chat/workingDirectoryRemoved` on chats). Currently `DetailsSheet` lists `session.workingDirectories` as static `Folder` facts, ignores `chat.workingDirectories` when a chat overrides or adds a folder, and provides no way on the `MANAGE` tier to add (from known `RecentFolders`) or remove a secondary working directory.

### Design

1. **Read path (`SessionDetail.kt` & `AppRoot.kt`):**
   - Merge `chat.workingDirectories` into the folders passed to `ChatPane` (for host-file link resolution) and display per-chat working directories in `DetailsSheet` when they differ from `session.workingDirectories`.
2. **Write path (`CapabilityPolicy.kt`, `SessionActions.kt`, `SessionManager.kt`):**
   - Add `"session/workingDirectorySet"` and `"session/workingDirectoryRemoved"` to `CapabilityPolicy.MANAGE_ACTIONS` (restricted to folders already known in `RecentFolders` from the host's sessions, preserving the security invariant that the phone never invents arbitrary host filesystem paths).

### Acceptance Criteria

- [x] **AC-P5.1** `ChatPane` and `DetailsSheet` include `chat.workingDirectories` alongside `session.workingDirectories`.
- [x] **AC-P5.2** On the `MANAGE` tier, `SessionManager` can add a folder from `RecentFolders` (`session/workingDirectorySet`) or remove an existing secondary folder (`session/workingDirectoryRemoved`), refusing any path not offered by the host's session history.

**As built (2026-11-04).** Decisions are in the Decisions table of [`ux-feedback-spec.md`](ux-feedback-spec.md#decisions-2026-11-03).

- **Refusals.** "Add folder" and "Remove" are each offered until the host refuses that action itself (a `Rejected` outcome); then that one is hidden for every session of the host and the host's reason is shown in its place. A refused add leaves Remove offered, and the other way round. A failed send, no reply, or this app's own policy is not a refusal. Kept in memory per host connection (`HostRefusals` on `HostSession`): a new connection to the host starts with nothing refused. Tests: `SessionManagerTest`, `AppControllerMultiHostTest` (JVM); the folder cases in `SessionScreensTest` (on a device, not yet run).
- **A new chat's model.** A chat with no turn starts on the session's model, else on the host's first listed model with its default settings, so its first message always names a model (`ModelOptions.inUse`). Tests: `ModelOptionsTest`, `ChatInteractorTest`.

---

## Slice 6 — Protocol Completions (`completions` & `sessionConfigCompletions`) (P6)

### Problem

AHP defines two read-like completion RPC queries:
1. `completions(CompletionsParams)` — returns inline autocomplete items (`CompletionItem`) for the chat composer (e.g. `@` file/symbol mentions or `/` command arguments).
2. `sessionConfigCompletions(SessionConfigCompletionsParams)` — returns dynamic options for `SessionConfigOption` entries of kind `complete-as-you-type` during session creation (`NewSessionDialog`). Currently [`SessionConfigForm.kt`](../core-ahp/src/main/kotlin/dev/ahpclient/state/SessionConfigForm.kt) skips `complete-as-you-type` config fields and only counts them as unsupported.

### Design

1. **Capability Policy (`CapabilityPolicy.kt`):**
   - Add `"completions"` to `INTERACT` (and `MANAGE`) methods, and `"sessionConfigCompletions"` to `MANAGE_QUERIES` alongside `"resolveSessionConfig"`.
2. **New Session Config Autocomplete (`SessionConfigForm.kt` & `Manage.kt`):**
   - Support `complete-as-you-type` config options in `NewSessionDialog` with a debounced search field that queries `sessionConfigCompletions` and only allows selecting a value echoed by the host.
3. **Composer Completions (`ChatInteractor.kt` & `Interaction.kt`):**
   - Query `completions` on debounced composer prefix changes when the host advertises completion support, merging results with local `SlashCommands`.

### Acceptance Criteria

- [x] **AC-P6.1** (`sessionConfigCompletions` is in `MANAGE` only; `completions` is in `INTERACT` and `MANAGE` and refused in `READ`) `CapabilityPolicy` allows `sessionConfigCompletions` in `MANAGE` and `completions` in `INTERACT`/`MANAGE`, rejecting both in `READ`.
- [x] **AC-P6.2** `NewSessionDialog` renders `complete-as-you-type` config fields and queries `sessionConfigCompletions`.
- [x] **AC-P6.3** (built, not live-checked; a local slash command that matches keeps the popup) Composer surfaces host `completions` suggestions alongside slash commands without blocking typing when the host does not implement `completions`.

---

## Slice 7 — Host Automations & Scheduled Runs (`AutomationState` & `AutomationRunLifecycle`) (P7)

### Problem

The AHP 1.0/1.1 protocol includes a complete automations subsystem:
- `AutomationState`, `AutomationEntry`, `AutomationDefinition`, `AutomationSchedule`, `AutomationEventTrigger`, `AutomationDisableCondition`, `AutomationMisfirePolicy`
- `AutomationRunState`, `AutomationRunLifecycle` (`Pending`, `Running`, `Completed`, `Failed`, `Cancelled`), `AutomationRunOrigin`
- Actions: `automation/createRequested`, `automation/updateRequested`, `automation/set`, `automation/removed`, `automation/runLifecycleChanged`, `automation/runSessionSet`, `automation/runSessionRemoved`, `automation/runPrimarySessionChanged`, `automation/runCancelRequested`.

Today, `ahp-android-client` detects whether a session was spawned by an automation (`SessionSummary.isAutomation` in `HostExtras.kt` for session filtering), but does not subscribe to automation channels or allow viewing, cancelling, or managing automations.

### Design

1. **Read Tier (`AutomationStore.kt` & Automations Sheet/Screen):**
   - Subscribe to automation channels advertised by the host (`AgentCapabilities.automations` / root state).
   - List configured automations (schedule/trigger, enabled state, misfire policy, last/active run status, and links to spawned sessions).
2. **Manage Tier (`CapabilityPolicy.kt`, `SessionManager.kt`):**
   - Allow `automation/runCancelRequested` and `automation/updateRequested` (e.g. pause/enable an existing automation) on the `MANAGE` tier.
   - Gate `automation/createRequested` behind `BiometricApprovalGate` (since creating an automation schedules future agent runs on the host).

### Acceptance Criteria

- [x] **AC-P7.1** `AutomationStore` decodes `AutomationState` and `AutomationRunState` and folds `automation/*` actions via `AutomationReducer` and `AutomationRunReducer`. (Built as a `ChannelStore` on `ahp-automations://` with the library's reducers, and one for a run's channel while a cancellation is answered. JVM tests in `AutomationsTest` and `AppControllerTest`.)
- [ ] **AC-P7.2** Users can inspect host automations, jump to a run's primary session, and (on `MANAGE` tier) cancel an active run or toggle an automation's enabled state. (Built: the drawer's Automations button, shown only when `initialize` names `automations`, opens a sheet. JVM and UI tests. Live on a Pixel 10 against VS Code 1.145 on 2026-11-05: both automations listed with their schedule, state, next run and runs, and a run opened its session. On 2026-11-06, in a supervised run, switching one off and on again was sent and applied; a switch on one that runs by itself now asks first. Not yet tried live: cancelling a run, which needs a run in progress.)

### As built (2026-11-05)

- **Offered by.** VS Code Insiders 1.146 advertises `automations` (`create`, `schedules`, `runCancellation`, history of 50), and so does the stable 1.145 host. Only the stable host has any: the Insiders host's catalogue is empty. (The two automations first seen from the Insiders window were the stable host's, reached through that window's tunnel connection.)
- **Asking first.** Switching an automation that a schedule or an event sets off asks "Turn off …?" before anything is sent; a manual-only one switches at once, since nothing runs by itself either way. Added after a stray tap in the live run turned a weekday schedule off.
- **Channel name.** The protocol names the catalogue `ahp-automations://`; VS Code answers and sends actions as `ahp-automations:`. Both reach the one store (`Automations.canonical`).
- **Action names.** The library's are `automation/updateRequested` and `automationRun/cancelRequested` (not `automation/runCancelRequested` as above). Cancellation is sent on the run's own `ahp-automation-run:` channel, held open only until the host answers.
- **Policy.** Both are `MANAGE` actions. The update is held by `CapabilityPolicy.checkContent` to `changes: {"enabled": <boolean>}` alone, so it can never rewrite an automation's prompt, session or triggers. Creating and removing an automation, and starting a run, are not offered.
- **Shown.** Per automation: title, its triggers in words ("Weekdays at 06:00 (America/Los_Angeles)", "Manual only", or the cron expression as written), on or off with the next run, and its latest five runs with status and age. A run with a session opens it; a waiting or running run has "Cancel run", asked about first.

---

## Slice 8 — Host Terminals & Interactive Terminal Lifecycle (Opt-In Gate) (P8)

### Problem

- `RootState.terminals: List<TerminalInfo>` (`root/terminalsChanged`) lists terminals open on the host outside of a single tool-call result.
- AHP also defines `createTerminal`, `disposeTerminal`, `terminal/resized`, `terminal/cleared`, and `terminal/input`.
- By explicit security design ([`docs/spec.md`](spec.md)), `terminal/input` is currently disallowed at every tier because raw shell input bypasses agent tool-call confirmation.

### Design

1. **Read-Only Host Terminals (`RootState.terminals`):**
   - Surface `RootState.terminals` in `DetailsSheet` / host drawer when the host advertises active terminals, allowing the user to watch them read-only in `TerminalScreen` using the existing `AhpConnection.subscribeTerminal` path.
2. **Terminal Lifecycle (`MANAGE` tier):**
   - Optionally allow `terminal/cleared` and `disposeTerminal` on `MANAGE` tier (with confirmation + `BiometricApprovalGate` for `disposeTerminal`).
   - Keep `terminal/input`, `authenticate`, `session/activeClientSet`, and `resourceWrite|Delete|Move|Copy` **disallowed** unless a dedicated Tier 4 (`TERMINAL_EXEC`) security review and per-command biometric gate are explicitly approved.

### Acceptance Criteria

- [x] **AC-P8.1** `RootState.terminals` can be opened read-only in `TerminalScreen` without enabling `terminal/input`. (JVM tests; `HostTerminalsUiTest` passes on the emulator, 48/48 with `TerminalUiTest` and `AccessibilityTest`. Live on a Pixel 10 against VS Code 1.145 on 2026-11-05, after a fix: VS Code names its terminals `agenthost-terminal:/<id>`, with one slash, which the app had refused, so every host showed none. With it, "Terminals, 8 on the host" listed them; one opened read only with its folder and prompt, and back let it go. Only `subscribe` and `unsubscribe` were sent.)
- [x] **AC-P8.2** `terminal/input`, `authenticate`, `session/activeClientSet`, and `resourceWrite|Delete|Move|Copy` remain blocked in `CapabilityPolicyTest` across `READ`, `INTERACT`, and `MANAGE`. (JVM only; the same test also refuses `createTerminal`, `disposeTerminal`, `terminal/resized`, `terminal/cleared` and `terminal/claimed` on every tier.)

## Slice 9 — Windowed Delivery for Chats (P9)

### Problem

- A long chat cannot be loaded: the host sends a chat's snapshot as one message, and the dev tunnel carries at most 16 MB in one message (`ChatNotLoadedException`).
- AHP's [windowed delivery](https://github.com/microsoft/agent-host-protocol/blob/main/docs/specification/windowed-delivery.md) lets a client offer `flowControl` on `subscribe`. The host then sends large messages as `channel/frame` fragments and waits for `channel/credit` before it sends more than a window. VS Code 1.146 (`microsoft/vscode#345886`) accepts it for every state channel but the root, with a 1 MiB window and frames up to 16 MiB. Hosts that are not offered it keep ordinary delivery.
- The 1.0.0 Kotlin library has no types for it, so the messages are written and read as JSON here.

### Design

1. **Chats only.** Chat subscriptions offer `flowControl.receive` (window 4 MiB, frames up to 1 MiB; the host may lower both). Sessions, the root and terminals keep ordinary delivery: they are small, and the root is never windowed.
2. **Accepted or not.** A result with `snapshot` and no `flowControl` is ordinary delivery, as today. A result with `flowControl` and no `snapshot` starts the window, registered as the reply is read, before any frame for the channel can be. The chat's state then arrives as a `channel/snapshot` data message. Limits above the offer, or a frame limit under 1024, are refused: the chat is unsubscribed and shown as not loaded.
3. **Counting.** Every data message on a windowed chat counts its received text as UTF-16 bytes (two per UTF-16 unit, measured before parsing), and so does every `channel/frame`. `channel/credit` and `unsubscribe` are control messages and are not counted.
4. **Fragments.** `data` pieces are joined in order and parsed only on `final: true`. An empty piece, a data message in the middle of a fragmented one, a joined message for another channel, or one past 64 MiB of text is a broken channel: it is unsubscribed and shown as not loaded, and the connection stays up.
5. **Credit.** Data is released as it is handed on to be applied. Credit (`consumedBytes`, a running total) is sent once three quarters of the window is uncredited, fragments included, so the host never waits on a whole message.
6. **Sending.** A `dispatchAction` on a windowed chat counts against the host's limits. It waits (up to the dispatch timeout) while the host's window is full, and is refused, before it is sent, when larger than the host's frame limit. Messages are not sent in fragments.
7. **Reconnecting.** Chats are not named in `reconnect`; each is subscribed afresh on the new connection, as today, so its counters start at zero and recovery is by a new snapshot. Frames for a channel that is no longer windowed, such as after `unsubscribe`, are ignored.
8. **Policy.** `channel/credit` is allowed on every tier: it is needed to read.

### Acceptance Criteria

- [x] **AC-P9.1** A chat whose host accepts windowed delivery loads from a `channel/snapshot` sent whole or in fragments, and follows later actions, with credit sent as specified. (JVM tests in `WindowedDeliveryTest` and `HostSessionChatTest` with a scripted host; not yet against a real one, see AC-P9.4.)
- [x] **AC-P9.2** A host that does not offer it, or a 1.145 host, behaves exactly as before. (JVM tests; live on a Pixel 10 against VS Code 1.145 on 2026-11-05: a chat opened and showed its turns.)
- [x] **AC-P9.3** Broken fragments or limits mark only that chat not loaded; the connection and other chats carry on. (JVM tests.)
- [ ] **AC-P9.4** Live: a chat over 16 MB loads through a 1.146 or Insiders host. (Needs that host.)