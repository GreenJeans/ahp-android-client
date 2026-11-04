package dev.ahpclient.app

/**
 * The names the app shows for itself, in one place so a rename (U9 in `docs/ux-feedback-spec.md`) is one change here. The Android
 * launcher and tile labels are in `app/src/main/res/values/strings.xml`, and the iOS name is `PRODUCT_NAME` in
 * `iosApp/Configuration/Signing.xcconfig` and `CFBundleDisplayName` in `iosApp/AhpClient/Info.plist`: keep those in step.
 * The application and bundle ids stay as they are, so an update installs over the existing app.
 */
object AppName {
    /** The app's own name: the launcher, the sign-in screen, About. */
    const val NAME = "AHP Client"

    /** The Quick Settings tile. */
    const val MONITOR = "AHP Monitor"
}
