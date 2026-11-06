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

**Cause of the two failures, from the host log (`<user data>/logs/20261101T062633/agenthost.log`).** Both failed sessions logged, about 1 s after `session.send() returned`, `[warning] [Copilot:<sdk id>] Static system messages and tool definitions exceed the model's usable context budget. Reduce static context or switch to a larger-context model. {"warningType":"compaction_static_context_blocked"}` and then `[error] ... Session error: query - No response was returned.` (15:10:35 / 15:10:36 and 15:13:36 / 15:13:37). Neither failed turn had a `Changing model to:` line. **Correction (found later in `~/.copilot/session-state/<sdk id>/events.jsonl`):** the failed sessions did run on a model, `claude-sonnet-5.5`, set at startup with `"contextTier": null` (`session.model_change`, `source: "startup"`). A working VS Code session (`2ac39c37…`) has `claude-sonnet-5.5` with `"contextTier": "long_context"`, set by an `sdk` model change. The session resent after choosing **Claude Opus 5.5** in the phone's picker got a model change with `"contextTier": "long_context"`, ran `powershell`, `ado-wit_query` and `ado-wit_work_item`, and ended "Done · 21s · 247k tok · $15.00" with the echo output shown. So the likely cause is that Sonnet 5.5 at the standard context tier cannot hold this session's static context, while the long-context tier can. An earlier version of this note said the phone sent no model; that was wrong. The runtime log (`~/.copilot/logs/process-*.log`) had nothing relevant.

**What the host offers (read-only look at the local host's root state, 2026-11-02).** For the Copilot agent's `claude-sonnet-5.5` the model's settings are `thinkingLevel` and `reasoningEffort` (text lists) and `contextSize`, a **number** with the values 200000 and 936000 and the default 936000 (Opus 5.5: 200000 or 1000000, default 1000000). VS Code sends `contextSize`; the phone kept only text settings, so it sent none and the session started at the standard tier. A hint saying "this message named no model" (branch `claude/no-model-error-hint`, deleted) rested on a wrong premise and was dropped.

**Fix and live check (branch `claude/model-numeric-settings`, 641b622).** The picker now offers number settings with a list of whole numbers (the host's labels, "200K" and "1M", are shown), and a message that names a model carries the host's own default for each setting the person left alone, with number settings sent as numbers. On the Pixel 10 a new Copilot session in `skill-packs`, Claude Sonnet 5.5 chosen in the picker and the context size left at its default, ran the same `echo hello` prompt that failed twice before: the reply showed `hello`, "Done · 27s · 245.2k tok", the host log had no `compaction_static_context_blocked`, and the session's event log shows `contextTier: long_context`. The same prompt cost about $1 here against about $15 on Opus 5.5. A first turn where no model is chosen and the host reports none still sends no model, so the host's default applies and can still fail. The new session left a worktree and the branch `agents/terminal-echo-hello-output-25f7c4e5` in `skill-packs`.

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

## 2026-11-03: Pixel 10, N3a attachments against the same host

**Setup.** Debug build of the merged N3a tree (`0b0acf2`) installed over the existing app on the Pixel 10 (sign-in kept). A 27-byte text file (`attach-check.txt`, content "The secret word is PELICAN.") was pushed to the phone's Downloads. A throwaway Copilot session with no folder was started, the file was picked through the system chooser, and a message asking for the secret word was sent with it.

| Check | Result | Notes |
|---|---|---|
| The paperclip opens the system chooser and a picked file shows as a chip | Pass | Chip `attach-check.txt` with a remove mark appeared above the field |
| The send is accepted and the host receives the file | Pass | Host log: `sendMessage called: "..." (1 attachments)`, both times the file was sent |
| The model answered from the file with tools forbidden | Fail, and the test was wrong | Copilot provider, Claude Sonnet 5.5, told to use no tools: "The file's contents weren't included in the message, and I can't open it without tools." The host does not paste the content into the message (see the Claude rows below) |
| Claude provider, text file, tools allowed | Pass | "Use your Read tool on the attached text file": the session ran `Read attach-check.txt` and answered PELICAN |
| Claude provider, image, tools allowed | Pass | `Read attach-image.png` and the answer HERON (the image said HERON) |
| Claude provider, image, tools forbidden | Fail, expected | "The image came through as a file path, not inline, so I can't see it unless I open it with the Read tool" |
| The first send on the host's Default model | Not a result | The turn failed with `compaction_static_context_blocked` (the host's own system prompt and 223 tools exceeded that model's usable context). Unrelated to the file; changing the model fixed it |

**What this shows.** N3a works against a real VS Code host. The send is accepted, the host counts the file, and the model reaches the content: this host saves the attachment and gives the model a file path, not the content inline. A text file and an image both came through that way on a Claude-provider session, and the model read them with its Read tool. A turn with tools forbidden cannot read them.

**What it means for the app.** The model has to be allowed to read files in the session for an attachment to be useful. Nothing in the app can change that; it is how this host presents attachments. The note on the composer or in the spec should not promise that the model "sees" the file in a no-tools or read-only session. Not checked: a Copilot-provider session with tools allowed (the Copilot turn above forbade tools), and whether the image reaches a model's vision when the host passes the file inline.

**Cost note.** The Copilot retry on the oversized default context used 243.4k tokens ($1.00 on the host's meter). The Claude turns used about 150 tokens each.

**Left behind.** Two throwaway sessions on the host ("secret word in file" on Copilot, "identify word in image" on Claude), to be deleted by the host owner.

## 2026-11-03 (evening): Pixel 10, N3b host completions against the same host

**Setup.** Debug build of `claude/n3b-composer-completions` (with `main` merged in), installed over the existing app over wireless adb, sign-in kept. Two existing idle sessions; only typing in the message box, nothing sent. The draft was cleared afterwards.

| Check | Result | Notes |
|---|---|---|
| `@` | App's own list, as designed | The app's agent-mention list ("@dev-advisor", "@dev-assessor"…) took the popup, so the host was not asked; host `@` suggestions show only where no custom agent matches |
| `#` asks the host on the chat channel | Pass | After the pause the host's file suggestions came back (TOUR.md, TEST-PLAN.md, .gitattributes, .env.example in one session; validate-packs.mjs, tune-signals.mjs… in another), so the VS Code host accepts `completions` with the chat as the channel |
| Accepting one | Pass | The text became `#TOUR.md` and a "TOUR.md" chip appeared |
| Typing after accepting | **Fail, fixed** | The cursor stayed after the `#`, so typing on landed inside the mention. The message box now moves the cursor to the end when its text is set from outside (`b7091bd`); rechecked live: `#validate-packs.mjs` then typing gave `#validate-packs.mjszz` |

**Not checked.** What the host's attachment for a file mention holds (it was not sent), the space trigger after a slash command, and a message sent with a mention.

## 2026-11-04: Pixel 10, `main` after P5 and P8

**Setup.** Debug build of `main` at `0672b02`, then of `claude/live-eval-fixes`, installed over the existing app over USB adb, sign-in kept. One "Add folder" was tried on "Idea 9"; nothing else was changed on the host.

| Check | Result | Notes |
|---|---|---|
| P5: Add folder and Remove offered before any refusal | Pass | "Nexus Home phase 2" (two folders) showed "Remove" on the second and "Add folder" |
| P5: a refused add hides Add folder for this host | Pass | Choosing a temporary folder from "Add a recent folder" was refused; the button was replaced by "This host does not let this app add folders. It said: Session working-directory actions require an Editor Window client." |
| P5: a refused remove | Not tried | The host's words cover all "working-directory actions", so a remove is very likely refused too, but trying it on a live session risks removing a folder the person uses |
| P8: host terminals | Not checked | This host lists no terminals in its root state, so the app offers none |

## 2026-11-05: Pixel 10, P9 windowed delivery against VS Code Insiders

**Setup.** VS Code Insiders `1.146.0-insider` (commit `69176e5`, which contains `microsoft/vscode#345886`) on the same PC, with Remote Tunnel Access on as the host "insiders", beside the stable 1.145 host. Debug build of `main` with the wire log's new inbound lines (`claude/p9-live-check`), installed over wireless adb. Read only: seven existing idle Insiders sessions were opened, nothing was sent. The app's debug wire log (`AhpWire`) names windowed delivery's inbound messages by method only.

| Check | Result | Notes |
|---|---|---|
| The host takes the offer | Pass | Every chat opened logged `received a subscribe reply that took windowed delivery`, then `received channel/snapshot` within 1 to 75 ms |
| The chat loads from `channel/snapshot` | Pass | Each chat showed its turns ("Done · 1m 15s", Read, Regenerate, Fork, Side chat), as with ordinary delivery |
| A 1.145 host still works beside it | Pass | Connected to both hosts at once; the stable host's chats load as before (checked 2026-11-05 on its own) |
| Fragments (`channel/frame`) and credit | Not seen | Every snapshot came whole: these chats are far below the 1 MiB frame and the three quarters of a 1 MiB window after which credit is sent. JVM tests cover both |
| A chat over 16 MB (AC-P9.4) | Not run | No chat on the Insiders host is that large |

**Seen in passing.** The phone's home screen still labels the app "AHP Client", though the installed APK's label is "Hostline" (`aapt2 dump badging`). The launcher keeps the name it had when the icon was placed; removing and adding the icon again should show the new name. Worth checking on a clean install before U9.1 is ticked.
## 2026-11-05: Pixel 10, P7 host automations against VS Code 1.145

**Setup.** Debug build of `claude/p7-automations` installed over wireless adb, sign-in kept, "Act for me" on (manage tier). Read only: the sheet was opened and one run's session was opened from it; no switch was flipped and no run cancelled.

| Check | Result | Notes |
|---|---|---|
| The drawer offers Automations | Pass | The button showed for the stable host, which advertises `automations` as Insiders does |
| The catalogue loads | Pass | One `subscribe`; the sheet listed "Nexus Context Snapshot" (Manual only, Off, one run Done Oct 8) and "Morning Session Digest" (Weekdays at 06:00 (America/Los_Angeles), On · Next run in 20h, four runs Done 3h to 3d ago), with a switch on each on the manage tier |
| A run opens its session | Pass | Tapping "Done, 3h" opened "Morning digest of agent sessions", and the catalogue was unsubscribed as the sheet closed |
| Switching one off, cancelling a run | Not tried | Both change the host's real automations; JVM tests cover what is sent and the policy that holds the update to `enabled` |
## 2026-11-05: Pixel 10, P8 host terminals against VS Code 1.145

**Setup.** Debug build of `claude/vscode-terminal-uris` over wireless adb, connected to the stable host and Insiders. Read only.

| Check | Result | Notes |
|---|---|---|
| Host terminals are listed | **Fail, fixed** | No host showed a Terminals button. VS Code names its terminals `agenthost-terminal:/<id>` (one slash, seen in the root state), and the app took only `agenthost-terminal://…` and `ahp-terminal:/…`, so every one was dropped. With the fix the stable host showed "Terminals, 8 on the host", each "Agent Host Terminal, Running". The Insiders host lists none of its own |
| A host terminal opens read only | Pass | Title "Agent Host Terminal", "Running · C:\Users\…\featagent-mesh-v3-remote-mail-r2", "Read only, as plain text.", and the PowerShell prompt. Nothing to type into |
| Back | Pass | Back went to the drawer and the terminal was unsubscribed. Only `subscribe` and `unsubscribe` were sent |
| Output arriving while watched (U7.2, terminal screen) | Not seen | The terminals were idle at a prompt |

## 2026-11-06: Pixel 10, supervised writes against VS Code Insiders 1.146 and stable 1.145

**Setup.** Debug build of `main` (`bf5032c`) over USB adb, "Act for me" on. The tester approved each write beforehand and did the tapping and the approvals on the phone; the app was read over `adb` and the `AhpWire` log. Writes were limited to a throwaway Insiders session and one automation switch.

| Check | Result | Notes |
|---|---|---|
| New session on Insiders | **Fail, fixed** | The first start failed with "working directory no longer exists": the new-session form offered `AppData/Local/Temp/nexus-gate-cli-probe-20261104-054603`, a deleted CLI probe temp folder. `RecentFolders` now leaves out OS temporary folders (`AppData/Local/Temp`, `/tmp`, `/var/folders/…/T`), non-local project URLs (`https://…`), `.claude/worktrees`, `.gemini/antigravity/worktrees`, and `copilot-worktrees`, and host-marked scratch folders (`workspaceless`, `vscode.scratchFolders`), and `HostRefusals` drops any folder the host reports as `workingDirectoryFailed` on the connection. The second, in a folder that exists, started |
| A command's approval | Pass | The phone asked; once approved there, the run went ahead. The approval card in the Insiders window took a moment to clear, which is the host's |
| Streamed output in the chat (U7.2) | Pass | A command printing 1 to 60, a line a second, filled the chat's tool block as it ran: 26 lines at 9 s, 52 at 35 s, 58 at 41 s, 60 at 49 s |
| Insiders' own automations | Empty | "This host has no automations." The two seen from the Insiders window on 2026-11-05 were the stable host's |
| Two hosts connected, focus the other | **Fail, fixed** | With Insiders shown, tapping the stable host's connected card did nothing, so the drawer and Automations stayed on Insiders. With the fix a tap on a connected card that is not shown brings that host to the front without reconnecting, and the shown one reads "Shown now" |
| Switch an automation (P7) | Pass, with a finding | Two `automation/updateRequested` were sent (06:51:42, 06:51:48) and both applied. One tap landed on "Morning Session Digest" rather than the manual-only "Nexus Context Snapshot" and turned its weekday schedule off; it was turned back on from the phone at 06:53:45 (On, next run in 2d, no run missed) |
| Ask before switching one that runs by itself | **Missing, fixed** | Nothing asked before the schedule above was turned off. With the fix, switching an automation with a schedule or an event asks "Turn off …?" (or "Turn on …?") first; a manual-only one switches at once |
| Cancel a run | Not tried | No run was in progress |

**Also on the phone.** Two stray taps from the `adb` driver, before the tester took over the tapping: one landed on the approval card's Skip and denied the first command, and one opened a side chat ("Side chat from turn 1"). Neither is an app fault, but both came from controls close to rows that move while a turn runs (see the 2026-11-04 finding in `ux-feedback.md`).

**Left on the host.** The Insiders sessions "PowerShell command for printing numbers" (the failed start) and "PowerShell command to print numbers" with its side chat, for the tester to remove.

**Not run.** A chat over 16 MB (AC-P9.4), cancelling a run, TalkBack.