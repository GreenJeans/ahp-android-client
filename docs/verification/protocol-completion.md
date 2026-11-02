# Protocol completion: live verification (N1)

Runs of the P1 to P5 work against a real host. Boxes in `docs/protocol-completion-spec.md` stay unticked until a run below covers them.

## 2026-11-02: Pixel 10, VS Code dev tunnel host

**Setup.** Debug build of `9d7ef88` (the merged tree, same code as `2af07a8`) installed on a Pixel 10 as a fresh install. The tester signed in to their own VS Code dev tunnel host; the host listed hundreds of real sessions. Read-only checks only so far: the app was driven over `adb`, and nothing was toggled, sent or changed on the host.

| Check | Result | Notes |
|---|---|---|
| Host connects through the tunnel and lists sessions | Pass | Connected; session list with branch and change counts rendered |
| Open an existing idle session | Pass | Title, status "Idle", repository and `(+737, -68)` summary shown; transcript renders |
| P2: Customizations sheet loads from a live session | Pass | "252 active or configured items": 0 plugins, 12 MCP servers, 170 skills, 20 instructions, 32 agents, 1 hook, 17 tools |
| P2: MCP servers tab shows real names and states | Pass | Names are readable (`kb-manager`, `ado`, `ado-gap`, `agent-mesh`, `computer-use`) with Stopped or Ready; a `Global` scope tag appears; "View file" is offered |
| P2: a toggle switch is offered | Not exercised | No switch appeared on any MCP server in this session. That matches the rule that agent-level and global customizations cannot be toggled on a session, so the toggle write (`session/customizationToggled`) is still unchecked against a live host |

**Throwaway session (after "Act for me" and "Look after sessions" were turned on).** The "+" new-session button appeared, and the dialog listed the host's agents (Copilot, Claude, Codex) and folders. A session started with Copilot and no folder opened as "Untitled session". The first message ("Run echo hello in the terminal and tell me the output. Do not edit any files.") was accepted and the session titled itself "Terminal echo command output", then ended "Failed · 15s" with "No response was returned. Send your message again to retry." The quick reply "Try again" only fills the draft. This looks like the Copilot agent failing on the host, not a client fault, but the host's side was not inspected, so the cause is unconfirmed. Nothing in P1, P3, P4 or P5 was reached.

**Second throwaway session (Copilot, `skill-packs` folder).** Same outcome: the host accepted the prompt, titled the session "Terminal echo command output", printed "Created isolated worktree for branch `agents/terminal-echo-hello-output`", then ended "Failed · 15s" with "No response was returned." Two identical failures with Copilot on two folders point at the Copilot agent on the host rather than the client, but the host was not inspected. **Side effect on the host:** the new session created a worktree and the branch `agents/terminal-echo-hello-output` in `skill-packs`; both need removing by the owner.

| Check | Result | Notes |
|---|---|---|
| P3: changeset kinds are offered and open against a live host | Pass | A "Changes" button appeared for the session in a repository; its menu listed Branch, Uncommitted, Session and Working Set. Session and Uncommitted opened and showed "No files changed.", which is right because nothing was changed |
| P3: a changeset with files, and terminal command parts | Not run | The agent never produced any work |

**Cause of the two failures, from the host log (`<user data>/logs/20261101T062633/agenthost.log`).** Both failed sessions logged, about 1 s after `session.send() returned`, `[warning] [Copilot:<sdk id>] Static system messages and tool definitions exceed the model's usable context budget. Reduce static context or switch to a larger-context model. {"warningType":"compaction_static_context_blocked"}` and then `[error] ... Session error: query - No response was returned.` (15:10:35 / 15:10:36 and 15:13:36 / 15:13:37). Neither failed turn had a `Changing model to:` line, so the phone sent no model and the host ran the session's default model (the composer chip said "Default model" before the first send). Sessions started from VS Code log `Changing model to: claude-sonnet-5.5` and work. The same session, resent after choosing **Claude Opus 5.5** in the phone's model picker, logged `Changing model to: claude-opus-5.5`, ran `powershell`, `ado-wit_query` and `ado-wit_work_item`, and ended "Done · 21s · 247k tok · $15.00" with the echo output shown. The default model's identity is not in the log, so which model has the too-small budget is unconfirmed. The runtime log (`~/.copilot/logs/process-*.log`) had nothing relevant.

**Client gap this exposes.** A first turn that carries no model lets the host pick its default, and on this host that default cannot hold the session's static context (252 customizations, many MCP servers). The picker shows a model radio as selected, but that value is only what the host reports, not something the turn sends. Candidate fix: send the model the picker shows, or tell the user that "Default model" can fail and name the warning.

**Cost note.** One trivial turn on Opus 5.5 used 247k tokens and about $15, because the whole static context is sent. Choose a cheaper model for checks that need only a trivial turn.

**Working session (Copilot, Claude Haiku 4.5 chosen in the picker, `skill-packs` worktree).** Prompt: create `n1check.txt` containing `hello`, then run `git status` in the terminal.

| Check | Result | Notes |
|---|---|---|
| Turn runs end to end with an explicit model | Pass | Host log: `Changing model to:` line, then `create` and `powershell` tools; the phone shows "Used 2 tools: Create File, Run Shell Command" and the `git status` output |
| P3: Session changeset lists a file the agent created | Pass | "1 file · +2 -0", `n1check.txt`, Added, with a Discard Changes button (not pressed) |
| P3: file diff opens | Pass | `n1check.txt`: "+1 -0 lines", line 1 `+ hello` |
| P3: counts agree | **Host reports +2 for a one-line file** | Header "(+1, -0)", file diff "+1 -0", changeset list "+2 -0". The file on disk is exactly `hello\r\n` (7 bytes, one line). The list and its total come straight from the host's `edit.diff.added` (`ChangesetView.from`), and the diff screen counts lines on the phone with `LineDiff.lines`, which treats a final line break as the end of the last line. So the host's changeset counts the trailing line break as an extra line, and the client shows the host's number unchanged. One file only: not yet seen with a file that has no trailing newline, so "counts the final break" is the likely cause, not a confirmed one. Not a client defect; the number could be corrected only by reading every file |
| P5: Session folders shown | Pass (read only) | Details sheet lists the worktree folder and offers "Add folder"; nothing was added or removed |
| P1: risk assessment and edit preview | Not exercised | The host logged `Requesting confirmation for tool call` and then `Permission response ... result=approve-once` 59 ms later, so the create was approved by the host (trusted worktree) with no card on the phone |
| P3: terminal command header | Not checked | The terminal block was not opened |

**Not yet run**

- P1: a risk assessment and edit preview. Needs an action the host does not auto-approve (a file outside the trusted folder, or an MCP tool call)
- P2: a toggle on a customization the session itself lists, and the host's echo (no switch is offered on this host's customizations)
- P3: terminal command parts in the terminal view
- P4: a canvas and annotations channel (no entry point appeared)
- P5 remove: not tried (the add was refused, so there is nothing to remove)

**P5 add folder (15:36).** Details → Add folder offered a list of the host's recent folders (the phone does not take a typed path). Choosing `c:/Users/Tyler.Morita/source/repos/skill-packs` was refused: the sheet showed "The host refused it: Session working-directory actions require an Editor Window client." The session folder list did not change, and the host log showed no working-directory action being applied. So this VS Code host only accepts the working-directory actions from an Editor Window client, and a phone client is not one. The refusal reason reached the screen unchanged and the app stayed consistent, which is the behaviour AC-P5 asks for on a refusal; the success path (the host echoing the added folder) cannot be exercised against this host. Open question: whether the app should hide "Add folder" on hosts that refuse it, or only report the refusal after a tap. Not decided.

**Observation for the MCP display-name item.** The MCP server names shown in the Customizations sheet are readable, so a `customizationId` for an MCP contributor may already be the server's name on this host. This is not confirmed: no tool call carrying an MCP contributor was seen yet.
