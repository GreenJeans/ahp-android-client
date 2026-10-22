package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.SessionStatus

/** The single activity a session row shows. Needs-input wins over in-progress because INPUT_NEEDED (24) includes IN_PROGRESS (8). */
enum class ActivityState { NEEDS_INPUT, IN_PROGRESS, ERROR, IDLE }

val SessionStatus.activityState: ActivityState
    get() = when {
        SessionStatus.INPUT_NEEDED in this -> ActivityState.NEEDS_INPUT
        SessionStatus.IN_PROGRESS in this -> ActivityState.IN_PROGRESS
        SessionStatus.ERROR in this -> ActivityState.ERROR
        else -> ActivityState.IDLE
    }

val SessionStatus.isRead: Boolean get() = SessionStatus.IS_READ in this

val SessionStatus.isArchived: Boolean get() = SessionStatus.IS_ARCHIVED in this
