# App Store Connect Listing & Release Guide (iOS)

Copy, answers, and commands for publishing **Hostline** (`com.greenjeans.ahpclient`) to TestFlight and the App Store.
The Android equivalent is [`play-store-listing.md`](play-store-listing.md).

> iOS differs from Android in three ways that the copy below respects:
> no on-device Gemini Nano (quick replies use the cloud/LAN providers you configure), no Quick Settings tile,
> and background monitoring only lasts as long as iOS lets a backgrounded app finish its work — there is no
> always-on foreground service.

---

## 1. Build & Upload to TestFlight

```bash
ASC_KEY_ID=<key id> ASC_ISSUER_ID=<issuer id> tools/ios-testflight.sh
```

Uploads authenticate with an App Store Connect API key (Users and Access → Integrations → App Store Connect API).
The key needs the **Admin** role: an App Manager key is refused access to the cloud-managed distribution
certificate, so no App Store profile can be made. Keep `AuthKey_<id>.p8` in `~/.appstoreconnect/private_keys/`
and never commit it.

The script archives a Release build (`Platform.isDebugBinary == false`, so Pro starts **locked** and StoreKit is live),
exports it with [`iosApp/ExportOptions.plist`](../iosApp/ExportOptions.plist) and uploads it to App Store Connect.
Every upload needs a new `CURRENT_PROJECT_VERSION` in [`Signing.xcconfig`](../iosApp/Configuration/Signing.xcconfig) — App Store Connect rejects a
repeated build number.

After processing (~5–15 min) the build appears under **TestFlight**. `ITSAppUsesNonExemptEncryption = false` is set in
`Info.plist`, so no export-compliance question is asked.

### Testers
- **Internal testing** (App Store Connect users on your team, up to 100): available as soon as the build processes; no review.
- **External testing** (anyone with an email or the public link, up to 10,000): first build of each version goes through
  Beta App Review (usually < 24 h). Fill in **Test Information** (below) first.

### Testing the in-app purchase
TestFlight purchases use the sandbox and are free. The product must exist in App Store Connect with ID
`ahp_pro_unlock` (matches [`BillingGateway.PRODUCT_ID`](../core-ahp/src/main/kotlin/dev/ahpclient/app/ProEntitlement.kt)).
A product still in **Missing Metadata** (e.g. no price) is **not** returned by StoreKit, so the purchase button reports
"Product ahp_pro_unlock not found" until it reaches **Ready to Submit** — set a price, display name, description and
review screenshot first.

---

## 2. App Information

| Field | Value |
|---|---|
| Name (≤ 30) | `Hostline: AHP Client` |
| Subtitle (≤ 30) | `Steer your coding agents` |
| Bundle ID | `com.greenjeans.ahpclient` |
| SKU | `hostline-ios` |
| Primary category | Developer Tools |
| Secondary category | Productivity |
| Copyright | `2026 Tyler Morita` *(the year plus the rights holder; App Store adds ©)* |
| Content rights | Does not contain, show, or access third-party content |
| Privacy Policy URL | `https://greenjeans.github.io/ahp-client-legal/privacy-policy` |
| Support URL | `https://github.com/GreenJeans/ahp-client-legal/issues` |
| Marketing URL | *(optional — leave blank)* |

### Promotional text (≤ 170, editable without a new build)
```text
Watch your coding agents work, review their diffs, and approve tool calls with Face ID — straight from your iPhone or iPad to your own machine. No cloud relay.
```

### Keywords (≤ 100, comma-separated, no spaces)
```text
agent,coding,AI,copilot,vscode,tunnel,remote,developer,diff,terminal,MCP,tailscale,ahp,claude,monitor
```
*(97 characters. Avoid other companies' trademarks in keywords beyond plain descriptive terms if Review objects.)*

### Description (≤ 4000)
```text
Hostline connects your iPhone or iPad directly to coding-agent hosts running the Agent Host Protocol (AHP) — either through Microsoft Dev Tunnels (VS Code Remote Tunnel Access) or straight to an ahpd WebSocket server over your LAN or Tailscale tailnet.

No host yet? Tap "Try demo workspace" to explore a sample workspace with live transcripts, diffs and tool approvals — no account or server needed.

FREE: WATCH YOUR AGENTS
• Connect to your VS Code Remote Tunnel or a direct ahpd host
• Live streaming transcripts, tool calls, reasoning steps and terminal output
• Full-screen Reader view, pinned prompt bar, word-wrap and table-fit controls
• Per-chat changesets, file diffs, canvases, annotations and host terminals
• Search and filter sessions by status, repository or keyword
• No telemetry and no relay servers — your phone talks straight to your machine

HOSTLINE PRO (ONE-TIME PURCHASE)
• Act for me: send and steer prompts with file and image attachments, answer agent questions, and approve or deny tool calls with risk and diff previews — protected by Face ID or Touch ID
• Quick replies and completions: AI-suggested replies from the provider you choose (Google Gemini, Anthropic, or any OpenAI-compatible server, including local models on your own network), plus live host #, / and @ completions
• Look after sessions: start, rename and fork sessions, manage MCP servers and customizations, control host automations, and discard changeset edits
• Notifications: get alerted when an agent needs input or finishes, with inline reply and Approve / Deny actions
• Connect to several hosts at once

ABOUT
Hostline is an independent client for the open Agent Host Protocol. It is not affiliated with, endorsed by, or sponsored by Microsoft Corporation or GitHub, Inc. Visual Studio Code and GitHub are trademarks of their respective owners.
```

### What's New in This Version (0.2.0)
```text
First iOS release of Hostline:
• Live agent transcripts, diffs, terminals and changesets
• Face ID–protected tool approvals and "Act for me" with Hostline Pro
• Chat switcher for forks, side chats and sub-agents
• Built-in demo workspace — explore without a host
```

---

## 3. In-App Purchase (`ahp_pro_unlock`, Non-Consumable)

| Field | Value |
|---|---|
| Reference name | `Hostline Pro Unlock` |
| Product ID | `ahp_pro_unlock` |
| Price | Pick a tier — Android guidance is **$4.99–$9.99** |
| Display name (≤ 30) | `Hostline Pro` |
| Description (≤ 45) | `Act for me, approvals, alerts & multi-host` |
| Review screenshot | [`store-assets/app-store/review/iap.png`](../store-assets/app-store/review/iap.png) — the Hostline Pro purchase dialog (Settings → Pro → Unlock Pro) |
| Review notes | `Unlocks sending prompts, Face ID tool approvals, session management, notifications and multi-host. Purchase and "Restore purchase" are in Settings → Pro.` |

**Submit the IAP with the first app version** (attach it on the version page under *In-App Purchases and
Subscriptions*); a first IAP cannot be submitted on its own.

---

## 4. App Privacy ("Nutrition Label")

- **Data collection:** *No, we do not collect data from this app.*
  The developer receives nothing: tokens live in the iOS Keychain, session data goes only between the device and
  the user's own hosts, and purchases go through Apple.
- Quick-reply text is sent to the AI provider **the user configures with their own API key**; that is the user's
  own account with that provider, not collection by the developer. The in-app provider setup explains what is sent.
- The published policy is [`privacy-policy.md`](privacy-policy.md), copied to the `ahp-client-legal` repository that
  serves the Privacy Policy URL. It covers both Android and iOS (Keychain, Face ID, local notifications, StoreKit and
  the optional AI quick-reply providers) — publish the updated copy there before submitting for review.
- `PrivacyInfo.xcprivacy` already declares: no tracking, no collected data, UserDefaults (`CA92.1`) and
  file timestamps (`C617.1`).

## 5. Age Rating

Answer **None / No** to every content question (no violence, mature themes, gambling, unrestricted web browsing,
or user-to-user messaging — chats are between the user and their own agents). Expected rating: **4+**.

## 6. App Review Information

- **Sign-in required:** No — the demo workspace needs no account.
- **Notes:**
  ```text
  Hostline is a client for the open Agent Host Protocol (https://github.com/microsoft/agent-host-protocol). It connects to coding-agent hosts the user runs on their own computer — via VS Code Remote Tunnels (GitHub sign-in) or directly by address.

  To review without a host or account: on the first screen tap "Try demo workspace". It opens a built-in, offline sample workspace with live transcripts, file diffs, sub-agent chats and tool approvals.

  The one-time purchase "Hostline Pro" (ahp_pro_unlock) unlocks sending prompts, approving tool calls, session management, notifications and multiple hosts. "Restore purchase" is in Settings → Pro.

  "Sign in with GitHub" uses GitHub's OAuth Device Flow only to list the user's own VS Code tunnels; it is not an account with us.
  ```
- **Contact:** your name, phone and email (App Review only).

## 7. Screenshots

Generated into [`store-assets/app-store/`](../store-assets/app-store/):

| Display | Size (portrait) | Folder |
|---|---|---|
| iPhone 6.9" (required; scaled down for smaller iPhones) | 1320 × 2868 | `iphone-6.9/` |
| iPad 13" (required because the app runs on iPad) | 2064 × 2752 | `ipad-13/` |

Raw simulator captures (no frame) are in `raw/` for re-use. Up to 10 per display; the first 3 show in search results.
