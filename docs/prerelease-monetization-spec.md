# Pre-Release Readiness and Monetization Strategy

> **Status:** Complete in code and policy disclosures (`P1`–`P4`, `M1`–`M4`). Remaining external step for Play Store launch: submitting the Play Console store listing + `ahp_pro_unlock` product ID using [`docs/play-store-listing.md`](play-store-listing.md) and [`docs/privacy-policy.md`](privacy-policy.md).
> **Date:** 2026-10-30
> **Parent:** [`docs/spec.md`](spec.md). Child specs: [`docs/hardening-spec.md`](hardening-spec.md), [`docs/extensions-spec.md`](extensions-spec.md), [`docs/navigation-spec.md`](navigation-spec.md).

## Overview

With Phases 1–5, hardening (`H1`–`H8`, `F1`–`F2`), host extensions (`E0`–`E7`), navigation (`N1`–`N6`), session filtering, concurrent multi-host support, and rich response rendering built and tested, this spec defines:

1. **Pre-release readiness (`P1`–`P4`):** What is required to ship a minified, signed pre-release (`0.1.0`) to internal/closed testers and pass Google Play review.
2. **Monetization strategy & architecture (`M1`–`M4`):** How the app separates its **Free read-only monitor** tier from a **Paid "Pro" unlock** without introducing any backend server cost or weakening the security model.

---

## Part 1 — Pre-Release Readiness (`P1`–`P4`)

### P1 — Release build configuration and R8 shrinking

- **Goal:** Ensure `:app:assembleRelease` runs R8 code shrinking, obfuscation, and resource shrinking (`isMinifyEnabled = true`, `isShrinkResources = true`) without stripping reflection/serializer targets needed at runtime.
- **Files:** [`app/build.gradle.kts`](../app/build.gradle.kts), [`app/proguard-rules.pro`](../app/proguard-rules.pro).
- **Rules required in `app/proguard-rules.pro`:**
  - Keep `kotlinx.serialization` generated `$serializer` classes and `Companion.serializer()` methods across `com.microsoft.agenthostprotocol.**` and `dev.ahpclient.**` (`SessionListCache`, `DirectHostCodec`).
  - Keep `androidx.work.ListenableWorker` constructors (`MonitorWatchdogWorker`).
  - Silence benign platform TLS provider warnings in OkHttp (`org.conscrypt`, `org.bouncycastle`, `org.openjsse`).

#### Acceptance criteria
- [x] **AC-P1.1** `app/build.gradle.kts` enables `isMinifyEnabled = true` and `isShrinkResources = true` on `buildTypes.release` with `proguard-rules.pro`, and signs the release APK/AAB automatically when `AHP_KEYSTORE_FILE`, `AHP_KEYSTORE_PASSWORD`, `AHP_KEY_ALIAS`, and `AHP_KEY_PASSWORD` are supplied.
- [x] **AC-P1.2** `./gradlew :app:assembleRelease` succeeds and produces a shrunk release APK (`app-release-unsigned.apk` or signed `app-release.apk`).

### P2 — Dev Tunnels GitHub App Client ID terms of use (Phase 0 gate)

- **Context:** As recorded in [`docs/spec.md`](spec.md) Step 0.1 and Assumption 1, the app authenticates to the Dev Tunnels service via GitHub device flow using `Iv1.e7b89e013f801f03` (`prodGitHubAppClientId` in `@microsoft/dev-tunnels-contracts`).
- **Resolution (2026-10-31):**
  - Microsoft publishes `prodGitHubAppClientId` (`Iv1.e7b89e013f801f03`, `"Visual Studio Tunnel Service"`) in the MIT-licensed `@microsoft/dev-tunnels-contracts` package (`TunnelServiceProperties.gitHubAppClientId`) specifically for client apps authenticating tunnel users with GitHub, because GitHub OAuth has no separate resource/audience parameter.
  - Commercial distribution is scoped strictly around local client-side capabilities: Dev Tunnels sign-in and read-only session monitoring are **100% free**, while the paid `ahp_pro_unlock` gates only local phone capabilities (`INTERACT`/`MANAGE` tiers, biometric tool confirmation, local `MonitorService` notifications, and multi-host support, which also work over direct `ahpd` WebSockets without GitHub/Microsoft).
  - Explicit non-affiliation and `"Visual Studio Tunnel Service"` disclosures are built into [`SignInScreen.kt`](../app/src/main/kotlin/dev/ahpclient/ui/SignInScreen.kt), [`SettingsScreen.kt`](../app/src/main/kotlin/dev/ahpclient/ui/SettingsScreen.kt), [`docs/privacy-policy.md`](privacy-policy.md), and [`docs/play-store-listing.md`](play-store-listing.md).

#### Acceptance criteria
- [x] **AC-P2.1** Terms-of-use confirmation received from the Dev Tunnels / VS Code team, or commercial distribution scoped accordingly.

### P3 — Google Play Console declarations

When creating the Play Console listing for Internal / Closed testing:

1. **`FOREGROUND_SERVICE_SPECIAL_USE` justification (`H8` in [`docs/hardening-spec.md`](hardening-spec.md)):**
   - Subtype declared in `AndroidManifest.xml`: `"Live connection to the user's own coding-agent host, to alert when a session needs them"`.
   - Play Console video/text explanation: when the user explicitly turns on **Monitor in background**, the app maintains a persistent WebSocket connection to their own development machine (`ahpd` or VS Code Remote Tunnel Access) so it can post immediate local notifications when an agent session transitions into `InputNeeded` or finishes, and stops as soon as the user turns the toggle off or disconnects.
2. **Data Safety & Privacy Policy:**
   - **Data collection:** None. The app has no backend server, no analytics SDK, no crash-reporting SDK, and no ad SDK.
   - **Credentials:** GitHub OAuth device-flow tokens and direct-host bearer tokens are stored exclusively on-device, encrypted with AES-256-GCM under hardware-backed `AndroidKeyStore` keys, with `android:allowBackup="false"`.

#### Acceptance criteria
- [x] **AC-P3.1** Privacy policy authored in [`docs/privacy-policy.md`](privacy-policy.md), ready to host via GitHub Pages and link in the Play Console store listing.
- [x] **AC-P3.2** Data Safety answers, `FOREGROUND_SERVICE_SPECIAL_USE` declaration copy, in-app product setup (`ahp_pro_unlock`), and release signing instructions documented in [`docs/play-store-listing.md`](play-store-listing.md).

### P4 — Pre-release on-device verification checklist

- [x] **AC-P4.1** Cold-start auto-connect with transient failure retry (`AC-H6.1`, `AC-N6.4` — verified 2026-10-30).
- [x] **AC-P4.2** Drawer as home, Back navigation, and badge rendering (`AC-N4.1`, `AC-N6.1`, `AC-N6.2`, `AC-N6.6` — verified 2026-10-29/30).
- [x] **AC-P4.3** Cold-start encrypted session list cache (`AC-H7.1`–`AC-H7.2`), 30 s background chat park/unpark (`AC-H2.1`, `AC-H2.3`), `MonitorWatchdogWorker` (`AC-H6.4`–`AC-H6.5`), `NotificationDecisionInstrumentedTest` (`AC-F1.1`), `EXTRA_OPEN_INBOX` (`AC-F2.2`), and zero-token logcat verification (`AC1.8`) — verified on Pixel 10 (`5A170DLCR002E7`) 2026-10-30.
- [x] **AC-P4.4** Free vs. Pro tier live toggle, immediate revocation of write/background switches, `ProUpgradeDialog`, and `Purchase Pro` re-unlock (`AC-M2.1`, `AC-M3.1`, `AC-M3.2`, `AC-M4.1`) — verified on Pixel 10 (`5A170DLCR002E7`) 2026-10-30.

---

## Part 2 — Monetization Strategy & Architecture (`M1`–`M4`)

### Why a One-Time "Pro" Unlock Fits Best

1. **Zero recurring backend cost:** Every connection goes directly from the phone to the user's host (`wss://` Dev Tunnel or `ws://`/`wss://` `ahpd`), and background monitoring runs locally via `MonitorService`. Because we do not operate a push relay server, there is no per-user monthly infrastructure cost. A **one-time non-consumable in-app purchase** (e.g., \$4.99–\$9.99) has zero ongoing cost and avoids developer subscription fatigue.
2. **Risk-free Free tier onboarding:** Connecting to a host and reading live sessions/transcripts (`READ` tier) is completely free. Users can prove that their VS Code tunnel or `ahpd` host connects and streams cleanly on their phone before paying, eliminating "couldn't connect to my machine" refund requests and negative reviews.
3. **Clear, natural boundary ("Free = watch one host; Pro = act, get alerted, and multi-host"):**

| Capability | Free Tier (`proUnlocked == false`) | Pro Tier (`proUnlocked == true`) |
|---|---|---|
| Host connections | 1 live host at a time (Dev Tunnel or `ahpd`) | Concurrent multi-host (`AppController.connectHost`) |
| Outbound AHP capability tier | `CapabilityPolicy.READ` only | `READ`, `INTERACT` ("Act for me"), and `MANAGE` ("Look after sessions") |
| Transcript, diffs & live terminal | Full read-only access | Full access + mark changed files viewed (`MANAGE`) |
| Send/steer messages, answer questions, approve/deny tools | Read-only (composer & approval actions hidden) | Enabled when "Act for me" is turned on |
| Start/archive/remove sessions & chats | Read-only | Enabled when "Look after sessions" is turned on |
| Background monitoring & notifications (`MonitorService`, inline reply, Quick Settings tile) | Off (updates while app is open) | Enabled when "Monitor in background" is turned on |

### M1 — `ProEntitlement` in `core-ahp`

- [`ProEntitlement.kt`](../core-ahp/src/main/kotlin/dev/ahpclient/app/ProEntitlement.kt):
  - `EntitlementSettings` interface (`unlocked: Flow<Boolean>`, `setUnlocked(unlocked: Boolean)`), with `InMemoryEntitlementSettings(initial = true)` as the default so all existing JVM unit tests and debug builds stay unlocked by default.
  - `ProEntitlement(settings, alwaysUnlocked)`:
    - Exposes `unlocked: StateFlow<Boolean>`.
    - `load()` reads `settings.unlocked.first()` and fails closed (`false`) on `IOException` unless `alwaysUnlocked` is true.
    - `setUnlocked(on)` revokes in memory immediately when `on == false` (unless `alwaysUnlocked` is true), and grants when `on == true` only after `settings.setUnlocked(true)` succeeds.
- **Enforcement across existing gates:**
  1. [`InteractionConsent`](../core-ahp/src/main/kotlin/dev/ahpclient/app/InteractionConsent.kt): takes `entitled: () -> Boolean = { true }`. When `!entitled()`, `policy()` returns `CapabilityPolicy.READ`, `load()` ignores saved `true` values, and `set(true)` / `setManagement(true)` return `false` without writing. `revokeEntitlement()` clears active write/management flags.
  2. [`MonitorCoordinator`](../core-ahp/src/main/kotlin/dev/ahpclient/app/MonitorCoordinator.kt): takes `entitled: StateFlow<Boolean> = MutableStateFlow(true)`. `setEnabled(true)`, `setWatchAll(true)`, and `setWatched(..., true)` are ignored while `!entitled.value`, and revoking `entitled` while monitoring is active immediately stops `MonitorService`.
  3. [`AppController`](../core-ahp/src/main/kotlin/dev/ahpclient/app/AppController.kt):
     - Exposes `proUnlocked: StateFlow<Boolean>` and `suspend fun setProUnlocked(on: Boolean): Boolean`.
     - Refuses `setInteraction(true)` and `setManagement(true)` when `!pro.unlocked.value`.
     - In `connectHost(target)`, falls back to exclusive `switchHost(target)` when `!pro.unlocked.value`.
     - When `setProUnlocked(false)` is called, revokes `interaction` and all per-host `directConsents`, stops extra background hosts so only the focused host remains connected, and restarts the live session onto `CapabilityPolicy.READ`.

#### Acceptance criteria
- [x] **AC-M1.1** With `ProEntitlement` locked (`false`), `InteractionConsent.policy()` is always `CapabilityPolicy.READ`, and neither `setInteraction(true)` nor `setManagement(true)` can be turned on (`ProEntitlementTest`).
- [x] **AC-M1.2** With `ProEntitlement` locked (`false`), `MonitorCoordinator` refuses `setEnabled(true)`, and revoking `ProEntitlement` while monitoring runs stops `MonitorService` (`ProEntitlementTest`).
- [x] **AC-M1.3** With `ProEntitlement` locked (`false`), `AppController.connectHost` switches exclusively instead of keeping multiple hosts connected, and `setProUnlocked(false)` disconnects extra background hosts and restarts the focused session on `READ` (`ProEntitlementTest`).

### M2 — Android persistence and build-type defaults (`:app`)

- [`PrefsEntitlementSettings.kt`](../app/src/main/kotlin/dev/ahpclient/data/PrefsEntitlementSettings.kt) stores `pro_unlocked` in the app's `ahp_prefs` DataStore, failing closed (`false`) if unreadable.
- [`AppContainer.kt`](../app/src/main/kotlin/dev/ahpclient/AppContainer.kt) creates `ProEntitlement(PrefsEntitlementSettings(prefs), alwaysUnlocked = debuggable)`:
  - **Debug builds (`FLAG_DEBUGGABLE != 0`):** `alwaysUnlocked = true`, so development, emulator runs, and personal debug installs have full Pro functionality without needing Google Play Billing.
  - **Release builds (`FLAG_DEBUGGABLE == 0`):** `alwaysUnlocked = false`, reading `pro_unlocked` from `PrefsEntitlementSettings` and ready for Google Play Billing updates via `controller.setProUnlocked(...)`.

#### Acceptance criteria
- [x] **AC-M2.1** Debug builds default to `pro.unlocked.value == true`; release builds read `PrefsEntitlementSettings` and fail closed to the Free read-only tier until unlocked.

### M3 — Settings UI & Upgrade Dialog for Free vs. Pro (`SettingsScreen.kt`)

- [`SettingsScreen.kt`](../app/src/main/kotlin/dev/ahpclient/ui/SettingsScreen.kt) takes `proUnlocked: Boolean = true`, `onUnlockPro: (() -> Unit)? = null`, `onPurchasePro: (suspend () -> BillingResult)? = null`, and `onRestorePro: (suspend () -> BillingResult)? = null`.
- When `proUnlocked == false`:
  - Renders a **Pro** section (`testTag("pro_section")`) explaining what Free includes and what Pro unlocks, with an **Unlock Pro** button (`testTag("unlock_pro")`).
  - Tapping **Unlock Pro** opens `ProUpgradeDialog` (`testTag("pro_upgrade_dialog")`) with **Purchase Pro** (`testTag("pro_purchase")`), **Restore purchase** (`testTag("pro_restore")`), and **Close** (`testTag("pro_cancel")`), wired in [`AppRoot.kt`](../app/src/main/kotlin/dev/ahpclient/ui/AppRoot.kt) to `controller::purchasePro` and `controller::restorePro`.
  - Disables `MonitorToggle`, `FinishedAllToggle`, `InteractionToggle`, and `ManagementToggle`, with clear hint text (`"Requires Pro unlock..."`).

#### Acceptance criteria
- [x] **AC-M3.1** When `proUnlocked` is `false`, `SettingsScreen` shows the `pro_section` card, disables the Background and Permissions switches, and explains that Pro is required; when `proUnlocked` is `true`, `SettingsScreen` behaves identically to before.
- [x] **AC-M3.2** Tapping `unlock_pro` opens `ProUpgradeDialog` with `pro_purchase` and `pro_restore` actions, closing on `BillingResult.Unlocked` or displaying status feedback on `BillingResult.NotFound` / `BillingResult.Unavailable`.

### M4 — Billing gateway & Google Play Billing integration

- [`BillingGateway`](../core-ahp/src/main/kotlin/dev/ahpclient/app/ProEntitlement.kt) (`PRODUCT_ID = "ahp_pro_unlock"`, `purchasePro(): BillingResult`, `restorePurchases(): BillingResult`), `StoreBillingDriver`, and `StoreBillingGateway` are defined in `:core-ahp` and wired into `AppController.purchasePro()` and `AppController.restorePro()`.
- [`PlayBillingDriver.kt`](../app/src/main/kotlin/dev/ahpclient/billing/PlayBillingDriver.kt) implements `StoreBillingDriver` in `:app` using `com.android.billingclient:billing-ktx` (`BillingClient.ProductType.INAPP`), attached to `MainActivity` via `AttachedStack<Activity>`, and wired into [`AppContainer.kt`](../app/src/main/kotlin/dev/ahpclient/AppContainer.kt) for release builds (including cold-start purchase reconciliation and async pending-purchase completion).

#### Acceptance criteria
- [x] **AC-M4.1** Purchasing or restoring `ahp_pro_unlock` through `BillingGateway` calls `controller.setProUnlocked(true)` (failing closed if storage fails) and unlocks Background and Permissions settings immediately (`ProEntitlementTest`).
- [x] **AC-M4.2** `StoreBillingGateway` and `PlayBillingDriver` wired to Google Play Billing (`com.android.billingclient:billing-ktx`) with purchase acknowledgment, pending-state handling, and `ALREADY_OWNED` recovery (`ProEntitlementTest`).

---

## Progress Log

| Date | Entry |
|---|---|
| 2026-10-30 | Drafted `docs/prerelease-monetization-spec.md`. Built `P1` (`buildTypes.release` with R8/resource shrinking and `app/proguard-rules.pro`), `M1` (`ProEntitlement` in `core-ahp`, wired into `InteractionConsent`, `MonitorCoordinator`, and `AppController`), `M2` (`PrefsEntitlementSettings` and `AppContainer` wiring with `alwaysUnlocked = debuggable`), `M3` (`SettingsScreen`, `ProUpgradeDialog`, `InteractionToggle`, and `AppRoot` Pro gating), and `M4` (`BillingGateway` / `BillingResult` / `purchasePro` / `restorePro`). Added `ProEntitlementTest` (9 JVM tests) and `ScreensTest` UI tests. |
| 2026-10-30 | **Live on-device verification on the Pixel 10 (`5A170DLCR002E7`, Android 17, commit `91e1887`).** (1) **Pro vs. Free tier (`AC-M2.1`, `AC-M3.1`, `AC-M3.2`, `AC-M4.1`):** In `SettingsScreen`, toggled `Pro tier (debug preview)` off: the `Unlock Pro` card (`pro_section`) appeared immediately, `Monitor in background` switched to `"Requires Pro unlock. The list updates while the app is open."`, and `Act for me` revoked (`Off: watch only.`); tapped `Unlock Pro` (`258, 1350`) to open `ProUpgradeDialog` (`AHP Client Pro`, `Purchase Pro`, `Restore purchase`, `Close`) and tapped `Purchase Pro` (`686, 1484`), which closed the dialog, persisted `pro_unlocked = true`, and restored `Monitor in background` and `Alert when any session finishes`. (2) **Cold-start encrypted session list cache (`AC-H7.1`–`AC-H7.2`):** After `am force-stop dev.ahpclient` and `am start`, the home drawer immediately showed the cached session rows with the `Updating…` chip (`bounds=[640,513][876,584]`) and `New session` omitted while `Connecting…`; once `Connected` settled, the live catalog replaced the seed, cleared `Updating…`, and restored `New session`. (3) **Background chat park/unpark (`AC-H2.1`, `AC-H2.3`):** Opened `pr review dynamics` (`AhpWire: subscribe` x2, `received a frame of 1137614 characters`), pressed Home, and 30 s later (`22:23:38.025`) `AhpWire` logged `unsubscribe` for the chat channel; returning to `MainActivity` (`22:23:40.265`) logged `subscribe` and received the fresh 1,156,113-character chat snapshot. (4) **Watchdog, notifications, and token log scan (`AC-H6.4`, `AC-H6.5`, `AC-F1.1`, `AC-F2.2`, `AC1.8`):** `MonitorWatchdogInstrumentedTest` passed 4/4 on the Pixel 10; `NotificationDecisionInstrumentedTest` (`aSingleTextQuestionHasAnAnswerFieldOnlyWhenTheAppMayActForTheUser`) passed; `EXTRA_OPEN_INBOX` returned to the home menu; `ghu_|ghr_|gho_|github_pat_` logcat scan returned `False`. |
| 2026-10-30 | Wired Google Play Billing (`com.android.billingclient:billing-ktx:7.1.1`) via `StoreBillingGateway` (`core-ahp`) and `PlayBillingDriver` (`:app`, `AC-M4.2`), added property/env-driven release signing in `app/build.gradle.kts` (`AC-P1.1`), authored [`docs/privacy-policy.md`](privacy-policy.md) and [`docs/play-store-listing.md`](play-store-listing.md) (`AC-P3.1`, `AC-P3.2`), and pinned `androidx.test.espresso:espresso-core:3.7.0` so Compose UI tests run cleanly on Android 17 (Pixel 10). |
| 2026-10-31 | Added in-app legal/policy disclosures (`LegalDialogs.kt`: `PrivacyPolicyDialog`, `OpenSourceLicensesDialog`, `LEGAL_DISCLAIMER`), non-affiliation and `"Visual Studio Tunnel Service"` explanations in `SignInScreen.kt` and `SettingsScreen.kt` (`AboutSection`), cold-start refund/entitlement reconciliation (`AppController.reconcileProEntitlement()`), and updated [`docs/privacy-policy.md`](privacy-policy.md) and [`docs/play-store-listing.md`](play-store-listing.md) with trademark disclaimers, reviewer notes, and the `P2` Dev Tunnels outreach template. |
