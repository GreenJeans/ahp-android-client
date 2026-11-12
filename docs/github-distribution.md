# GitHub distribution (Android)

Hostline for Android ships through two channels with the same package ID
(`com.greenjeans.ahpclient`) but different signing keys and different Pro
arrangements. They are separate builds of the same app, not two apps.

## The two flavors

`app/build.gradle.kts` defines a `store` flavor dimension:

| Flavor | Channel | Pro | Signing |
|---|---|---|---|
| `play` | Google Play closed testing, then production | One-time unlock `ahp_pro_unlock` via Play Billing, $4.99 at launch | Google Play app-signing key |
| `github` | GitHub Releases, installed directly or via Obtainium | Included free during the beta | Our own upload keystore |

The flavor is a `BuildConfig.GITHUB_BUILD` flag. In the GitHub flavor
`AppContainer` sets `ProEntitlement(alwaysUnlocked = true)`, the billing
gateway is a stub that always reports unlocked, the Play purchase
reconciliation on cold start is skipped, and `MainActivity` never attaches
the Play billing driver. Settings shows "Pro included" instead of a buy
button. Nothing else in the app differs between flavors.

## Why Pro is free on GitHub

Google Play Billing only works for apps installed from Play; a sideloaded
APK cannot sell or validate `ahp_pro_unlock`. The GitHub channel exists to
reach the power-user / FOSS crowd who overlap heavily with the early
Agent Host Protocol community — people on de-Googled phones, Obtainium
users, and the AHP repo regulars. Charging them is not possible through
Play, and the distribution, bug reports, and word of mouth are worth more
at this stage than the $4.99 those users would have paid (many of them
could not have paid through Play at all).

## Time-box

This is a beta arrangement, not a permanent price. The decision checkpoint
is the Play production launch (expected ~Nov 30, 2026) or v0.3.0, whichever
comes first. At that point, decide one of:

1. Keep Pro free on GitHub (goodwill channel, Play remains the money channel).
2. Switch the GitHub flavor to a license key sold outside Play (Stripe /
   Gumroad) — real work: a second entitlement path in the app.
3. Make the GitHub flavor free-tier only, with Pro on Play only.

Whatever is decided, say so in the release notes of the first build it
applies to. Do not silently take Pro away from people who installed a
build that promised it.

## Releasing a GitHub build

1. Add the four repository secrets (Settings → Secrets and variables →
   Actions) — the same keystore values already used for local Play builds:
   `AHP_KEYSTORE_BASE64` (base64 of the upload keystore file),
   `AHP_KEYSTORE_PASSWORD`, `AHP_KEY_ALIAS`, `AHP_KEY_PASSWORD`.
2. Bump `versionName` / `versionCode` in `app/build.gradle.kts` if needed.
3. Push a tag matching the version: `git tag v0.2.0 && git push origin v0.2.0`.
   The workflow fails if the tag does not match `versionName`, so a
   mistyped tag can never ship a mislabelled build.
4. `.github/workflows/release-apk.yml` builds `:app:assembleGithubRelease`,
   renames the APK to `hostline-<version>-github.apk`, writes a SHA-256
   file, and publishes the GitHub Release. Releases are deliberately NOT
   marked prerelease: Obtainium's default config ignores prereleases.
5. Local equivalent (needs the Android SDK and the keystore env vars):
   `./gradlew :app:assembleGithubRelease`.

## Installing (for users)

- **Obtainium** (recommended, auto-updates): install Obtainium, tap Add
  App, paste `https://github.com/GreenJeans/ahp-android-client`. One-tap
  link (opens in Obtainium on the phone):
  `obtainium://add/https://github.com/GreenJeans/ahp-android-client`
  — on the web, wrap it so it stays clickable:
  `https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/GreenJeans/ahp-android-client`
- **Direct APK:** download `hostline-<version>-github.apk` from
  [Releases](https://github.com/GreenJeans/ahp-android-client/releases)
  and open it; verify with the `.sha256` file beside it.

## Switching channels

The GitHub APK is signed with our upload keystore, not Google Play's
app-signing key, so Android refuses to update one over the other.
Switching between the GitHub build and the Play Store version means
uninstalling first, which wipes local data (saved hosts, tokens, session
lists). Stay on one channel for updates.

## Rules

- Never link the GitHub build from the Play Store listing or from inside
  the Play build of the app. Play policy forbids steering Play users to
  outside payment/download channels; the two channels are kept separate.
- Never mark a GitHub release as prerelease (see above).
- The GitHub flavor must never attempt a Play Billing call: it has no
  Play purchase to reconcile and no store to buy from.
