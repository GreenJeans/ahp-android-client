# UX feedback roadmap (U1 to U9)

> **Status:** Drafted 2026-11-03 from a round of user feedback. Nothing here is built. Each item names what the app does today, so the gap is clear, and gets detailed steps when it is picked up.

## Overview

Nine items from using the app day to day. Most are about keeping context in a long chat and making the message box less crowded. Two are product decisions (what Pro covers, and the app's name) and need an answer before any code.

| # | Item | Theme | Size | Needs a decision first |
|---|---|---|---|---|
| U1 | Keep chats cached, so returning to one does not reload it | Speed | M | No |
| U2 | Find your place in a long chat: jump to your last message, keep it in view, jump to the start of the agent's reply, a reader view | Reading | M | No |
| U3 | A smaller Suggest button, out of the chip row | Message box | S | No |
| U4 | Chips that scroll, and a pass on what each one says and where it lives | Message box | S–M | No |
| U5 | Rework how side chats, forks and sub-chats are shown, instead of one tab each | Navigation | M–L | Yes: the shape of the chat switcher |
| U6 | Word wrap on or off where long lines are cut or scroll sideways | Reading | S | No |
| U7 | Terminals and host output that stream as they arrive | Live output | M | No, but needs a live check first |
| U8 | AI quick replies behind Pro | Monetization | S | Yes: confirm what "AI completions" means |
| U9 | A new name now that there is an iOS app | Brand | S (code), L (store) | Yes: the name |

## U1 — Chat cache

**Feedback.** Chats are reloaded from scratch when you go back to them.

**Today.** The chat on screen and up to three others you looked at in the same session stay subscribed (`MAX_WARM_CHATS = 3`, `core-ahp/.../app/AppController.kt:1688`), so going back to one of those is instant. Any other chat, and every chat after you leave the session, is subscribed afresh and asks the host for its last 10 turns (`CHAT_SNAPSHOT_TURNS = 10L`, `core-ahp/.../state/HostSession.kt:873`). Until that arrives the screen has nothing to show.

**Proposal.** Keep the last known state of recently opened chats in memory, bounded by count and size, beyond the subscribed few. Opening a cached chat shows it at once, marked as not yet current, and the fresh snapshot replaces it when it arrives. Whether to also keep it on disk (so it survives the app being closed) is a later step: the session list cache in [`hardening-spec.md`](hardening-spec.md) is the model, and the same privacy rule applies (nothing that would not be shown on screen anyway).

**As built (2026-11-03, memory only).** When a chat stops being subscribed, `HostSession` keeps what it showed (its turns and its own settings) for the last 6 such chats. Opening one of them again shows that copy at once; `ChatStore.cached` is true and the screen says "Showing this chat as you last saw it. Updating…" until the host's snapshot replaces it. Nothing is folded into the copy: actions that arrive meanwhile wait for the snapshot, as before, and no older turns are asked for from it. While it is a copy there is no message box and no approval buttons, and `ChatInteractor` treats the chat as not there, so a decision from a notification cannot act on it either. A chat the host said is gone is not kept, and stopping the session (switching host, signing out) empties the cache. Not built: keeping it on disk.

**Acceptance criteria**

- [ ] **AC-U1.1** Going back to any of the last N opened chats shows its turns with no blank wait, then updates to the host's snapshot without the scroll position jumping. (Half checked: shown at once and replaced, in JVM tests and live on a Pixel 10 at the newest row, see `docs/verification/ux-feedback.md`; a chat left scrolled up is not checked.)
- [x] **AC-U1.2** A cached chat is shown as possibly out of date until the snapshot arrives, and a chat the host removed is not shown from the cache.
- [x] **AC-U1.3** The cache has a fixed bound and is cleared on sign-out and on switching host.

**Risks.** A cached chat that is wrong (a turn the host has since truncated or restored) must never be acted on: the message box and approval buttons stay off until the snapshot arrives.

## U2 — Finding your place in a long chat

**Feedback.** It is easy to lose the thread, especially after coming back from another session or after a long time. Agent replies can be long.

**Today.** The chat has one jump button, to the newest item (`ChatScreen.kt:416`), and follows new output while you are at the bottom (`ChatScreen.kt:361`). There is no way to jump to your own last message or to the start of the agent's last reply, and no full-screen reading view.

**Proposal.**

1. **Your last message, kept in view.** A slim bar at the top of the chat shows the start of your most recent message (one or two lines, cut with an ellipsis) whenever that message has scrolled out of sight. Tapping it jumps to the message.
2. **Jump to the start of the agent's last reply.** Next to the existing jump-to-newest button, a second one that goes to the top of the latest reply, not its end.
3. **Reader view.** A full-screen view of one agent reply: larger text, comfortable line length, no chrome, the same markdown. Opened from the reply's menu.

**As built (2026-11-03, parts 1 and 2).** `TranscriptLandmarks` (core, JVM-tested) finds the latest message and the first row after it in the rows the list shows. The bar shows the message's first two lines at the top of the chat once its top edge has scrolled out of view, and only when the agent has started replying (a message still waiting is the newest row and needs no reminder); tapping it brings the message's first line to the top. A round button at the bottom right, above the message box when that is showing, goes to the first line of the reply. It shows whenever that line is out of view, and keeps working while the reply arrives. Rows that come on screen grow a frame later as their text lays out, so a jump measures again after each frame until the place holds. Reader view (part 3, built the same day): a "Read" button beside the duration on the row that ends each turn, offered only on turns that wrote text. It opens that turn's text, without the steps between them, in the full-screen viewer, answer first: the last text part, with the notes written on the way folded under it ("Show 3 notes from while it worked"), decided after the live check showed long turns burying the answer; about 12% larger text with more room between lines, at most 640 dp wide. Links go through the same confirmation as in the chat. The chat now stays composed under any full-screen view (this one, tables, code, diagrams), hidden from screen readers while covered, so closing the view finds the chat exactly where it was and the message box keeps its draft.

**Acceptance criteria**

- [x] **AC-U2.1** When your last message is off screen, a bar shows its start; tapping it scrolls to it. The bar is hidden when the message is visible and when you have not sent one.
- [x] **AC-U2.2** A control jumps to the first line of the agent's latest reply, and works while the reply is still streaming.
- [x] **AC-U2.3** Reader view opens any agent reply full screen and closes back to the same scroll position.
- [ ] **AC-U2.4** All three read correctly with TalkBack and at the largest font size. (Half checked: at twice the font size, `atTheLargestFontSizeTheBarStaysShortAndTheControlsStayUsable` passes on the emulator; spoken labels are tested; TalkBack itself not run.)

## U3 — The Suggest button

**Feedback.** The assistance button competes for space with the other chips. Make it smaller and move it to the text field's row, or above it.

**Today.** "Suggest" is a full-height chip (`quick_reply_suggest_button`, `Interaction.kt:1367`) in the same row as the slash commands, agent and model chips.

**Proposal.** An icon-only button (keeping its 48 dp touch target and its "Suggest quick replies" label for screen readers) at the leading or trailing edge of the text field, or on the quick-reply bar when that bar is showing. Decide between the two placements with a quick mock at phone width before building.

**Acceptance criteria**

- [x] **AC-U3.1** The Suggest button is no longer in the chip row, its touch target is still at least 48 dp, and its spoken label is unchanged.
- [x] **AC-U3.2** At 360 dp wide, with the agent and model chips showing, nothing in the message box is cut off. (Checked at 320 dp: the row scrolls and send stays in view.)

## U4 — Chips: scrolling, wording and placement

**Feedback.** Make the chips scrollable, and take a design pass: what text each shows and where each lives.

**Today.** Below the text field one row holds the slash commands chip, the agent chip (capped at 150 dp, `Interaction.kt:1397`) and the model chip (which takes the remaining width, `Interaction.kt:1436`). On a narrow phone the model name gets cut short. Mode and approvals are set inside the agent sheet (`Interaction.kt:392`), so they are not visible from the message box. The quick-reply chips are a separate bar.

**Proposal.** A design pass first, written down as a short table: each chip, what it says (short form, for example the model's short name and the agent's name without its path), what it says to a screen reader, and where it lives. Then the row scrolls sideways when it does not fit, with the most used chip first. The pass also decides whether mode and approvals, now hidden in the agent sheet, get a chip of their own.

**Design pass (2026-11-03, with U3).** The message box, from top to bottom: attached files, the text field, then one row. The text field carries the Suggest button as its trailing icon. The row has a part that scrolls sideways (the controls that set up the message) and a fixed part on the right (steer, send options, send), so sending is never scrolled away.

| Control | Where | Shows | Spoken | Change |
|---|---|---|---|---|
| Attach | Scrolling row, first | Paperclip icon | "Attach files" | None |
| Suggest | Text field, trailing icon, only while quick replies can be asked for | Sparkle icon | "Suggest quick replies" | Was a "Suggest" chip in the row (U3) |
| Slash commands | Scrolling row | `/` | "Slash commands" | None |
| Agent and chat options | Scrolling row | The agent's name; "Chat options" when the host offers no agents but does offer mode or approvals | "Agent: …" or "Chat options" | Was "Default agent" even when the host had no agents to pick from |
| Model | Scrolling row, last | Model name and any settings ("Claude Sonnet 5.5 · Thorough"), cut at about 200 dp | "Model: " and the whole label | Was squeezed into the width left over |
| Steer now, send options, send | Fixed, right | As today | As today | None |

Kept as they are: the order (attach, then the controls that change what the message is sent with), and mode and approvals inside the agent sheet. A chip of their own is left for later, once it is clear how often they are changed per message.

**Acceptance criteria**

- [ ] **AC-U4.1** The chip row scrolls sideways when it is wider than the screen; no chip is cut off mid-word. (Half done: the row scrolls; a model label longer than about 200 dp still ends in an ellipsis on screen, though it is spoken whole.)
- [x] **AC-U4.2** Each chip's text follows the table from the design pass, and its spoken description names what it is ("Model: …", "Agent: …").
- [x] **AC-U4.3** The design pass is recorded in this spec before the code changes.

## U5 — Side chats, forks and sub-chats

**Feedback.** One tab per sibling or sub chat is unwieldy, and most of them do not matter to the person. Rework how they are shown: a chat switcher, a hierarchy.

**Today.** Every chat in a session is a tab in a scrolling tab row (`SessionScreen.kt:369`), with a dot for unread. Forks, side chats and the main chat look the same.

**Proposal.** The main chat is the session; other chats are reached from one control (for example "3 other chats") that opens a switcher. The switcher groups chats by how they came about (forks of a turn, side chats, chats the agent started), shows them as a tree under the turn they came from, and puts any that need you at the top. The transcript can also show a small link at the turn a fork or side chat came from. Chats that only the agent uses are collapsed by default.

**Decision needed.** The shape of the switcher (a sheet, a drawer section or a dropdown) and which chats count as "not relevant to the person". Mock two options before building. (Decided 2026-11-03: a bottom sheet, see Decisions.)

**As built (2026-11-03).** The tab row is gone. Under the bar there is a chip with the main chat's title (tap it to go back to the main chat) and one control, "2 other chats", which opens a bottom sheet; with only the main chat neither is drawn. When another chat is on screen the control shows its title too ("Try plan B · 2 other chats"). `ChatSwitcher` (core, JVM-tested) builds the sheet from the host's chat list: the main chat first, then groups in this order: Waiting on you, Forks, Side chats, Started by you, Other chats, Chats the agent uses. The relation comes from `ChatSummary.origin` (`fork`, `sideChat`, `tool`, `user`); a chat with no origin, or one of a kind this app does not know, goes under "Other chats" with no relation line. Any chat waiting on the person goes in the first group whatever its relation, so nothing that needs them is folded away. "Chats the agent uses" holds chats a tool call started (origin `tool`, for example a sub-agent) and chats the host marks `read-only` (`ChatSummary.interactivity`); it starts folded, and its heading still says how many chats it holds and how many are unread. Within a group a chat sits under the chat it came from, one step further in (a fork of a fork). The relation line says "Fork of turn 3" only when the app holds every turn of the chat it came from (the one on screen or one of the few kept warm, with no older turns left to load); otherwise it names the chat ("Fork of the main chat", "Fork of Try plan B"). Not built: the link in the transcript at the turn a fork or side chat came from.

**As built, revised (2026-11-04, after review of the first build).**

- *The control's dot means "waiting on you" only.* The "N other chats" control carries a dot only when some other chat is waiting on the person, and its spoken description says only that ("3 other chats, 1 waiting on you"). Unread no longer lights the control or appears in its description. Unread still shows per chat: a quieter dot on the chat's row in the sheet, "Unread" in the row's spoken description, and the unread count in the heading of the folded "Chats the agent uses" group. The main chat's chip is unchanged: it still carries a dot when the main chat is waiting or unread.
- *Hidden chats are their own group, left out by default.* Chats the host marks `hidden` no longer go in "Chats the agent uses". They make up a last group, "Hidden chats", which is not listed until the person turns on the "Show hidden chats (N)" toggle at the end of the sheet (N is how many are left out; with the toggle on it reads "Show hidden chats" and its state is On). The "N other chats" label counts only the chats the sheet lists; a session whose only other chats are hidden still gets the control, labelled "1 hidden chat", so they can be reached. The toggle is held by the session screen, as "Show archived chats" is, and is not saved on the device.
- *A waiting chat is never left out.* A hidden chat that is waiting on the person is listed under "Waiting on you" whether hidden chats are shown or not, and is not counted in the toggle's N. An archived chat that is waiting on the person is listed under "Waiting on you" even with "Show archived chats" off; other archived chats stay hidden as before, and the menu's "Show archived chats (N)" no longer counts the waiting ones.
- *What can be on screen follows the sheet.* A chat stays on screen only while the switcher lists it (the main chat and the listed others), so turning off archived or hidden chats while one of them is shown goes back to the main chat, as archived chats did with the tabs; a waiting archived or hidden chat stays.

**Acceptance criteria**

- [ ] **AC-U5.1** A session with many chats shows one control instead of a tab per chat; the main chat stays one tap away. (Built; UI tests written in `SessionScreensTest` and `AccessibilityTest`, not yet run on a device.)
- [ ] **AC-U5.2** The switcher shows each chat's relation (fork of turn N, side chat, started by the agent) and puts chats waiting on the person first. (Half checked: the grouping, order, tree and wording are covered by JVM tests in `ChatSwitcherTest`; the sheet's UI tests are written, not run. The turn number shows only when the parent chat's turns are all held.)
- [ ] **AC-U5.3** Unread and needs-input signals are not lost compared with today's tab dots. (Half checked: the counts and marks are covered by JVM tests, including a folded group; the dots on screen are in UI tests written, not run. Unread now shows on rows only, by decision; a waiting chat is never left out by the archived or hidden filters, also JVM-tested.)
- [ ] **AC-U5.4** Chats the host marks hidden are left out until "Show hidden chats (N)" is turned on, then listed in their own group; a hidden or archived chat waiting on the person is always listed under "Waiting on you". (Half checked: JVM tests in `ChatSwitcherTest`; the toggle's UI tests in `SessionScreensTest` and `AccessibilityTest` are written, not run.)

## U6 — Word wrap on or off

**Feedback.** Offer word wrap on and off where it makes sense.

**Today.** Code blocks and tables in markdown (`MarkdownPart.kt:298`, `:381`), tool output (`ToolResultViews.kt:264`) and the file viewer (`RichViewer.kt:338`) keep long lines on one line and scroll sideways, with no way to wrap them. The terminal screen and the file diff do not scroll sideways, so they offer no way to see a line unbroken.

**Proposal.** A wrap toggle on each of those views, remembered per kind of view (code, tool output, file, terminal, diff) on the device. Prose always wraps.

**As built (2026-11-03).** One icon button, 48 dp, labelled by what a tap does: "Wrap long lines" while lines are kept whole, "Keep lines whole" while they wrap (tinted then); test tags `wrap_toggle_code`, `_tool_output`, `_file`, `_terminal`, `_diff`. Where it is: in the header of each code block (and of a diagram's card while its source is shown), in the action bar of the full-screen code and file viewer (an expanded code block counts as code, a host file or text attachment as file, a diagram's source as code), in the top bar of the terminal screen and the file diff once there are lines to show, and under every block of tool output (a result's text and a terminal result's output), whether or not a line runs past the edge, so it is in the same place on every result (changed 2026-11-04, see Decisions; it used to show only when a line did not fit). A tool's input values and the short text preview of an attachment keep the old rule: the button is offered only when one of their lines does not fit, so a short value carries no control it has no use for. Wrapping removes the sideways scroll; keeping lines whole brings it back. On the terminal and diff, lines kept whole make the whole list as wide as its widest line (measured from the 20 longest), so it scrolls sideways as one block and rows stay lined up. The choice is kept per kind of view in `UiSettings` (`lineWrap`, saved as `wrap_<kind>` in the same preferences as text size and theme) and provided to every screen from `AppRoot`, so wrapping one code block wraps every code block, and it survives a restart. Defaults keep today's behaviour: code, tool output and files keep lines whole, the terminal and diff wrap. Markdown tables (added 2026-11-04): their cells already wrapped inside columns up to 320 dp, but a table with many columns scrolled sideways. The table card's header now has a "Fit columns to width" button (48 dp icon, test tag `fit_columns_toggle`, labelled "Keep column widths" and tinted while on). On, the table is as wide as the card and its columns share that width in the same proportions as their own widths, so cell text wraps and there is no sideways scroll; off, each column keeps its own width and the table scrolls sideways as before. The choice is one more kind of view in the same store (`WrapView.TABLE`, saved as `wrap_table`, off at first), so fitting one table fits every table and it survives a restart; it does not touch the code wrap. The full-screen table viewer (Expand) is unchanged: it keeps column widths and has its own zoom. Checked: JVM tests (`UiSettingsLineWrapTest`, 4; the whole `:core-ahp:jvmTest` run, 1460, all pass) and the app and its UI tests compile. The UI tests are written but not yet run on a device: `ChatScreenTest` (code block, shared choice and restart through the settings, expanded code, file viewer, a wide table fitted and back, the fit choice shared across tables and kept through the settings), `CollapsibleBlocksTest` (tool output: a long line, output that fits still offering the button, input values offering it only when too long), `TerminalUiTest`, `DiffUiTest`, `UiSettingsInstrumentedTest`.

**Acceptance criteria**

- [ ] **AC-U6.1** Code blocks, tool output and the file viewer offer a wrap toggle that removes the sideways scroll; the terminal screen and the file diff offer the opposite (keep lines whole and scroll sideways). (Built; UI tests written, not yet run.)
- [ ] **AC-U6.2** The choice is remembered per kind of view across restarts. (Checked in JVM tests against the settings store; the UI test through real preferences is written, not yet run.)
- [ ] **AC-U6.3** A markdown table's card offers "Fit columns to width": on, the columns share the width and cells wrap with no sideways scroll; off, today's column widths and sideways scroll. The choice holds for every table and across restarts. (Built; JVM-tested in the settings store; UI tests written, not yet run.)
- [ ] **AC-U6.4** Every block of tool output shows the wrap button, including output that fits. (Built; UI test written, not yet run.)

## U7 — Live terminals and host output

**Feedback.** Terminals and host output should stream better, not show as snapshots.

**Today.** An opened terminal is subscribed and folds each update the host sends (`HostSession.openTerminal`, `HostSession.kt:426`, through `terminalReducer`), so in principle it already streams. Where the output still arrives in jumps (terminal output inside a tool call in the transcript, a chat opened mid-turn, or updates the host batches) is not yet known.

**Proposal.** First, a live check: watch a long-running command on a real host in three places (the terminal screen, the tool call in the chat, the session's output) and write down where output arrives late or all at once. Then fix the cause per place: rendering only on completion, too coarse a throttle, or a host that sends snapshots.

**Finding (2026-11-03, from the code; not yet seen live).** The tool call in the chat is the place that looked like snapshots, and the reason is the protocol's shape. A tool call's terminal result is `ToolResultTerminalContent(resource, title, isPty, result)` (`core-ahp/src/ahpProtocol/.../generated/State.generated.kt:3884`): only the address of the terminal channel, plus `result` (`TerminalCommandResult`: exit code and a preview of the output) once the command ends. The block built its text from that alone (`ToolDetail.kt:209`), so a running command said "No output yet." and then showed everything at once. The terminal channel itself streams: `terminal/data` actions are folded by `terminalReducer` into the store `HostSession.openTerminal` returns, which the terminal screen already follows. Nothing in the chat subscribed to it.

**As built (2026-11-03, the tool call in the chat).** While a tool call runs and its block is open, a terminal result that has no output of its own yet shows the last 12 lines of its terminal (`LiveTerminal.tail`, the same lines as the terminal screen, with blank lines at the end left out), monospace, updating as output arrives. There is no "Live" label over it, and it is not a live region, so TalkBack stays quiet as lines arrive (decided 2026-11-04). "Open live output" stays. The block watches the terminal through `AppController.watchTerminal`, which applies the same rules as opening the terminal screen: only an address a tool result in the open chat names, and read only (nothing is sent but `subscribe` and `unsubscribe`; `terminal/input` stays disallowed). The subscription is shared and counted with the terminal screen, taken when the block's body is composed and given up when it leaves (folded, scrolled out of the list, chat left) or when the call stops running; a folded block composes no body, so it subscribes nothing. When the call finishes the block shows its result as before. If the terminal cannot be watched, or the host has not sent its state yet, the block says "No output yet." Not built: the session's output, and anything for a host that batches its updates. Tests: `LiveTerminalTest` and the U7 cases in `AppControllerTest` (JVM); `LiveTerminalTailTest` (on a device, not yet run).

**Acceptance criteria**

- [ ] **AC-U7.1** The live check is recorded in `docs/verification/`, naming for each place whether output streams, and why not if it does not. (Not done: the finding above is from reading the code. Still to do on a phone with a real host: a long-running command in the terminal screen, in the tool call's block, and in the session's output, noting for each whether output arrives as it is printed.)
- [ ] **AC-U7.2** Output that the host sends as a stream appears on the phone within about a second, in the terminal screen and in the chat. (Built for the chat's tool call block; not yet run on a device.)

## U8 — AI quick replies behind Pro

**Feedback.** Put AI completions behind the paywall.

**Assumption to confirm.** "AI completions" is taken to mean the on-device AI quick-reply suggestions (`GeminiNanoQuickReplyProvider`), not the host's `@` and `/` completions (N3b in [`protocol-completion-spec.md`](protocol-completion-spec.md)), which are the host's own feature.

**Today.** The Free and Pro table in [`prerelease-monetization-spec.md`](prerelease-monetization-spec.md) does not mention AI quick replies. In practice Free has no message box (sending needs "Act for me", which is Pro), so Free may already never reach them; this needs checking, not assuming.

**Proposal (decided 2026-11-03: both kinds are Pro).** Add a row to the Free and Pro table: Free gets the plain (heuristic) quick replies, Pro gets AI suggestions and the host's `@` and `/` completions. Gate the AI provider and the `completions` request on `proUnlocked`, so the rule holds even if Free later gains a message box.

**Acceptance criteria**

**As built (2026-11-03).** Without Pro the connection is held to the READ tier, which has no message box and refuses `completions` (`CapabilityPolicy`), so Free never reached either feature. Two explicit gates now say so in code, so it stays true if Free ever gains a message box: the AI provider is passed to a chat only with Pro (`AppRoot`), and a chat is given the host's trigger characters only with Pro (`AppController.interactorFor`), so without them the host is never asked. The AI settings in Settings stay visible either way.

- [x] **AC-U8.1** With Pro off, no AI suggestion and no host `completions` request is made or shown; with Pro on, both work as today. (By the READ tier, which is tested; the two new gates have no test of their own, because no state reaches them without Pro.)
- [x] **AC-U8.2** The Free and Pro table lists AI quick replies and host completions.

## U9 — A new name

**Feedback.** Rename and rebrand now that the app also runs on iOS.

**Today.** The Android launcher label is "AHP Client" and the Quick Settings tile label "AHP Monitor" (`app/src/main/AndroidManifest.xml:23` and `:66`); iOS uses `PRODUCT_NAME = AHP Client` (`iosApp/Configuration/Signing.xcconfig:5`). The Android application id is `com.greenjeans.ahpclient`. Store text is in [`play-store-listing.md`](play-store-listing.md).

**Proposal.** Pick a name that is not tied to one platform and does not suggest a Microsoft or GitHub product (the non-affiliation wording in the app and store listing stays). Change the shown names, the store listing and the in-app text. Keep the application id and bundle id: changing them makes a new app in the stores and loses installs and purchases.

**Decision needed.** The name, and a trademark and store search before it is used.

**Acceptance criteria**

**Named (2026-11-04): Hostline.** The launcher, the Quick Settings tile, the sign-in screen, About, the Pro dialog and the iOS display name now say "Hostline"; the store title is "Hostline: AHP Client", so people who know the protocol still find it. `PRODUCT_NAME` on iOS is left as it was, so the Xcode target and binary names do not change. A trademark and store search is still to do before the store listing goes live.

**Prepared (2026-11-03).** Every name the app shows now comes from `AppName` in `core-ahp` (sign-in screen, About, the Pro dialog, the Quick Settings tile) or `app/src/main/res/values/strings.xml` (the launcher and tile labels, which the manifest now reads), so a rename is a change to those two files on Android and to `PRODUCT_NAME` and `CFBundleDisplayName` on iOS. The store text in `play-store-listing.md` is separate. Checked: the built APK's label still reads "AHP Client".

- [ ] **AC-U9.1** The new name appears on the launcher, in the app, on iOS and in the store listing; no "AHP Client" or "AHP Monitor" remains on screen.
- [x] **AC-U9.2** The application id and bundle id are unchanged, so an update installs over the existing app.

## Order of work

1. **U3 and U4 together** (one pass on the message box, small, visible every day).
2. **U2** (context in long chats; the jump controls first, then the bar, then reader view).
3. **U1** (cache), which also makes U2's jumps feel instant on return.
4. **U6** (small, independent; can go in anywhere).
5. **U7**, starting with the live check, which needs the phone and a real host.
6. **U5**, after its mock and decision.
7. **U8** and **U9** as soon as their decisions are made; both are small in code.

The protocol slices still open (N3b live check, P7 automations, P8 host terminals) keep their place in [`protocol-completion-spec.md`](protocol-completion-spec.md); U7 overlaps P8 on terminals and should be planned with it.

## Decisions (2026-11-03)

| Question | Decision |
|---|---|
| U2: where reader view opens from | A small "Read" button on the row that ends each turn, beside its duration |
| U5: how other chats are reached | One "N other chats" control opens a bottom sheet with a tree: chats grouped by relation, those waiting on the person first, chats only the agent uses collapsed |
| U5: what the control's dot means (2026-11-04) | Only that another chat is waiting on the person. Unread does not light the control; it shows on the chat's row in the sheet |
| U5: chats the host marks hidden (2026-11-04) | A group of their own, left out by default, revealed by a "Show hidden chats (N)" toggle in the sheet. A hidden chat waiting on the person is always listed under "Waiting on you" |
| U5: archived chats waiting on the person (2026-11-04) | Always listed under "Waiting on you", even with "Show archived chats" off. Other archived chats stay hidden until it is on |
| U7: a label over the live terminal tail, and what TalkBack says | No "Live" label (2026-11-04). The tail is not a live region, so TalkBack does not read each new line |
| U8: what Pro covers | Both AI quick replies (Gemini Nano) and the host's `@` and `/` completions (N3b). Free keeps the plain, heuristic quick replies |
| U9: the name | A shortlist of three, then the owner picks one; a trademark and store search comes before it is used. Shortlist: **Hostline** (a direct line to your agent host), **Agent Deck** (one place to run your agents from), **Steerwise** (steering agents from your phone). None of the three has been searched yet Chosen 2026-11-04: **Hostline**, with the store title "Hostline: AHP Client". |
| P5: "Add folder" on a host that refuses it | Offered until the host refuses it once; then hidden for that host, with the reason shown where it was |
| A first message with no model chosen and none reported by the host | The model picker starts on the host's first listed model with its default settings, so a message always names a model |
| N3b: the host's space trigger | Honoured only when the message starts with a slash command, so the host is asked about a command's arguments and not after every word |
| P7: automations | Wait until a reachable host advertises `automations`, so it can be checked live |
| U6: wrapping markdown tables (2026-11-04) | A "Fit columns to width" toggle on the table card, remembered on the device like the wrap choices: on, the columns share the screen width and cells wrap with no sideways scroll; off, today's behaviour |
| U6: when the tool-output wrap button shows (2026-11-04) | Always, on every block of tool output, not only when a line runs past the edge. Tool input values and attachment previews still show it only when a line does not fit |

The P5, first-model and N3b decisions belong to [`protocol-completion-spec.md`](protocol-completion-spec.md) and are recorded there when that work is picked up.

## Progress Log

| Date | Entry |
|---|---|
| 2026-11-03 | Drafted from a round of user feedback. Nothing built. |
| 2026-11-03 | U5 built: the chat switcher (a main chat chip and one "N other chats" control opening a grouped sheet) replaces the tab row. JVM tests pass; UI tests written, not run on a device. |
| 2026-11-04 | U5 revised: the control's dot means waiting only; hidden chats are their own group behind "Show hidden chats (N)"; waiting archived and hidden chats are always listed. JVM tests pass; UI tests updated, not run on a device. |
| 2026-11-04 | U5 UI tests pass on the emulator (SessionScreens, Accessibility, ManageUi: 181/181 at 1af4dc0). |
| 2026-11-04 | U6: markdown tables can fit their columns to the width; the tool-output wrap button always shows. Fitted columns now share the width as fractions (a 6-column table had a row screens tall). UI tests pass on the emulator (174/174 at 766cfb3). |
