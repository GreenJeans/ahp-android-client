package dev.ahpclient.state

/** How long a turn took, in the fewest words: "4s", "1m 4s", "2h 5m". Null when the host did not say. */
object TurnDuration {
    fun format(ms: Long?): String? {
        if (ms == null || ms < 0) return null
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}