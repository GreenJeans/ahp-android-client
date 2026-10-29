# Navigation: what was seen on the phone

Runs against [`docs/navigation-spec.md`](../navigation-spec.md). Each run: date, build, device, host, what was done, what was seen.

## 2026-10-29: first pass on the Pixel 10

- **Build:** debug, `6cd6615` (installed with `:app:installDebug` the same morning).
- **Device:** Pixel 10, Android 17, gesture navigation, dark theme.
- **Host:** one VS Code tunnel host (`re1lw1zrrff4`), connected, about 300 sessions (305 archived). No host added by address, so the sheet showed "No other hosts."
- **Permissions:** acting for me and managing were on. The pass sent only read-tier methods: `adb logcat -d -s AhpWire` showed `subscribe`, `unsubscribe` and one `resourceRead`, and no `dispatchAction`.
- **Method:** driven with `adb shell input` (key events, taps, swipes). Read back with `uiautomator dump` and screenshots. Not done by hand, and no TalkBack.

| Step | Seen | Criterion |
|---|---|---|
| A session was open. Back | The session closed and the menu was open, still connected, sessions listed | AC-N6.2 |
| Tap the scrim to the right of the menu, no session open | The menu stayed open | AC-N6.1 |
| Swipe the menu left, no session open | The menu stayed open | AC-N6.1 |
| Back at the home menu | The launcher came to the front; the app process kept the same pid (1263) | AC-N6.2 |
| Launch the app again | The menu as it was, "Connected", sessions listed | AC-N6.2 |
| Tap the host row | The Hosts sheet opened over the menu: the connected host with Disconnect, "No other hosts.", Add a host by address, Manage hosts | AC-N4.1 |
| Back on the sheet | The sheet closed and the menu was still open | AC-N4.1 |
| Open the sheet again, swipe it down | The sheet closed and the menu was still open | AC-N4.1 |
| Manage hosts | Settings opened with Hosts first: GitHub "Signed in. Renews itself." with Sign out, and Hosts you added "None yet." | AC-N5.1 (sign-out and add rows present; not exercised) |
| Back in Settings | The menu came back | — |
| Open a session, tap Menu, then Back | The menu closed and the session stayed open | AC-N6.2 (menu over a session) |
| The changes `(+685, −18)` in the bar | A menu of two changesets: Session, Working Set. Back closed the menu and left the session open | AC-N6.3 |
| Session changeset, then a file | The changeset list ("11 files · +754 −17"), then the diff of one file | AC-N6.3 |
| Back from the diff, then from the changeset, then from the session | Diff to changeset, changeset to session, session to menu | AC-N6.3 (changeset and diff only) |
| Swipe in from the left edge with a session open | The session closed and the menu opened, the same result as Back | AC-N6.7 (outcome only; the animation was not watched) |
| `dumpsys activity services dev.ahpclient` with monitoring on | `MonitorService` with `isForeground=true` and `types=0x40000000` (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`) | AC-H8.1 ([hardening-spec](../hardening-spec.md)) |

### Found

1. **The Archived count badge is clipped.** With 305 archived sessions the badge on the Archived button shows "30", cut off by the button's edge. Three-digit counts don't fit. The spoken label is not affected. **Fixed the same day (uncommitted):** an icon button clips what it holds to its shape, so the badge (Archived and Inbox) is now drawn over the button by `CountOver` in `SessionsDrawer.kt` and is hidden from accessibility, since the button already says the count. Covered by `SessionScreensTest.aThreeDigitArchivedCountIsNotClippedByTheToggle` and `aThreeDigitInboxCountIsNotClippedByTheButton`. Seen on the Pixel: "304" in full.
2. **The host row shows the tunnel's id.** The menu, the sheet and the session bar call the host `re1lw1zrrff4`. If the tunnel has a friendlier name (the machine name), the app doesn't show it. Check what the tunnel list returns before treating this as a bug.
3. **Changed-file rows are tall.** Each row shows the file's full absolute path on the host (`c:/Users/.../mcp-tool-cache.ts`), which wraps to about nine lines, so two files fill the screen. The path relative to the project, or the file name with the folder under it, would read better.
4. **The added-line count differs between list and diff.** The list says `+192 −0` for a file whose diff says `+191 −0 lines` (lines 1–191). This is probably the trailing newline. Small, but it is the kind of mismatch a reviewer notices.
5. **Opening a session receives a 580,726-character frame** (logcat, twice for the same session). It is not a navigation problem, but it is a cost on mobile data each time a session is opened.

6. **A cold start whose first tunnel-list load fails doesn't connect.** After the fixed build was installed over the old one on the Pixel (process restarted, monitoring on, the tunnel host last used), the app came up with the hosts sheet open, saying "Could not reach the Dev Tunnels service. Check the connection and try again." The phone's network was fine (a ping to the tunnel relay answered). One tap on refresh listed the host, and a tap on it connected. So one transient failure right after start leaves the person on the sheet, with no auto-connect and no retry. Bears on AC-H6.1 and AC-N6.4.

### Not done in this pass

- AC-N1.2 to N1.4 (links): no link was opened. Needs a chat with an https link, and the device-flow link needs a sign-out.
- AC-N4.2, AC-N4.6 (switching hosts): only one host is known. Needs `ahpd` on the LAN, or a second tunnel host.
- AC-N4.4, AC-N5.2, AC-N5.3: no sign-out, no direct host to remove.
- AC-N6.3 terminal and subagent screens, AC-N6.4 (no host), AC-N6.5 (notification tap), AC-N6.6 (rotation: not driven, since it needs a change to the phone's rotation setting).
- AC-N6.7 animation: predictive back was not watched frame by frame.
- TalkBack across the action row.

## 2026-10-29: second pass on the emulator

- **Build:** debug, `6cd6615` plus the uncommitted badge fix (`CountOver`).
- **Device:** emulator `ahp-api36` (API 36, Google APIs, x86_64, 1080x2400, 420 dpi), windowed, `-gpu swiftshader_indirect -no-snapshot-load`, light theme, gesture navigation.
- **Host:** the same VS Code tunnel host, after a fresh GitHub sign-in on the emulator by the person. The app was on the read tier only (a new install: acting for me and managing off).
- **Method:** `adb shell input`, `adb emu rotate`, `uiautomator dump` and screenshots.

| Step | Seen | Criterion |
|---|---|---|
| First start after a fresh sign-in, no last host | The hosts sheet opened by itself over the menu, listing the tunnel host "Online · v6" | AC-N6.4 (the "fresh sign-in" path; not a cold start that restores a sign-in) |
| Tap the host in the sheet | The sheet closed and the menu showed it Connected with its sessions. No "Not connected" page at any point | AC-N4.2 (connecting from the list; not a switch between two hosts) |
| The Archived badge with 306 archived | "306" in full | Finding 1 fixed |
| A chat's tool steps, a finished "Run Shell Command", then Open live output | The terminal screen: "Exited with code 0", "Read only, as plain text.", the command's output | AC-N6.3 (terminal) |
| Back from the terminal | Back to the chat, session still open | AC-N6.3 (terminal) |
| Rotate with a session open | Session still open, menu closed | AC-N6.6 |
| Back in landscape | Session closed, menu open | AC-N6.2 |
| Rotate with the home menu, no session | Menu still open, still listing sessions | AC-N6.6 |
| Open a session, open the menu over it, rotate | Menu still open over the session | AC-N6.6 |
| Back | The menu closed, the session stayed | AC-N6.2 |
| Rotate back to portrait | The session was still open | AC-N6.6 |

**Two reconnects during rotation were the emulator's network, not the app.** "Connecting…" showed after two rotations. logcat at the same moment has the emulator's virtual Wi-Fi dropping (`CTRL-EVENT-BEACON-LOSS`, `wlan0` link down), and the app went `WaitingForNetwork` → `Connecting` → `Online` within about 3 s. A rotation back with no Wi-Fi event caused no reconnect, and 20 s idle caused none.

**Not done:** links (no web link was found in the chats looked at; markdown links don't show their address to `uiautomator`, so finding one needs a screen-by-screen look), a subagent transcript, switching between two hosts (still only one host known), the notification tap, sign-out and removal, TalkBack, and predictive back's animation.
