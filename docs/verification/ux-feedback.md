# UX feedback: live verification (U1 to U4)

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
- When the snapshot replaced the copy, the quick-reply bar that had been dismissed came back, because the message box is made afresh. Minor.
- The jump button sits at the right of the row with Regenerate, Fork and Side chat when the turn's end is on screen. It did not cover a button here, but it is close on a narrow screen.

**Not run**

- U1 with the chat left scrolled up before closing it (whether the place holds when the snapshot arrives).
- TalkBack and the largest font size (AC-U2.4).
