# UX feedback: live verification (U1 to U9)

Runs of the U items in `docs/ux-feedback-spec.md` against a real host. Boxes in the spec stay unticked until a run below covers them.

## 2026-11-03: Pixel 10, VS Code dev tunnel host

**Setup.** Debug build of `ebe8925` (branch `claude/ux-feedback-roadmap`) installed over the existing app by `tools/run-device-tests.ps1`, sign-in kept. Read only: the app was driven over `adb`, one existing idle session was opened ("lore-compile.ps1: fix pwsh 7…", a finished 35-minute turn), and nothing was sent, approved or changed on the host. The first attempt could not connect (socket timeouts; the tunnel service listed VS Code as not connected) until Remote Tunnel Access was turned on again.

| Check | Result | Notes |
|---|---|---|
| U2: the last message is kept in view | Pass | With the chat at its newest row, the bar at the top read "/pr-watch 88662", the message that started the turn |
| U2: jump to the start of the reply | Pass | The round button above the message box went to the reply's first row ("Used 1 tool, Read Skill"), which started just below the bar |
| U2: tapping the bar | Pass | Went to the message itself; the bar then went away, and so did the jump button, since the reply's start was on screen |
| U2: reader view | Pass | "Read" beside "Done · 34m 52s · 305.2k tok · $1.00" opened the turn's text full screen, without its tool steps, under "Reply · Reader view" |
| U2: back from reader view | Pass | The chat was at exactly the same place as before it opened (screenshots identical) |
| U3: Suggest | Pass | Hidden while the quick-reply bar was showing; after the bar was dismissed a sparkle icon appeared at the end of the text field, and no Suggest chip in the row |
| U4: the chip row scrolls | Pass | Attach, `/`, "Default agent" and "Claude Sonnet 5.5"; a sideways swipe moved the row and showed the model's name whole, while send stayed in place |
| U1: a closed chat comes back at once | Pass | After opening another session and coming back, the chat showed straight away under "Showing this chat as you last saw it. Updating…", with no message box |
| U1: the snapshot replaces the copy | Pass, at the newest row only | Within a few seconds the line went, the message box came back and the chat stayed at its newest row. A chat left scrolled up was not tried |

**Findings to decide on**

- Reader view showed every text part of the turn, so the agent's progress notes ("Reading the rest of the workflow…") came before its final answer, which a long turn left at the bottom. Decided the same day: the answer first, the notes folded under it.
- The quick-reply bar that had been dismissed was back after leaving the session and opening the chat again. That is not the cache: a dismissal is kept only while the chat stays open, not across visits. Minor.
- The jump button sits at the right of the row with Regenerate, Fork and Side chat when the turn's end is on screen. It did not cover a button here, but it is close on a narrow screen.

**Not run**

- U1 with the chat left scrolled up before closing it (whether the place holds when the snapshot arrives). Run on 2026-11-04, below.
- TalkBack and the largest font size (AC-U2.4).

## 2026-11-04: Pixel 10, `main` after U5 to U9, then the fixes on `claude/live-eval-fixes`

**Setup.** Debug build of `main` at `0672b02` (all of U5 to U9, P5 and P8 merged), installed over the existing app over USB adb, sign-in kept, Pro on through the debug switch. Mostly read only. On the host: one empty side chat was made in "Idea 9" for U5 and removed afterwards (the removal asked for the phone's face check, which the owner's face answered), and one "Add folder" was tried, which the host refused. Text typed into message boxes was deleted unsent. Then the build of `claude/live-eval-fixes` was installed the same way and the three fixes were checked.

| Check | Result | Notes |
|---|---|---|
| U5: the switcher shows only with a second chat | Pass | Seven sessions with one chat each showed no switcher. After "Side chat" on turn 7 of "Idea 9" the bar showed the main chat chip and "Unti… · 1 other chat" |
| U5: what TalkBack hears on the bar | Pass (read from the tree) | "Idea 9: remote mesh board spec, Main chat" and "1 other chat, showing Untitled session"; after switching back, "…, Main chat, Shown" and "1 other chat" |
| U5: the sheet | Pass | "Chats", the main chat with "Main chat · 3m", then a "Side chats" group with "Untitled session, Side chat from turn 7 · 3m" and a check on the one shown; tapping the main chat went back to it |
| U5: the dot means waiting only | Pass | No dot while the main chat was working and nothing waited on the person |
| U6: wrap on a tool's input | Pass | A 409-character command showed the wrap button; tapping it wrapped the value, and the button then read "Keep lines whole" (52 dp target) |
| U8: Free | Pass | With Pro off the chat had no message box, so no quick reply and no host suggestion can be asked for |
| U8: Pro | Pass | `#` brought the host's files (TOUR.md, CONTRIBUTING.md, package.json…); AI quick replies showed as "Quick replies · Gemini Nano". (`@` shows the app's own agent list, not the host's, as recorded for N3b) |
| U9: the name | Pass | The phone's own face check for removing a chat was headed "Hostline" |
| U2: pinned bar and Read | Pass | Seen in every chat opened |
| U1: a chat left scrolled up | **Fail on `main`, fixed** | On `main` it came back at the newest row and stayed there (no jump, but the place was lost). With the fix, left four swipes up, another session opened, then back: the screen 6 seconds later, after the snapshot, was identical to the one at leaving (same screenshot hash) |
| Pro off then on | **Fail on `main`, fixed** | On `main` turning Pro off also turned "Act for me" off for good, and turning Pro on left it off. With the fix it reads "Requires Pro. Watch only." while Pro is off and is on again, without asking, once Pro is back |
| Settings without Pro | **Fail on `main`, fixed** | On `main` the quick-reply tile still said "Gemini Nano ready on-device" and offered the AI source. With the fix it says "Requires Pro.", offers no AI choice, and the Unlock Pro text names AI quick replies and the host's suggestions |

**Findings**

- The main chat's row in the U5 sheet does not say it is working. Minor. Fixed the same day: a working chat's row reads "… · Working", and so does what TalkBack says for it.
- A chat that is working moves under the finger as rows arrive, which made "Side chat" hard to hit: the first two taps landed on other rows. Not new, and not a small fix: while a turn runs the list is held by its newest row (the working line, then the reply as it grows), so every new or growing row pushes the rows above it up. Holding still under a finger means holding by the touched row instead, which changes how U2's follow mode works. Left for a design decision.

**Not run**

- TalkBack itself, and the largest font size on a device (AC-U2.4).
- U7's live terminal tail and P8's host terminals: no tool result on this host named a terminal, and the host lists no terminals of its own (see `protocol-completion.md`).
