# Navigation: the side menu as home, hosts in a sheet

> **Status:** N1 to N3 built (uncommitted); N4 to N6 planned. Decisions marked **(open)** below wait on the person using the app; each has a default the build will take if no answer comes.
> **Date:** 2026-10-28
> **Parent:** [`docs/spec.md`](spec.md) (product phases; "Design pass 4" built the drawer shell this changes). [`docs/extensions-spec.md`](extensions-spec.md) E6 built the direct hosts that the host sheet lists. Tick boxes here as work lands and add a row to the Progress Log at the bottom; note the commit in the row.
> **Source:** the person's requests of 2026-10-28, and a read of `AppRoot.kt`, `SessionsDrawer.kt`, `Shell.kt`, `HostScreen.kt`, `SettingsScreen.kt`, `Links.kt` and `AppController.kt` at `543a121`.

## Overview

Design pass 4 made a drawer shell with an empty main page under it: "Not connected" with a button to the host screen, or "No session open" with Sessions and New session. Both pages only point at the drawer or the host screen. This spec removes them: with no session open, the drawer *is* the main page; the host becomes a sheet over the drawer; switching hosts goes from one host to the next without passing through a "not connected" page; and the drawer's header gets denser.

### Goals

- No page whose only job is to point somewhere else. With no session open, the side menu is what the person sees.
- Back is predictable: from a session to the menu, from the menu out of the app.
- Switching hosts is one tap from the menu and never shows "Not connected" between hosts.
- The drawer header takes one row for its actions, not four.
- One fewer setting: links open in the phone's default browser, and the system's own share sheet covers "open it somewhere else".

### Non-goals

- Several live hosts at once (still deferred, parent spec open question 6; extensions spec E6 "one live host at a time"). The host sheet lists other hosts; picking one ends the current connection and starts the new one.
- Any change to what a capability tier allows. Nothing here sends a new kind of message to a host.
- A tablet or foldable layout (permanent drawer). Worth doing, not in this pass.

### Order of work

| # | Item | Theme | Size | Depends on |
|---|---|---|---|---|
| N1 | Links open in the default browser; Share in the link dialog | Simplify | S | — |
| N2 | A compact drawer header | Density | S | — |
| N3 | Host catalog outside `AppState`, and `switchHost` | Foundation (core) | M | — |
| N4 | The host sheet | Feature | M | N3 |
| N5 | Host management moves into Settings | Simplify | S | N3, N4 |
| N6 | The drawer as home, and Back | Navigation | M | N2, N4 |

N1, N2 and N3 touch different files and can run in parallel sessions. N3 is the risky one (it changes `AppState` and so `AppControllerTest`, about 160 cases); N4 to N6 build on it one after the other, since all three edit `AppRoot.kt`.

---

## N1 - Links open in the default browser; Share in the link dialog

### Problem

The host screen has **Open links in**: the system default or any installed browser, found by querying the phone's browsers (`Browsers.installed`, two `<queries>` entries in the manifest). Android already has a default browser setting, and Custom Tabs already offer "Open in…" and Share from their own menu. The setting duplicates both, costs package visibility, and sits on the host screen, where nobody would look for it.

### Design

- Remove `Browsers`, `BrowserOption`, `BrowserPickerDialog`, `BrowserChoice` and the browser row from `HostScreen.kt`; remove `UiSettings.browser` / `setBrowser` and the `browser` parameter of `openInBrowser`, `openLink` and `launchWeb` (`Links.kt`), and its uses in `AppRoot.kt` and `MainActivity.kt`.
- `launchWeb` keeps its shape: a Custom Tab with no package set, so the system's default browser opens it; `ACTION_VIEW` + `BROWSABLE` if no Custom Tab handler exists.
- Remove the two `<queries>` intents from `AndroidManifest.xml` (neither `CustomTabsIntent.launchUrl` nor `startActivity` needs them; confirm on the phone that a link still opens on Android 11+, where package visibility applies).
- `UiSettings` removes the stored `browser` key once at load (`prefs.edit().remove(BROWSER)`), so a stale package name is not left behind.
- **Share.** `LinkConfirmDialog` gains a third button for web links, **Share**, which sends the full URL through `Intent.createChooser(Intent(ACTION_SEND).setType("text/plain").putExtra(EXTRA_TEXT, url), null)`: the system share sheet, where "open in another browser", copy and every messaging app already are. Mail and phone links have no Share (they are not URLs a person shares).

**(open)** "Share picker" could also mean an *open-with* chooser on every link (`Intent.createChooser(ACTION_VIEW)`), which asks every time. Default: the default browser opens it, and Share is one tap away in the dialog. The every-time chooser is the alternative if that is what was meant.

### Acceptance criteria

- [x] **AC-N1.1** No screen offers a browser choice; `UiSettings` holds no browser and the old key is gone after first launch. _`ScreensTest.theHostScreenOffersNoBrowserChoice`, `UiSettingsInstrumentedTest`; API 36 emulator._
- [ ] **AC-N1.2** A link in agent output, and the GitHub device-flow link, open in the phone's default browser as a Custom Tab. _Code is in place (a Custom Tab with no package set); not seen on a device._
- [ ] **AC-N1.3** The link dialog for a web link has Share, which opens the system share sheet with the full URL; mail and phone links do not. _Verified: `ChatScreenTest` (Share present for web and hands over the full URL without opening it; absent for mail and phone, and for a caller that gives no way to share) and `LinksTest` (the chooser's action, type and text); API 36 emulator. Not verified: the `AppRoot` wiring that starts the chooser (and its "Nothing can share this link" toast), the share sheet itself, and the three-button dialog's layout and 48 dp targets; they need a device._
- [ ] **AC-N1.4** The manifest has no `<queries>`, and links still open on the Pixel (Android 17) and on an API 30 emulator. _The manifest has no `<queries>` (done); that links still open without package visibility needs a device, and no API 30 image is installed._

### Tests

`ChatScreenTest` (or the link dialog's own test): Share present for web, absent for mail and phone, and the intent it builds (action, type, text). Removing browser cases from `ScreensTest`. AC-N1.4 needs a device.

---

## N2 - A compact drawer header

### Problem

The drawer stacks four full-width rows above the sessions: the host, **New session** (an extended button), **Inbox** and a divider, then **Settings** as a row at the bottom, and **Archived (3)** as a text button in the Sessions header. On a phone that is a third of the drawer before the first session.

### Design

```
┌──────────────────────────────────┐
│ ▣ Laptop (tunnel)          ⌄     │  host row: name + state, opens the host sheet (N4)
│   Online                         │
│ [📥 2]  [🗄 3]        [ + ]  [⚙] │  one row of icon buttons
├──────────────────────────────────┤
│ Sessions            Updating…    │
│ 2 in progress · 1 needs you      │
│ ( Search                      )  │
│ …                                │
```

- **Host row** as today (`HostButton`), with a down chevron instead of the right one, since it now opens a sheet rather than a page.
- **One action row** under it, 48 dp high, start to end:
  - **Inbox**: `AppIcons.Inbox` with a badge for the waiting count (`BadgedBox`, the tertiary colour as today). Spoken "Inbox, 2 need you" / "Inbox". Tag `drawer_inbox`.
  - **Archived**: a new `AppIcons.Archive` (Phosphor `archive`, drawn like the others in `Icons.kt`) as an `IconToggleButton`, checked while archived sessions are shown, with a small count of hidden archived sessions when not, and a filled container while checked (so the state is not carried by the icon's tint alone). Spoken "Show archived" always, with `stateDescription` "On" / "Off, 3 hidden", so the name never says the opposite of the state. Shown only when there are archived sessions or they are being shown, as today. Tag `archived_toggle`. Removed from the Sessions header.
  - a spacer,
  - **New session**: a `FilledTonalIconButton` with `AppIcons.Add`, spoken "New session". Only when the app may manage sessions, as today. It opens the same dialog `NewSessionButton` opens (the button's look changes, not its flow).
  - **Settings**: `AppIcons.Settings`, spoken "Settings". Tag `settings`. Removed from the bottom of the drawer, which gives the list that height back.
- Every icon button gets a `PlainTooltip` on long press with its name, and a 48 dp touch target. None carries meaning by colour alone (the badge has a number).

**(open)** Settings could stay at the bottom of the drawer as an icon, where it is now. Default: in the action row, so all the drawer's actions are in one place and the list runs to the bottom.

### Acceptance criteria

- [x] **AC-N2.1** The drawer shows the host row, then one row of icon buttons, then Sessions; the words Inbox, Archived, New session and Settings appear nowhere in it except in spoken labels and tooltips. _`theDrawerHasIconButtonsButNoneOfTheirNamesAsText`, `theActionRowSitsUnderTheHostAndAboveTheSessions`; API 36 emulator._
- [x] **AC-N2.2** Each icon button has a spoken name, a tooltip and a 48 dp target; Archived reads as a toggle with its state; the inbox count is spoken. _`theArchivedToggleRevealsArchivedSessionsAndKeepsItsLabel`, `theArchivedToggleCountsWhatIsHiddenAndSaysOnOrOff`, `aLongPressNamesAnIconButtonInATooltip`, the inbox and settings cases, 48 dp checked on touch bounds; API 36 emulator. TalkBack not run._
- [x] **AC-N2.3** New session is absent without the manage tier, and the Archived toggle is absent when there is nothing archived and it is off, as today. _`newSessionIsNotOfferedWhenTheAppMayNotManageSessions`, `noToggleIsOfferedWhenNothingIsArchived`, `ManageUiTest` drawer cases; API 36 emulator._
- [x] **AC-N2.4** The accessibility test class passes on the drawer in both themes. _`AccessibilityTest.theDrawerActionRowInBothThemes` (light and dark, archived shown and hidden) and the existing drawer cases; API 36 emulator._

### Tests

`SessionScreensTest` / `ManageUiTest` cases that tap by tag keep their tags; text-based finds (`onNodeWithText("Settings")`) move to content descriptions. An `AccessibilityTest` case for the new row.

---

## N3 - Host catalog outside `AppState`, and `switchHost`

### Problem

The list of hosts lives only in `AppState.Hosts` (`list`, `direct`, `tokenExpiresAt`, `renews`), so it exists only while not connected. `connect()` refuses unless the state is `Hosts`. The only way from one host to another is `disconnect()` (to `Hosts`, which the UI draws as "Not connected" plus a host page) and then `connect()`. A sheet that lists other hosts while connected has nothing to read.

### Design

- **One source of truth for hosts.** New `AppController.hosts: StateFlow<HostCatalog>`, where `HostCatalog(list: HostList, direct: List<DirectEntry>, tokenExpiresAt: Instant?, renews: Boolean)`: the four fields leave `AppState.Hosts`. `AppState.Hosts` stays as the "signed in or holding direct hosts, nothing connected" state, with no fields (a `data object`), so screens still switch on it.
- **Loading.** `showHosts()` and `refreshHosts()` load the catalog as today, but `refreshHosts()` now works in any signed-in state, connected included (the sheet calls it when it opens). A load while connected never changes `AppState`, and a load already running when a host connects keeps running (`connect` no longer cancels it, since the catalog outlives the host list). A 401 on it ends the sign-in (`endSignIn`), as today, unless the connected host is a direct one: that connection needs no GitHub sign-in, so it stays up and only the token and the account's tunnel hosts are dropped. The last loaded list stays shown while a refresh runs (`HostList.Loading` only when there is nothing yet), so opening the sheet does not flash a spinner.
- **`isLastUsed` and the connected host.** The catalog marks the last used host as today; the sheet marks the connected one from `AppState.Connected.host.id`, not from the catalog.
- **`switchHost(target: HostRef)`**, `@Synchronized`, the one way to change hosts:
  - From `Hosts`: what `connect(tunnel)` / `connect(direct)` do today (they become thin wrappers, so their tests stand).
  - From `Connected` to another host: cancel the watch job, forget expansions, cancel the diff job, stop the session (it saves its list, H7), create and start the new session, and swap `Connected(old)` for `Connected(new)` with `compareAndSet`; if the state is no longer that `Connected` (a sign-out won), stop the new session, as `swapToConnected` does (H5).
  - To the host already connected: nothing.
  - A direct host must still be on the saved list at the time of the call, as today.
- **Park and consent.** The new session starts parked if the app is out of sight (`parking.ifParked`), and is made under the target's own consent (direct hosts) or the tunnel consent, as `connect` does; the switch never carries one host's tier to another (AC-E6.2).
- A switch republishes the catalog's last-used marks and order (it does not pass through the host list, which used to rebuild them). A permission change (`setInteraction` / `setManagement`) restarts only the session that was connected when it was made, and `removeDirectHost` disconnects only if the removed host is still the connected one when it gets the lock.
- `disconnect()` stays, for a host the person wants to leave without picking another (N4).
- Auto-connect (`connectToLastHost`, H6) reads the catalog instead of `AppState.Hosts.list`.

### Acceptance criteria

- [x] **AC-N3.1** While connected, `refreshHosts()` loads the tunnel list into `hosts` and leaves `AppState` as it is. _`refreshingWhileConnectedLoadsTheTunnelHostsIntoTheCatalogAndLeavesTheStateAsItIs` (no state is set; a 401 there ends the sign-in)._
- [x] **AC-N3.2** `switchHost` from one connected host to another goes `Connected(a)` → `Connected(b)` with no `Hosts` state in between (a collector of `state` never sees one), and `a`'s session is stopped and has saved its list. _`switchingFromOneConnectedHostToAnother...` records every state as it is set; a mutation that parks the state on `Hosts` between the two failed it; `the session a switch leaves saves its list as it stops` counts the write before the debounce could run._
- [x] **AC-N3.3** A sign-out that lands during a switch wins, and the new session is stopped (the H5 cases, repeated for `switchHost`). _Two cases: a sign-out inside the factory's `create()`, and one held at the token wipe while the switch is made._
- [x] **AC-N3.4** A switch to a direct host connects at that host's own tier; a switch back to a tunnel host at the tunnel tier. _`AppControllerDirectHostsTest`: tunnel to direct and back (tier read from `interactionAllowed`, `canInteract` and `consentScope`), and direct to direct with the permission staying with each host._
- [x] **AC-N3.5** All existing `AppControllerTest` cases pass, edited only where they read the removed `AppState.Hosts` fields (which now read `hosts`). _Full `:core-ahp:test`: 1245 tests, 0 failures. Edited only to read `hosts` and `AppState.Hosts` as an object, plus the two cases noted in the Progress Log._

### Tests

`AppControllerTest`: refresh while connected; switch tunnel→tunnel, tunnel→direct, direct→tunnel, to the same host, to a removed direct host; a sign-out inside the factory's `create()` during a switch; the old session's cache save; parked switch; per-host consent after a switch. `HostSessionCacheTest` unchanged. A mutation check that an intermediate `Hosts` state fails AC-N3.2.

---

## N4 - The host sheet

### Problem

The host is a full page (`Page.HOST`, `HostScreen`) with the connected host and **Switch host**, or the list; the browser choice; and Sign out. Switching from it goes through "Not connected".

### Design

- Tapping the host row in the drawer opens a `ModalBottomSheet` (the same look as the session's Details sheet), **Hosts**. It calls `refreshHosts()` on open.
- **Contents**, top to bottom:
  1. **Connected**: the current host, its state line (`ConnectionLine`), and a **Disconnect** text button (to the "no host" drawer, N6). Absent when nothing is connected.
  2. **Other hosts**, one list, ordered: tunnel hosts online, then direct hosts, then tunnel hosts offline; "last used" and "not encrypted" as today. A tap calls `switchHost` and closes the sheet; the drawer shows the new host's sessions as they arrive (the cached list first, H7).
  3. **Add a host by address**, opening the existing `AddHostDialog`.
  4. When not signed in to GitHub: one row, "Sign in with GitHub to see your VS Code hosts".
  5. A footer link, **Manage hosts**, to Settings › Hosts (N5).
- A refresh icon in the sheet's header; a failed tunnel list shows its message in place of the tunnel rows and leaves the direct hosts.
- `Page.HOST` and `HostScreen` are deleted once N5 has taken the management parts. `ConnectedCard`, `HostRow`, `DirectRow` and `AddHostDialog` move into the sheet's file.

**Which hosts are listed (open).** Asked for: "other hosts you're connected to, hosts you've connected to in the past and not deleted, and the option to add a new connection". With one live host at a time, "connected to" means the current one. Tunnel hosts are not stored by this app: they are what GitHub lists for the account, so they cannot be deleted here, only hidden. Default: list every tunnel host GitHub returns and every saved direct host, with no hiding. The alternative is a local "hide this host" for tunnel hosts, kept in preferences by tunnel id, with a "Show hidden" row; it is small, but it is a second list to keep in step with GitHub's.

### Acceptance criteria

- [ ] **AC-N4.1** Tapping the host in the drawer opens the sheet over the drawer; Back or a swipe down closes it to the drawer.
- [ ] **AC-N4.2** While connected, the sheet lists the other tunnel and direct hosts; tapping one connects to it with no "Not connected" screen at any point, and the sheet closes.
- [ ] **AC-N4.3** Add a host works from the sheet, and the new host is listed at once.
- [ ] **AC-N4.4** Not signed in, the sheet lists the direct hosts and offers GitHub sign-in; a failed tunnel list still lists the direct hosts.
- [ ] **AC-N4.5** The sheet passes the accessibility test class (labels, touch sizes, the drag handle at 48 dp as fixed for Details).
- [ ] **AC-N4.6** On the Pixel: switching from the tunnel host to `ahpd` and back shows each host's own sessions and never the other's (AC-E6.5).

### Tests

A new `HostSheetTest` (instrumented): listing, ordering, switch callback, add, not-signed-in, failed list, the connected host absent from "other hosts". `DirectHostsUiTest` moves its add and remove cases to the sheet and Settings. `AccessibilityTest` case. AC-N4.6 needs the phone.

---

## N5 - Host management moves into Settings

### Problem

Asked: "consider moving the host selection/management into the settings menu".

### Recommendation

Move **management** into Settings and keep **selection** in the drawer. Choosing a host is something done many times a day and wants one tap from where the sessions are; the account and the saved addresses are set up once. Putting selection behind Settings would make a switch three taps (menu, Settings, host) and hide the one thing the drawer header is for.

### Design

- Settings gets a first section, **Hosts**:
  - **GitHub**: "Signed in. Renews itself." / "Signed in until …" with **Sign out** (the existing confirm dialog), or **Sign in with GitHub**.
  - **Hosts you added**: each direct host with its address and a remove button (the existing confirm dialog, which says the token and consent go too), and **Add a host by address**.
- The Permissions section keeps its "These apply to …" line, which already says which host it acts on.
- `Page.HOST` is removed (N4); Settings is still `Page.SETTINGS`, reached from the drawer's gear.

### Acceptance criteria

- [ ] **AC-N5.1** Sign in, sign out (with its confirm), add and remove a direct host are all in Settings › Hosts, and nowhere else but the sheet's Add.
- [ ] **AC-N5.2** Removing the connected direct host from Settings disconnects it first and lands on the "no host" drawer (N6), and its token, cached list and consent are gone (AC-E6.4).
- [ ] **AC-N5.3** Signing out from Settings leaves direct hosts and their tokens alone (AC-E6.4), and a live tunnel connection ends.

### Tests

`ScreensTest` / `DirectHostsUiTest` for the Settings section; the existing `AppControllerTest` sign-out and remove cases cover the controller.

---

## N6 - The drawer as home, and Back

### Problem

With no session open the main page is `MainEmpty`, which only points at the drawer or the host page. Back closes a session to that page, and Back there leaves the app; the drawer is an extra step every time.

### Design

**What is on screen**

| State | What shows |
|---|---|
| Connected, a session open | The session, drawer closed (as today) |
| Connected, no session | The drawer, open and held open |
| Signed in or holding direct hosts, nothing connected (`AppState.Hosts`) | The drawer, open and held open; its host row says "Choose a host" and the session area says "No host connected" with a **Choose a host** button that opens the host sheet. On a cold start with no last host, the sheet opens by itself, once |
| Auto-connecting at cold start (H6) | The drawer with the last host's name and "Connecting…", and its cached sessions (H7) |

- **Held open.** `rememberDrawerState(initialValue, confirmValueChange = { it == DrawerValue.Open || open != null })`: with no session, a scrim tap or a swipe cannot close the drawer, so there is never a blank page behind it. When a session opens, the drawer closes as today (`showSession` → `closeDrawer`); when it closes, the drawer opens (`LaunchedEffect(open == null)`).
- **Behind the drawer**, visible as the scrim strip on a phone: an empty `Surface` in the background colour, no text. `MainEmpty` and its tags (`main_empty`, `open_sessions`, `connect_host`, `empty_title`, `open_inbox`) are deleted.
- **The "N need you" button** that `MainEmpty` led with is covered by the inbox badge in the action row (N2).
- **A notification tap** (`focusSession`) opens the session and closes the drawer, as a menu tap does.

**Back**

| Where | Back does |
|---|---|
| A diff, a changeset, a terminal, a subagent's transcript | Closes it, as today |
| A session (drawer closed) | Closes the session; the drawer opens |
| The drawer, no session | Leaves the app: `activity.moveTaskToBack(true)`, not `finish()`, on every API level, so the activity and the connection stay as they are (Android 12+ does this by default for a root activity; API 26–30 would finish it) |
| The drawer, over a session | Closes the drawer, back to the session (the platform's convention, and what a person who opened the menu by mistake expects). Decided 2026-10-28 |
| The host sheet, a dialog | Closes it |
| Inbox, Settings | Back to the drawer (they are pages over it, as today) |

- Predictive back: Material 3's `ModalNavigationDrawer` and `ModalBottomSheet` animate it themselves; the leave-the-app case shows the system's own back-to-home animation once `moveTaskToBack` is behind an `enabled` `BackHandler` that is turned off. Check on the Pixel with predictive back on.
- The drawer is the default width (`ModalDrawerSheet`, at most 360 dp). **(open)** On a phone, the drawer could fill the width while it is the home page, so it does not look like a menu over nothing. Default: the standard width, so the open and the held-open drawer look the same.

### Acceptance criteria

- [ ] **AC-N6.1** No screen says "No session open" or "Not connected" as a page; with no session, the drawer is open and cannot be swiped or tapped shut.
- [ ] **AC-N6.2** Back from a session closes it and shows the drawer open; Back from the drawer with no session puts the app in the background, and opening it again shows the drawer as it was, connected.
- [ ] **AC-N6.3** Back from a terminal, changeset, diff or subagent transcript behaves as before.
- [ ] **AC-N6.4** With nothing connected, the drawer offers Choose a host, and a cold start with no last host opens the host sheet once.
- [ ] **AC-N6.5** A notification tap with the drawer open opens the session with the drawer closed.
- [ ] **AC-N6.6** Rotation keeps the drawer's state and the open session.
- [ ] **AC-N6.7** On the Pixel with predictive back: the drawer, the sheet and the leave-the-app gesture each animate, and none leaves a half-closed drawer.

### Tests

`SessionScreensTest`: Back from a session opens the drawer; the drawer cannot be closed with no session; it closes when a session opens. A Back test that asserts `moveTaskToBack` (through a fake `Activity` hook or `ActivityScenario` state `CREATED`). `ScreensTest` cases for `MainEmpty` are removed or moved to the drawer. AC-N6.7 needs the phone.

---

## Verification on the phone

Record runs in `docs/verification/navigation.md`: date, build commit, Android version, host kinds, what was done, what was seen. In particular: switching tunnel ↔ `ahpd` (AC-N4.6), Back to background and return with monitoring on (AC-N6.2), predictive back (AC-N6.7), links with no `<queries>` (AC-N1.4), and TalkBack across the new action row (not run in earlier passes).

## Open questions

1. ~~**Back with the drawer open over a session:** close the drawer or leave the app? (N6)~~ Decided 2026-10-28: close the drawer.
2. **"Share picker":** default browser plus Share in the link dialog (default), or an open-with chooser on every link? (N1)
3. **Which hosts the sheet lists:** every tunnel host GitHub returns and every saved direct host (default), or a local "hide" for tunnel hosts? (N4)
4. **Settings in the action row** (default) or as an icon at the bottom of the drawer? (N2)
5. **Drawer width** while it is the home page: standard (default) or full width? (N6)
6. Should Disconnect stay in the sheet at all, now that switching never needs it? Default: yes, as a text button, for leaving a host that misbehaves without picking another.

## Progress Log

| Date | Entry |
|---|---|
| 2026-10-28 | Spec drafted from the person's requests and a read of the code at `543a121`. Nothing built. |
| 2026-10-28 | Open question 1 decided: Back with the drawer open over a session closes the drawer. Nothing built. |
| 2026-10-28 | **N1 built (uncommitted).** Removed `Browsers`, `BrowserOption`, the browser row and picker on the host screen, `UiSettings.browser` / `setBrowser`, the `browser` parameter of `openInBrowser` / `openLink` / `launchWeb`, the two manifest `<queries>` and the now unused `AppIcons.Globe`; `UiSettings` drops the stored `browser` key at construction. `launchWeb` is a Custom Tab with no package, falling back to `ACTION_VIEW` + `BROWSABLE`. `LinkConfirmDialog` has a Share button (tag `link_share`) for web links only; it calls a new `onShareLink` (threaded through `ChatScreen` / `ChatPane`, default no-op) that `AppRoot` wires to `shareLink` (`shareIntent(url)`: `createChooser(ACTION_SEND, text/plain, EXTRA_TEXT = url)`); the dialog closes on Share. README lines that described the browser choice updated. **Ran** (Windows, SDK from the main checkout, API 36 emulator `ahp-api36`): `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, and instrumented `ChatScreenTest` (61, incl. 3 new Share cases), `LinksTest` (1, new), `UiSettingsInstrumentedTest` (1, new), `ScreensTest` (21; the six browser cases replaced by one "no browser choice" case), `DirectHostsUiTest` (10), `AccessibilityTest` (23; the browser-picker case removed), each class on its own, all passing; `:app:lintDebug` builds successfully. **Not verified:** AC-N1.2 and AC-N1.4 (a link opening in the default browser, and with no `<queries>` on Android 11+) need the Pixel or an API 30 emulator (none installed); the share sheet was not looked at; the Share button's layout and 48 dp target in the three-button dialog was not checked by the accessibility class (it only builds screens, not the link dialog). **Deviation:** none from the spec; `onShareLink` is a callback rather than the dialog reaching for a `Context`, so the Share case can be asserted in a Compose test. |
| 2026-10-28 | **N2 built (uncommitted).** `DrawerContent` now has the host row (down chevron, still opens the host page until N4), then one 48 dp row: Inbox (`BadgedBox`, tag `drawer_inbox`, spoken "Inbox, 2 need you"), Archived (`IconToggleButton` with the new `AppIcons.Archive`, tag `archived_toggle`, spoken "Show archived, 3" / "Hide archived", `stateDescription` On/Off, a count badge while hidden, shown only when something is archived or shown), a spacer, New session (`NewSessionIconButton`, a `FilledTonalIconButton` in `Manage.kt` that opens the same dialog; only with the manage tier; tag `new_session`) and Settings (tag `settings`). Every button has a `PlainTooltip` (long press). Settings left the bottom of the drawer and Archived the Sessions header, so the session model is built in `DrawerContent` and passed to `SessionList`. Added `AppIcons.ChevronDown` and `AppIcons.Archive`; removed the now unused `AppIcons.ChevronRight`. README side-menu row updated. **Ran** (Windows, SDK from the main checkout, API 36 emulator `ahp-api36`): `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, `:app:lintDebug` (build successful), and instrumented `SessionScreensTest` (61, incl. 7 new or reworked N2 cases), `AccessibilityTest` (24, new `theDrawerActionRowInBothThemes`), `ManageUiTest` (64, unchanged and passing), each class on its own, all passing. **Not verified:** nothing seen on a phone; TalkBack not run across the row; the tooltip is asserted by a long press in a Compose test, not looked at; the `MainEmpty` page still uses the labelled `NewSessionButton` (N6 deletes it). **Deviation:** none from the spec; the host row keeps its `Open host` click label until N4. |
| 2026-10-28 | **N3 built (uncommitted).** New `HostCatalog(list, direct, tokenExpiresAt, renews)` and `AppController.hosts: StateFlow<HostCatalog>`; `AppState.Hosts` is now a `data object`. `showHosts()` (entering the host list: restore, sign-in, disconnect) always starts the catalog at `Loading`; `refreshHosts()` works from `Hosts` and `Connected`, keeps a `Loaded` or `Empty` list on show while the next one loads (a `Failed` one becomes `Loading`) and never sets `state`. A load publishes to the catalog whether or not a host is connected, and not after a sign-out (guarded on the token); a 401 on it still calls `endSignIn`. `endSignIn` resets the catalog to `NotSignedIn` plus the direct hosts; `adopt` (token renewal) and `publishDirect` write to the catalog; `connectToLastHost` and `reachOnline` read it (`reachOnline` waits on `combine(state, hosts)`). New `switchHost(target)`: from `Hosts` it is what `connect` did; from `Connected(a)` to another host it cancels the watch, forgets expansions, cancels the diff job, stops `a`'s session (which saves its list), makes and starts the new session under the target's own consent, and `compareAndSet`s `Connected(a)` for `Connected(b)`, stopping the new session if a sign-out won. The same host is ignored, as is a direct host no longer on the saved list (checked before `a` is touched). `connect(tunnel)` / `connect(direct)` are wrappers that still ignore any state but `Hosts`. `connect` no longer cancels an in-flight list load, since the catalog outlives the screen. `swapToConnected` takes the state it swaps from, which retires its retry loop (a `data object` cannot differ from itself). App module: `AppRoot` collects `controller.hosts` and passes it to `Shell` in place of the removed fields, so the host screen is unchanged. `docs/extensions-spec.md` E6 line about where the lists live updated. **Ran** (Windows, SDK from the main checkout): `:core-ahp:test` in full, 1245 tests, 0 failures (`AppControllerTest` 172, `AppControllerDirectHostsTest` 23); `:app:compileDebugKotlin` and `:app:compileDebugAndroidTestKotlin` build. A mutation (parking the state on `Hosts` between the two connections inside `switchHost`) failed the AC-N3.2 case, then was reverted. **Tests:** 17 new (`AppControllerTest`: refresh while connected, keep-the-list rule, 401 while connected, sign-out empties the catalog, tunnel to tunnel with no `Hosts` state seen and the old session stopped, the old session's cache save, same host, ignored from sign-in, from the host list, two sign-out races, parked switch; `AppControllerDirectHostsTest`: tunnel to direct and back, direct to direct consent, removed host, removing the switched-to host, add and remove while connected). Existing cases were edited only to read `hosts` and `AppState.Hosts` as an object (`MonitorCoordinatorTest`, `WatchdogDecisionTest`, the two controller test classes), except two: `a host list replaced between reading the state and the swap...` now races `refreshHosts()` instead of `Hosts.copy(renews = ...)` (a `data object` has nothing to copy; the case now checks that a catalog change under the swap does not stop it), and `when GitHub refuses the renewal...` loads a different second list and waits for it, since a refresh from `Loaded` no longer passes through `Loading`. **Not verified:** instrumented tests were not run (no UI changed beyond `AppRoot` reading the catalog; they compile); `:app:lintDebug` not run; nothing seen on a phone. **Deviations:** none from the spec in behaviour. `AppState.Hosts` having no fields means `signIn()` from a connected direct host with no GitHub sign-in still does nothing (it only acts from `Hosts` / `SignedOut`); the sheet's "Sign in with GitHub" row (N4) will need that decided. |
| 2026-10-28 | **Review fixes for N1 to N3 (uncommitted).** Core: a 401 on a host-list load while a *direct* host is connected no longer takes the screen: `loadHosts` calls a new `dropSignInKeepingConnection` (token, vault entry, tunnel consent and the catalog's tunnel hosts go; the connection and its saved list stay) instead of `endSignIn`; with a tunnel host or none connected it still ends the sign-in. `setInteraction` / `setManagement` restart only the session that was connected when the choice was made (`restartIfStillConnected`), and `removeDirectHost` disconnects through a `@Synchronized` `disconnectFrom(id)` that checks the id again under the lock, so neither can hit the host a concurrent `switchHost` moved to. `swapToConnected` republishes the catalog's last-used marks and order (`republishLastUsed`, with the ordering shared with `loadHosts`). App: `onShareLink` on `ChatScreen` / `ChatPane` is nullable and `LinkConfirmDialog` shows Share only when given one (no dead button); the archived toggle's label is fixed ("Show archived"), its state is "On" / "Off, N hidden" and it has a filled container while checked (this was already in the tree from the interrupted pass; spec text and tests now match); `AppContainer`'s `UiSettings` comment no longer mentions the browser. Docs: `docs/spec.md` Design pass 4 rows for the host screen, links and "not seen" now say the browser choice was removed by N1; Status line brought up to date; AC-N1.3 unticked (the `AppRoot` wiring, share sheet and dialog layout were never run; the tick claimed more than the tests show). **Tests added:** `AppControllerDirectHostsTest`: 401 while a direct host is connected leaves it up and drops the sign-in; a permission saved after a switch reconnects nothing on the new host (uses a new `hold` on `FakeInteractionSettings`); a switch while connected moves the last-used mark and order. `ChatScreenTest`: no Share without a way to share. No deterministic test for the `removeDirectHost` race (it needs two threads on the controller lock). **Not verified:** instrumented tests were not re-run after the nullable `onShareLink` and the new `ChatScreenTest` case (compile only); nothing on a phone; the `removeDirectHost` race is argued from the code. |
