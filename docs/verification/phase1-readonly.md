# Phase 1 read-only verification

Record of what has been checked, how, and what is still open for the Phase 1 acceptance criteria (AC1.1 to AC1.9 in the Phase 1 spec). Update the status column as items are verified; do not
tick an item that was only reasoned about.

Dates are 2026-10-22. "Phone" is a Pixel 10 on Android 17.

## Status

| AC | Claim | Evidence | Status |
|---|---|---|---|
| 1.1 | Device-flow sign-in, code shown in the app, no PAT entry | Real phone: code shown, typed into the phone's browser, approved; there is no token field. "Open GitHub" opens the default browser, which may not be the one signed in to GitHub, so typing the code is the path that works | Verified |
| 1.2 | Lists the user's agent-host tunnels with online state and connects | Real phone: host list with Online and last-used, connect reached "Connected" | Verified |
| 1.3 | Sessions list with title, activity, status from the bit flags; needs-input first | Real phone: rows with worded status chips, live flips both ways. Needs-input-first order is JVM-tested (`SessionCatalogTest`); not looked at on the phone | Verified, order by test |
| 1.4 | Live updates without refresh; reconnect resumes | Live updates seen on the phone. Reconnect after a network loss is tested only against a scripted host. The user reported the connection lost when the screen locks, which Step 1.11 addresses | Partly verified |
| 1.5 | Detail shows title, activity, chats, pending requests as read-only text | Real phone: a pending question and its text, and that it clears after it is answered. Option labels are JVM- and emulator-tested but not yet seen on a real pending question | Verified, options by test |
| 1.6 | Opt-in background monitoring notifies once when a session enters Needs input; tap opens it | Built. JVM tests for the detector, debounce, no storm on connect, coordinator lifecycle. Emulator tests for channels, the notification and the manifest. Not run on the phone | **Open: needs the phone** |
| 1.7 | Only read-tier methods are sent; no `activeClients` entry | Allowlist unit-tested. Probe against the real host: `activeClients=0`, wire methods `initialize`, `listSessions`, `subscribe`, `unsubscribe`. On-device wire log (`AhpWire`, debug builds) is built but not yet run | **Open: needs the phone** |
| 1.8 | Token only encrypted under a Keystore key; connect tokens memory-only; nothing logged | Real phone: sign-in restored after process death from the hardware-backed vault; vault file empties on sign-out. Connect tokens are fetched on every connect and never stored. A log search for token prefixes on the device has not been run | **Open: log search on the phone** |
| 1.9 | Sign-out deletes credentials and links to GitHub's authorized-apps page | Real phone: sign-out removes the vault file and survives a cold start. The link ("Review access on GitHub" after a deliberate sign-out) was added on 2026-10-22 and is tested on emulators only | **Open: look at it on the phone** |

## How the on-device pass is run

Debug builds log one line per outbound frame under the tag `AhpWire`: the JSON-RPC method name and nothing else, with
`NOT-READ` in front of any method outside the read tier. Release builds log nothing.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = '<serial from adb devices>'
& $adb -s $s install -r app\build\outputs\apk\debug\app-debug.apk     # keeps the sign-in
& $adb -s $s logcat -c
```

Then, on the phone, in one session of use: sign in or restore, open a host, open two sessions and go back, turn
**Monitor in background** on (allow notifications when asked), lock the screen for at least 10 minutes, ask a question
from a new session in VS Code, wait for the notification, tap it, press Stop on the persistent notification, sign out
and tap "Review access on GitHub".

Afterwards:

```powershell
# Every outbound method, with how many times. Expect only read-tier names and gateway.select.
& $adb -s $s logcat -d -s AhpWire | ForEach-Object { ($_ -split 'AhpWire:\s*')[-1].Trim() } | Group-Object | Sort-Object Count -Descending | Select-Object Count, Name

# Must print False
(& $adb -s $s logcat -d -s AhpWire | Select-String -Pattern 'NOT-READ' -Quiet)

# No token reached the log. Prints only True or False, never a match. Must print False
(& $adb -s $s logcat -d | Select-String -Pattern 'ghu_|ghr_|gho_|github_pat_' -Quiet)

# The service was a foreground service of the data-sync type while monitoring was on (run while it is on)
& $adb -s $s shell dumpsys activity services dev.ahpclient | Select-String 'isForeground|foregroundId|types'
```

Pass criteria:

- The `AhpWire` summary lists only `initialize`, `gateway.select`, `listSessions`, `subscribe`, `unsubscribe`, `reconnect`,
  `fetchTurns` and `ping`; the `NOT-READ` search is False.
- While the screen was off, `initialize` was sent once for the connection, not again: a second `initialize` or a
  `reconnect` during the lock means the socket dropped despite the service.
- Exactly one "Needs your input" notification for the question; a locked screen showed no session title; tapping it
  opened that session.
- Stop removed the persistent notification and nothing was left running; sign-out did the same.
- The token search is False.

A TalkBack pass is separate and manual: turn TalkBack on, walk the sign-in, host list, sessions list and a detail, and
write down anything read wrongly. The automated accessibility checks (API 34 and newer emulators) pass but do not
replace this.

## Results

| Date | Device | AC | Result | Notes |
|---|---|---|---|---|
| 2026-10-22 | Pixel 10, Android 17 | 1.1, 1.2, 1.3, 1.5 | Pass | Sign-in, host list, list and detail against the real host |
| 2026-10-22 | Pixel 10, Android 17 | 1.9 (wipe only) | Pass | Vault 319 to 0 bytes; still signed out after a cold start |
| 2026-10-22 | Real host, JVM probe | 1.7 | Pass | `activeClients=0`; wire methods `initialize`, `listSessions`, `subscribe`, `unsubscribe` |
| | Pixel 10 | 1.4, 1.6, 1.7, 1.8, 1.9 | Not run | The on-device pass above |
