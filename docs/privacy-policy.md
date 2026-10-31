# Privacy Policy — AHP Client (`dev.ahpclient`)

**Effective Date:** October 30, 2026

AHP Client ("the App") is an Android client for the [Agent Host Protocol (AHP)](https://github.com/microsoft/agent-host-protocol). It lets you monitor and interact with coding-agent sessions running on your own computers via Microsoft Dev Tunnels or a direct `ahpd` WebSocket connection.

## 1. Summary: Zero Developer Data Collection

- **No developer servers:** The developer of AHP Client does not operate any backend server, relay service, or cloud database for the App.
- **No analytics, telemetry, or ads:** The App contains no analytics SDKs, no crash-reporting SDKs, no tracking identifiers, and no advertising networks.
- **Your data stays between your phone and your hosts:** Prompts, code diffs, terminal output, file contents, and session transcripts travel directly between your Android device and the agent host(s) you configure. The developer never sees, receives, or stores any of your data.

## 2. Information Stored on Your Device

To function, the App stores the following data locally on your Android device only:

1. **Authentication Credentials:**
   - **GitHub OAuth Device-Flow Tokens** (when you sign in with GitHub to discover your Microsoft Dev Tunnels) and **Direct Host Bearer Tokens** (when you add an `ahpd` host by address).
   - These tokens are encrypted at rest using **AES-256-GCM** with non-exportable cryptographic keys held in your device's **Android Keystore (`AndroidKeyStore`)**.
2. **Cached Session List:**
   - To display your recent sessions immediately on cold start before a network connection settles, the App caches session titles, URIs, status values, and timestamps locally.
   - This cache is encrypted at rest using **AES-256-GCM** under a dedicated `AndroidKeyStore` key and is erased immediately when you sign out.
3. **App Preferences & Pro Entitlement State:**
   - Local settings (theme, font size, selected host ID, per-host interaction/management switches, background monitoring toggle, and local Pro unlock state) are stored in the App's private sandboxed DataStore/SharedPreferences.
4. **No Cloud Backup:**
   - Android Auto Backup and device-to-device transfer are disabled (`android:allowBackup="false"`), ensuring neither your encrypted ciphertexts nor preferences are uploaded to cloud backup storage.

## 3. Direct Network Connections

Depending on how you connect to your agent host, your device communicates directly with:

- **GitHub (`https://github.com/login/device/...`, `https://github.com/login/oauth/access_token`):** Used only when you initiate GitHub Device Flow sign-in to obtain and refresh tokens for Microsoft Dev Tunnels. Governed by the [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
- **Microsoft Dev Tunnels (`https://global.rel.tunnels.api.visualstudio.com`, `wss://*.devtunnels.ms`):** Used to list and connect to your own VS Code / AHP remote tunnels over TLS (`wss://`). Governed by the [Microsoft Privacy Statement](https://privacy.microsoft.com/privacystatement).
- **Direct `ahpd` Hosts (`ws://` or `wss://`):** Connects directly to the IP address or hostname you enter (such as over your local LAN or VPN/Tailscale tailnet).
- **Google Play Billing:** When you purchase or restore the optional one-time **AHP Client Pro** unlock (`ahp_pro_unlock`), the transaction is processed directly by Google Play. Governed by the [Google Privacy Policy](https://policies.google.com/privacy).

## 4. Device Permissions

- **`INTERNET` and `ACCESS_NETWORK_STATE`:** Required to connect to your agent hosts and detect network transitions for automatic reconnection.
- **`USE_BIOMETRIC`:** Used locally via Android's `BiometricPrompt` to verify your fingerprint, face, or screen lock before approving a tool call when you enable "Act for me". Biometric authentication is handled entirely by the Android operating system; the App only receives a pass/fail boolean.
- **`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_SPECIAL_USE`:** Used only when you explicitly turn on **Monitor in background** so the App can maintain a live WebSocket connection to your host and post local notifications when a coding-agent session needs input or finishes. Turning off **Monitor in background** or disconnecting stops the service immediately.

## 5. Data Deletion

You can delete all stored credentials, cached session metadata, and preferences at any time by:
- Tapping **Sign out** in the App's Settings (which immediately destroys the Keystore keys and deletes the encrypted token vault and session cache), or
- Clearing storage / uninstalling the App in Android System Settings.

## 6. Trademarks & Non-Affiliation Notice

AHP Client is an independent third-party client for the open [Agent Host Protocol (AHP)](https://github.com/microsoft/agent-host-protocol) and is not affiliated with, endorsed by, or sponsored by Microsoft Corporation or GitHub, Inc. Visual Studio Code and GitHub are trademarks of their respective owners. When you sign in with GitHub to connect to a VS Code Remote Tunnel, GitHub Device Flow requests authorization for Microsoft's "Visual Studio Tunnel Service" app so that your device can query and connect directly to your own Dev Tunnels.

## 7. Changes to This Policy

If this Privacy Policy is updated, the revised version and effective date will be posted on this page and in the App's **About -> Privacy** sheet.

## 8. Contact

For questions about this Privacy Policy or AHP Client, please open an issue in the [ahp-client-legal](https://github.com/GreenJeans/ahp-client-legal) repository or contact the developer via the support email address listed on the Google Play Store page.
