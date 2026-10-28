package dev.ahpclient.app

import dev.ahpclient.state.ApprovalTarget
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.QuestionTarget
import dev.ahpclient.state.RequestRead
import dev.ahpclient.state.WaitingText
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Reading what a session that needs the person is waiting for, for the alert that tells them and for the inbox. */
object WaitingRequests {
    /**
     * What a session is waiting for, in words, and the tool call buttons may be offered for. The session list does not carry it, so this
     * opens the session read-only, takes what is pending from its snapshot and closes it again: one `subscribe` and one `unsubscribe`,
     * both in the read tier. Null when the session is gone or has nothing pending. The caller bounds how long it may take.
     */
    suspend fun read(session: HostSession, resource: String): RequestRead? {
        val store = session.openSession(resource)
        try {
            val state = combine(store.state, store.error) { loaded, error -> loaded to error }
                .first { (loaded, error) -> loaded != null || error != null }
                .first ?: return null
            val text = WaitingText.of(state.inputNeeded.orEmpty())
            return RequestRead(text, ApprovalTarget.from(state), QuestionTarget.from(state))
        } finally {
            session.closeSession(resource)
        }
    }
}