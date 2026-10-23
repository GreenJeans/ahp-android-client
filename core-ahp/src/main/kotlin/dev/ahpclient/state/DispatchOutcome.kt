package dev.ahpclient.state

/**
 * What became of one action this client sent. Only [Accepted] and [Rejected] are definite. [NoReply] and [Interrupted]
 * mean the host may or may not have applied it, so a caller must not blindly send it again (a resent message would
 * appear twice); it should show the chat and let the person decide.
 */
sealed interface DispatchOutcome {
    /** The host echoed the action back as ours: it was applied, and the chat state now includes it. */
    data object Accepted : DispatchOutcome

    /** The host refused it and changed nothing. [reason] is the host's own text, so it is untrusted display text. */
    data class Rejected(val reason: String) : DispatchOutcome

    /** Sent, but no echo arrived in time. */
    data object NoReply : DispatchOutcome

    /** The connection dropped after the action was sent and before the host answered. */
    data object Interrupted : DispatchOutcome
}
