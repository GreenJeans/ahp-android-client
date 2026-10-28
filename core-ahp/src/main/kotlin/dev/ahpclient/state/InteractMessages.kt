package dev.ahpclient.state

/** What the person is told after trying to do something, in plain words. Null when there is nothing to say. */
object InteractMessages {
    private const val MAX_HOST_TEXT = 200

    fun describe(result: InteractResult): String? = when (result) {
        is InteractResult.Sent -> describe(result.outcome)
        InteractResult.NotAllowed -> "This app is not allowed to act in this chat right now."
        InteractResult.Blank -> null
        InteractResult.NoActiveTurn -> "Nothing is running to stop."
        InteractResult.NoSuchRequest -> "That is no longer waiting for an answer."
        InteractResult.NotConfirmed -> "Not approved: the device check was not completed."
        InteractResult.InvalidModel -> "The host does not offer that model here any more. Pick another and send again."
        InteractResult.InvalidAgent -> "The host does not offer that agent here any more. Pick another and send again."
        InteractResult.InvalidConfig -> "The host does not let this chat change that setting here."
        is InteractResult.NotSent -> "Could not send. Check the connection and try again."
        InteractResult.NotConnected -> "Open the app to answer."
    }

    fun describe(outcome: DispatchOutcome): String? = when (outcome) {
        DispatchOutcome.Accepted -> null
        // The host's own words are untrusted display text: bounded, and shown as plain text.
        is DispatchOutcome.Rejected -> "The host refused it: ${outcome.reason.take(MAX_HOST_TEXT)}"
        DispatchOutcome.NoReply -> "No answer from the host yet. Check the chat before sending it again."
        DispatchOutcome.Interrupted -> "The connection dropped before the host answered. Check the chat before sending it again."
    }

    /** True when the thing the person tried is done, so a composer can clear and a form can close. */
    fun succeeded(result: InteractResult): Boolean = result is InteractResult.Sent && result.outcome == DispatchOutcome.Accepted

    /** What a notification button says afterwards, where there is no screen to show it on: plainly done, or why not. */
    fun describeDecision(approved: Boolean, result: InteractResult): String =
        if (succeeded(result)) (if (approved) "Approved." else "Denied.") else describe(result) ?: "Nothing was sent."

    /** What a notification's text reply says afterwards: Sent when the host accepted it, or why it was not sent. */
    fun describeAnswer(result: InteractResult): String =
        if (succeeded(result)) "Sent" else describe(result) ?: "Nothing was sent."
}
