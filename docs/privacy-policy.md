# Privacy Policy — Hostline (`com.greenjeans.ahpclient`)

**Effective Date:** November 11, 2026

Hostline ("the App", formerly "AHP Client") is an Android, iPhone and iPad client for the [Agent Host Protocol (AHP)](https://github.com/microsoft/agent-host-protocol). It lets you monitor and interact with coding-agent sessions running on your own computers via Microsoft Dev Tunnels or a direct `ahpd` WebSocket connection.

This policy covers the App on both Android (Google Play) and iOS / iPadOS (Apple App Store). Where the two differ, each platform is described separately.

## 1. Summary: Zero Developer Data Collection

- **No developer servers:** The developer of Hostline does not operate any backend server, relay service, or cloud database for the App.
- **No analytics, telemetry, or ads:** The App contains no analytics SDKs, no crash-reporting SDKs, no tracking identifiers, and no advertising networks. On iOS the App does not track you as defined by Apple's App Tracking Transparency framework.
- **Your data stays between your device and your hosts:** Prompts, code diffs, terminal output, file contents, and session transcripts travel directly between your device and the agent host(s) you configure. The developer never sees, receives, or stores any of your data.

## 2. Information Stored on Your Device

To function, the App stores the following data locally on your device only:

1. **Authentication Credentials and API Keys:**
   - **GitHub OAuth Device-Flow Tokens** (when you sign in with GitHub to discover your Microsoft Dev Tunnels), **Direct Host Bearer Tokens** (when you add an `ahpd` host by address), and, if you set up AI quick replies, the **API key for the AI provider you choose** (see Section 3).
   - **Android:** encrypted at rest using **AES-256-GCM** with non-exportable keys held in the **Android Keystore (`AndroidKeyStore`)**.
   - **iOS / iPadOS:** encrypted at rest with authenticated encryption under a random 256-bit key held in the **iOS Keychain** with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, so the key never leaves the device and is never synced to iCloud Keychain or included in backups.
2. **Cached Session List:**
   - To display your recent sessions immediately on launch before a network connection settles, the App caches session titles, URIs, status values, and timestamps locally.
   - This cache is encrypted the same way as your credentials (Android Keystore / iOS Keychain key) and is erased immediately when you sign out.
3. **App Preferences & Pro Entitlement State:**
   - Local settings (theme, font size, selected host ID, per-host interaction/management switches, background monitoring and notification toggles, quick-reply provider settings, and local Pro unlock state) are stored in the App's private sandbox — DataStore/SharedPreferences on Android and `UserDefaults` on iOS.
4. **No Cloud Backup of Secrets:**
   - **Android:** Auto Backup and device-to-device transfer are disabled (`android:allowBackup="false"`).
   - **iOS / iPadOS:** encrypted credential and cache files are stored in a folder marked excluded from iCloud and device backups, and the Keychain key that decrypts them is device-only.

## 3. Direct Network Connections

Depending on how you use the App, your device communicates directly with:

- **GitHub (`https://github.com/login/device/...`, `https://github.com/login/oauth/access_token`):** Used only when you start GitHub Device Flow sign-in to obtain and refresh tokens for Microsoft Dev Tunnels. Governed by the [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
- **Microsoft Dev Tunnels (`https://global.rel.tunnels.api.visualstudio.com`, `wss://*.devtunnels.ms`):** Used to list and connect to your own VS Code / AHP remote tunnels over TLS (`wss://`). Governed by the [Microsoft Privacy Statement](https://privacy.microsoft.com/privacystatement).
- **Direct `ahpd` Hosts (`ws://` or `wss://`):** Connects directly to the IP address or hostname you enter (such as over your local network or a VPN/Tailscale tailnet). On iOS, the system may ask your permission for local network access the first time.
- **AI quick-reply provider (optional, off until you set it up):** If you configure quick replies with your own provider — Google Gemini (`https://generativelanguage.googleapis.com`), Anthropic (`https://api.anthropic.com`), or any OpenAI-compatible endpoint you enter, including a model running on your own network — the App sends that provider a short excerpt of the latest turn of the chat you are viewing so it can suggest replies, authenticated with your own API key. That request goes directly from your device to the provider under your own account with them and is governed by that provider's privacy policy; the developer never receives it. On Android, quick replies can instead run fully on-device with Gemini Nano, in which case nothing leaves the device.
- **Purchases:** When you buy or restore the optional one-time **Hostline Pro** unlock (`ahp_pro_unlock`), the transaction is processed entirely by the platform store — **Google Play Billing** on Android ([Google Privacy Policy](https://policies.google.com/privacy)) or the **Apple App Store / StoreKit** on iOS ([Apple Privacy Policy](https://www.apple.com/legal/privacy/)). The developer receives no payment details and no information that identifies you.

## 4. Device Permissions

### Android
- **`INTERNET` and `ACCESS_NETWORK_STATE`:** Required to connect to your agent hosts and detect network transitions for automatic reconnection.
- **`USE_BIOMETRIC`:** Used locally via Android's `BiometricPrompt` to verify your fingerprint, face, or screen lock before approving a tool call when you enable "Act for me". Biometric authentication is handled entirely by the Android operating system; the App only receives a pass/fail result.
- **`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_SPECIAL_USE`:** Used only when you explicitly turn on **Monitor in background** so the App can keep a live WebSocket connection to your host and post local notifications when a coding-agent session needs input or finishes. Turning off **Monitor in background** or disconnecting stops the service immediately.

### iOS / iPadOS
- **Face ID / Touch ID (or device passcode):** Used via Apple's `LocalAuthentication` framework to confirm it is you before approving a tool call or managing sessions. Your biometric data is handled entirely by iOS; the App only receives a pass/fail result.
- **Local Network:** Requested only when you connect to a host or AI endpoint on your local network.
- **Notifications:** If you allow them, the App posts **local** notifications when a session needs input or finishes. No push-notification service is used, so nothing about your sessions passes through Apple or the developer. iOS only lets the App keep its connection briefly after you leave it, so these alerts are best-effort.

## 5. Data Deletion

You can delete all stored credentials, API keys, cached session metadata, and preferences at any time by:
- Tapping **Sign out** in the App's Settings, which immediately destroys the Keystore / Keychain keys and deletes the encrypted token vault and session cache; or
- **Android:** clearing storage or uninstalling the App in System Settings.
- **iOS / iPadOS:** deleting the App from your device.

## 6. Children

The App is a developer tool, is not directed at children, and collects no personal information from anyone.

## 7. Trademarks & Non-Affiliation Notice

Hostline is an independent third-party client for the open [Agent Host Protocol (AHP)](https://github.com/microsoft/agent-host-protocol) and is not affiliated with, endorsed by, or sponsored by Microsoft Corporation or GitHub, Inc. Visual Studio Code and GitHub are trademarks of their respective owners. When you sign in with GitHub to connect to a VS Code Remote Tunnel, GitHub Device Flow requests authorization for Microsoft's "Visual Studio Tunnel Service" app so that your device can query and connect directly to your own Dev Tunnels.

## 8. Changes to This Policy

If this Privacy Policy is updated, the revised version and effective date will be posted on this page and summarized in the App's **About → Privacy** sheet.

## 9. Contact

For questions about this Privacy Policy or Hostline, please open an issue in the [ahp-client-legal](https://github.com/GreenJeans/ahp-client-legal) repository or contact the developer via the support email address listed on the Google Play or App Store page.
