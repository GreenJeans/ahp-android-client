package dev.ahpclient.app

/**
 * The foreground-service type the monitor runs as on a given Android version. Android 15 cuts a data-sync service after
 * about six hours in 24, which a working day of monitoring reaches, so from Android 14 it runs as special use, which has
 * no such limit. Android 10 to 13 keep data sync, which has no limit there either. Before Android 10 a service has no type.
 */
enum class ForegroundKind {
    NONE,
    DATA_SYNC,
    SPECIAL_USE,
    ;

    companion object {
        private const val ANDROID_10 = 29
        private const val ANDROID_14 = 34

        fun forSdk(sdkInt: Int): ForegroundKind = when {
            sdkInt >= ANDROID_14 -> SPECIAL_USE
            sdkInt >= ANDROID_10 -> DATA_SYNC
            else -> NONE
        }
    }
}
