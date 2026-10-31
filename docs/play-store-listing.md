# Google Play Console & Release Packaging Guide (`P1`, `P3`, `M4`)

This guide contains the exact copy, questionnaire answers, and build commands needed to publish **AHP Client** (`dev.ahpclient`) to Google Play Internal / Closed Testing and Production.

---

## 1. Building a Signed Release AAB / APK (`P1`)

[`app/build.gradle.kts`](../app/build.gradle.kts) automatically signs the `release` build type when all four signing credentials are supplied via environment variables or Gradle properties (`-PAHP_KEYSTORE_FILE=...` or `~/.gradle/gradle.properties`), and falls back to an unsigned shrunk build when they are absent (so CI never breaks).

### Create an Upload Keystore (once)
```powershell
keytool -genkeypair -v `
  -keystore "$HOME\.android\ahp-upload-keystore.jks" `
  -storetype JKS `
  -keyalg RSA -keysize 4096 -validity 10000 `
  -alias ahp-upload
```

### Build Signed App Bundle (`.aab` for Google Play) and APK (`.apk`)
```powershell
$env:AHP_KEYSTORE_FILE = "$HOME\.android\ahp-upload-keystore.jks"
$env:AHP_KEYSTORE_PASSWORD = "<keystore-password>"
$env:AHP_KEY_ALIAS = "ahp-upload"
$env:AHP_KEY_PASSWORD = "<key-password>"

.\gradlew.bat :app:bundleRelease :app:assembleRelease
```
- Signed Play Store bundle: `app/build/outputs/bundle/release/app-release.aab`
- Signed release APK: `app/build/outputs/apk/release/app-release.apk`
- R8 mapping file (upload to Play Console for deobfuscated stack traces): `app/build/outputs/mapping/release/mapping.txt`

---

## 2. Store Listing Copy

- **App name (<= 30 chars):** `AHP Client`
- **Short description (<= 80 chars):**
  `Monitor, review diffs, and steer your coding-agent sessions from your phone.`
- **Full description (<= 4000 chars):**
  ```text
  AHP Client connects your Android phone directly to coding-agent hosts running the Agent Host Protocol (AHP) — either through Microsoft Dev Tunnels (VS Code Remote Tunnel Access) or directly to an ahpd WebSocket server over your LAN or Tailscale tailnet.

  FREE TIER (READ-ONLY MONITOR)
  • Connect to your VS Code Remote Tunnel or direct ahpd host
  • Watch live streaming transcripts, tool calls, reasoning steps, and terminal output
  • Inspect unified side-by-side and inline file diffs and syntax-highlighted files
  • Search and filter sessions by status, repository, or keyword
  • Zero telemetry, zero cloud relay servers — connections go straight from your phone to your machine

  PRO UNLOCK (ONE-TIME PURCHASE)
  • Act for me: send and steer prompts, answer agent questions, and approve or deny tool calls (protected by your phone's biometric / screen-lock check)
  • Look after sessions: start new sessions, switch models/agents, and archive or remove chats
  • Background monitoring & notifications: keep a live connection in the background and get instant notifications (with inline reply and Approve/Deny actions) the moment an agent needs input or finishes a task
  • Quick Settings tile to toggle background monitoring from the notification shade
  • Concurrent multi-host connections across multiple machines

  DISCLAIMER
  AHP Client is an independent third-party client for the open Agent Host Protocol and is not affiliated with, endorsed by, or sponsored by Microsoft Corporation or GitHub, Inc. Visual Studio Code and GitHub are trademarks of their respective owners. VS Code tunnel connections rely on Microsoft's Visual Studio Tunnel Service and your own GitHub/VS Code account; direct ahpd WebSocket hosts connect independently.
  ```

---

## 3. One-Time In-App Product (`M4`)

In **Play Console -> Monetize with Play -> Products -> In-app products -> Create product**:

- **Product ID:** `ahp_pro_unlock` *(must match [`BillingGateway.PRODUCT_ID`](../core-ahp/src/main/kotlin/dev/ahpclient/app/ProEntitlement.kt))*
- **Name:** `AHP Client Pro Unlock`
- **Description:** `One-time unlock for sending prompts, biometric tool approvals, session management, background notifications, and multi-host connections.`
- **Status:** Active
- **Price:** One-time non-consumable (recommended **\$4.99 – \$9.99 USD**).

### License Testing (Free Test Purchases on Your Phone)
In **Play Console -> Settings -> License testing**, add your Google account email address to the License Testers list. Once added, tapping **Purchase Pro** on a release/closed-track build opens the Google Play test purchase sheet (which lets you simulate `Approve`, `Decline`, or `Slow test card — approves after a few minutes` without charging your card).

---

## 4. `FOREGROUND_SERVICE_SPECIAL_USE` Declaration (`P3`)

In **Play Console -> Policy and programs -> App content -> Foreground service permissions**:

- **Declared permission:** `FOREGROUND_SERVICE_SPECIAL_USE`
- **Subtype in `AndroidManifest.xml`:**
  `Live connection to the user's own coding-agent host, to alert when a session needs them`
- **Use-case explanation (copy-paste into Play Console):**
  ```text
  AHP Client connects directly to the user's own self-hosted coding-agent workstation (via WebSocket over Microsoft Dev Tunnels or a local/VPN ahpd server) using the Agent Host Protocol. Because the user's self-hosted development workstation does not have a cloud push-notification server (FCM), the app can only detect when a long-running coding agent pauses for user approval/input or finishes its task by keeping the user-initiated WebSocket connection open while the user switches to another app or locks their phone.

  The foreground service (MonitorService) runs ONLY when the user explicitly turns on the "Monitor in background" switch in Settings (or via the app's Quick Settings tile), displays an ongoing notification showing the connected host and status, and stops immediately when the user turns the switch off or disconnects.
  ```
- **Video link requirement:** Record a 20–30 second screen recording showing:
  1. Opening AHP Client connected to a host.
  2. Opening **Settings** and toggling **Monitor in background** ON.
  3. Pulling down the Android notification shade to show the ongoing `"Monitoring <host>"` foreground service notification and an `"Input needed"` or `"Session finished"` alert.
  4. Toggling **Monitor in background** OFF and showing the ongoing notification disappearing immediately.

---

## 5. Data Safety Form Answers (`P3`)

In **Play Console -> Policy and programs -> App content -> Data safety**:

1. **Does your app collect or share any of the required user data types?**
   - Select **No**.
   - *Reason:* All authentication tokens (`GitHub` OAuth device token, direct host bearer tokens) and session data remain exclusively on the user's device (encrypted in `AndroidKeyStore`) and are transmitted only directly between the user's device and the user's own configured host endpoints. No data is sent to the developer or any third-party analytics/ad service.
2. **Privacy Policy URL:**
   - Publish [`docs/privacy-policy.md`](privacy-policy.md) to the standalone public [`GreenJeans/ahp-client-legal`](https://github.com/GreenJeans/ahp-client-legal) repo using [`tools/publish-legal-docs.ps1`](../tools/publish-legal-docs.ps1), enable GitHub Pages on `ahp-client-legal`, and paste `https://greenjeans.github.io/ahp-client-legal/privacy-policy` under **Policy and programs -> App content -> Privacy policy**. (The full Privacy Policy and Open-Source Licenses are also built into the app behind the **About** button on the Sign-In and Settings screens.)
3. **App Access Instructions for Play Reviewers:**
   - Under **App content -> App access**, explain that the app connects to a user's self-hosted Agent Host Protocol server (`ahpd`) or VS Code Remote Tunnel, and provide either:
     - Instructions for exploring the sign-in / direct-host setup (`Connect to a host directly`) and Settings UI without an external host, or
     - A read-only demo `wss://` direct host address + bearer token running a sample session during review.
   - Note for reviewers regarding GitHub Sign-In:
     ```text
     AHP Client is an independent client for the open Agent Host Protocol (https://github.com/microsoft/agent-host-protocol). Users can either connect directly to an ahpd WebSocket host by address ("Connect to a host directly" — no GitHub account required) or sign in with GitHub via OAuth Device Flow to discover their own VS Code Remote Tunnel Access hosts, which requests authorization for Microsoft's "Visual Studio Tunnel Service" GitHub app used by VS Code Remote Tunnel Access.
     ```
