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
- [ ] **AC-U2.4** All three read correctly with TalkBack and at the largest font size.

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

**As built (2026-11-03).** The tab row is gone. Under the bar there is a chip with the main chat's title (tap it to go back to the main chat) and one control, "2 other chats", which opens a bottom sheet; with only the main chat neither is drawn. When another chat is on screen the control shows its title too ("Try plan B · 2 other chats"). `ChatSwitcher` (core, JVM-tested) builds the sheet from the host's chat list: the main chat first, then groups in this order: Waiting on you, Forks, Side chats, Started by you, Other chats, Chats the agent uses. The relation comes from `ChatSummary.origin` (`fork`, `sideChat`, `tool`, `user`); a chat with no origin, or one of a kind this app does not know, goes under "Other chats" with no relation line. Any chat waiting on the person goes in the first group whatever its relation, so nothing that needs them is folded away. "Chats the agent uses" holds chats a tool call started (origin `tool`, for example a sub-agent) and chats the host marks `read-only` or `hidden` (`ChatSummary.interactivity`); it starts folded, and its heading still says how many chats it holds and how many are unread. Within a group a chat sits under the chat it came from, one step further in (a fork of a fork). The relation line says "Fork of turn 3" only when the app holds every turn of the chat it came from (the one on screen or one of the few kept warm, with no older turns left to load); otherwise it names the chat ("Fork of the main chat", "Fork of Try plan B"). Dots: a chat waiting on you gets the strong dot, as the tabs did; an unread chat (the host has not marked it read) that is not on screen gets a quieter dot, which the tabs never showed. The control carries a dot when any other chat has one, and its spoken description says why ("3 other chats, 1 waiting on you, 1 unread"). Archived chats stay hidden unless "Show archived chats" is on, as before. Not built: the link in the transcript at the turn a fork or side chat came from.

**Acceptance criteria**

- [ ] **AC-U5.1** A session with many chats shows one control instead of a tab per chat; the main chat stays one tap away. (Built; UI tests written in `SessionScreensTest` and `AccessibilityTest`, not yet run on a device.)
- [ ] **AC-U5.2** The switcher shows each chat's relation (fork of turn N, side chat, started by the agent) and puts chats waiting on the person first. (Half checked: the grouping, order, tree and wording are covered by JVM tests in `ChatSwitcherTest`; the sheet's UI tests are written, not run. The turn number shows only when the parent chat's turns are all held.)
- [ ] **AC-U5.3** Unread and needs-input signals are not lost compared with today's tab dots. (Half checked: the counts and marks are covered by JVM tests, including a folded group; the dots on screen are in UI tests written, not run.)

## U6 — Word wrap on or off

**Feedback.** Offer word wrap on and off where it makes sense.

**Today.** Code blocks and tables in markdown (`MarkdownPart.kt:298`, `:381`), tool output (`ToolResultViews.kt:264`) and the file viewer (`RichViewer.kt:338`) keep long lines on one line and scroll sideways, with no way to wrap them. The terminal screen and the file diff do not scroll sideways, so they offer no way to see a line unbroken.

**Proposal.** A wrap toggle on each of those views, remembered per kind of view (code, tool output, file, terminal, diff) on the device. Prose always wraps.

**Acceptance criteria**

- [ ] **AC-U6.1** Code blocks, tool output and the file viewer offer a wrap toggle that removes the sideways scroll; the terminal screen and the file diff offer the opposite (keep lines whole and scroll sideways).
- [ ] **AC-U6.2** The choice is remembered per kind of view across restarts.

## U7 — Live terminals and host output

**Feedback.** Terminals and host output should stream better, not show as snapshots.

**Today.** An opened terminal is subscribed and folds each update the host sends (`HostSession.openTerminal`, `HostSession.kt:426`, through `terminalReducer`), so in principle it already streams. Where the output still arrives in jumps (terminal output inside a tool call in the transcript, a chat opened mid-turn, or updates the host batches) is not yet known.

**Proposal.** First, a live check: watch a long-running command on a real host in three places (the terminal screen, the tool call in the chat, the session's output) and write down where output arrives late or all at once. Then fix the cause per place: rendering only on completion, too coarse a throttle, or a host that sends snapshots.

**Acceptance criteria**

- [ ] **AC-U7.1** The live check is recorded in `docs/verification/`, naming for each place whether output streams, and why not if it does not.
- [ ] **AC-U7.2** Output that the host sends as a stream appears on the phone within about a second, in the terminal screen and in the chat.

## U8 — AI quick replies behind Pro

**Feedback.** Put AI completions behind the paywall.

**Assumption to confirm.** "AI completions" is taken to mean the on-device AI quick-reply suggestions (`GeminiNanoQuickReplyProvider`), not the host's `@` and `/` completions (N3b in [`protocol-completion-spec.md`](protocol-completion-spec.md)), which are the host's own feature.

**Today.** The Free and Pro table in [`prerelease-monetization-spec.md`](prerelease-monetization-spec.md) does not mention AI quick replies. In practice Free has no message box (sending needs "Act for me", which is Pro), so Free may already never reach them; this needs checking, not assuming.

**Proposal (decided 2026-11-03: both kinds are Pro).** Add a row to the Free and Pro table: Free gets the plain (heuristic) quick replies, Pro gets AI suggestions and the host's `@` and `/` completions. Gate the AI provider and the `completions` request on `proUnlocked`, so the rule holds even if Free later gains a message box.

**Acceptance criteria**

- [ ] **AC-U8.1** With Pro off, no AI suggestion and no host `completions` request is made or shown; with Pro on, both work as today.
- [ ] **AC-U8.2** The Free and Pro table lists AI quick replies and host completions.

## U9 — A new name

**Feedback.** Rename and rebrand now that the app also runs on iOS.

**Today.** The Android launcher label is "AHP Client" and the Quick Settings tile label "AHP Monitor" (`app/src/main/AndroidManifest.xml:23` and `:66`); iOS uses `PRODUCT_NAME = AHP Client` (`iosApp/Configuration/Signing.xcconfig:5`). The Android application id is `com.greenjeans.ahpclient`. Store text is in [`play-store-listing.md`](play-store-listing.md).

**Proposal.** Pick a name that is not tied to one platform and does not suggest a Microsoft or GitHub product (the non-affiliation wording in the app and store listing stays). Change the shown names, the store listing and the in-app text. Keep the application id and bundle id: changing them makes a new app in the stores and loses installs and purchases.

**Decision needed.** The name, and a trademark and store search before it is used.

**Acceptance criteria**

- [ ] **AC-U9.1** The new name appears on the launcher, in the app, on iOS and in the store listing; no "AHP Client" or "AHP Monitor" remains on screen.
- [ ] **AC-U9.2** The application id and bundle id are unchanged, so an update installs over the existing app.

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
| U8: what Pro covers | Both AI quick replies (Gemini Nano) and the host's `@` and `/` completions (N3b). Free keeps the plain, heuristic quick replies |
| U9: the name | A shortlist of three, then the owner picks one; a trademark and store search comes before it is used. Shortlist: **Hostline** (a direct line to your agent host), **Agent Deck** (one place to run your agents from), **Steerwise** (steering agents from your phone). None of the three has been searched yet |
| P5: "Add folder" on a host that refuses it | Offered until the host refuses it once; then hidden for that host, with the reason shown where it was |
| A first message with no model chosen and none reported by the host | The model picker starts on the host's first listed model with its default settings, so a message always names a model |
| N3b: the host's space trigger | Honoured only when the message starts with a slash command, so the host is asked about a command's arguments and not after every word |
| P7: automations | Wait until a reachable host advertises `automations`, so it can be checked live |

The P5, first-model and N3b decisions belong to [`protocol-completion-spec.md`](protocol-completion-spec.md) and are recorded there when that work is picked up.

## Progress Log

| Date | Entry |
|---|---|
| 2026-11-03 | Drafted from a round of user feedback. Nothing built. |
| 2026-11-03 | U5 built: the chat switcher (a main chat chip and one "N other chats" control opening a grouped sheet) replaces the tab row. JVM tests pass; UI tests written, not run on a device. |
