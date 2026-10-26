package dev.ahpclient.ui

import dev.ahpclient.state.FinishedWatch

/**
 * What the screens need for "alert me when it finishes": the choice (every session, or those picked with the bell), whether
 * background monitoring is on (the alerts only come while it is), and how to change the choice.
 */
data class FinishedAlertsUi(
    val watch: FinishedWatch = FinishedWatch(),
    val monitoring: Boolean = false,
    val onAll: (Boolean) -> Unit = {},
    val onSession: (resource: String, on: Boolean) -> Unit = { _, _ -> },
)
