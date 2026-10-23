package dev.ahpclient.ahp

/** Thrown before any bytes leave the device when a call is outside the active tier. */
class CapabilityDeniedException(val method: String, val actionType: String?) :
    Exception("Capability denied: '$method'${actionType?.let { " (action '$it')" }.orEmpty()} is not allowed by this client's tier")

/**
 * The single gate every outbound AHP frame passes through. [READ] is the default and allows exactly the calls that
 * observe state. [INTERACT] adds `dispatchAction` for the few action types a person acts with. A tier is chosen by the
 * caller when the connection is made, never inferred, and a method or action type that is not listed is refused before
 * any bytes leave the device.
 *
 * Never allowed in any tier without a new decision (see the spec's Design Notes): `authenticate`, `resource*`
 * writes, `terminal/input`, `session/activeClientSet`, MCP start/stop and customization toggles.
 */
class CapabilityPolicy private constructor(
    val name: String,
    private val methods: Set<String>,
    private val actionTypes: Set<String>,
) {
    /** @throws CapabilityDeniedException when [method] (or, for `dispatchAction`, [actionType]) is not allowed. */
    fun check(method: String, actionType: String? = null) {
        if (method !in methods) throw CapabilityDeniedException(method, actionType)
        if (method == DISPATCH_ACTION && (actionType == null || actionType !in actionTypes)) {
            throw CapabilityDeniedException(method, actionType)
        }
    }

    /** True when this tier can send any action at all. */
    val canDispatch: Boolean get() = DISPATCH_ACTION in methods

    companion object {
        const val DISPATCH_ACTION = "dispatchAction"

        /** Connect, observe, page through history. `unsubscribe` is a notification in AHP 1.0 but is still a method name on the wire. */
        private val READ_METHODS = setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns")

        val READ = CapabilityPolicy(name = "READ", methods = READ_METHODS, actionTypes = emptySet())

        /**
         * READ plus the actions that let a person take part in a chat: send a message, answer the agent's question, approve or
         * deny a tool, and stop a turn. Nothing that changes a session itself, runs a command directly or touches files.
         */
        val INTERACT = CapabilityPolicy(
            name = "INTERACT",
            methods = READ_METHODS + DISPATCH_ACTION,
            actionTypes = setOf(
                "chat/turnStarted",
                "chat/pendingMessageSet",
                "chat/pendingMessageRemoved",
                "chat/inputAnswerChanged",
                "chat/inputCompleted",
                "chat/toolCallConfirmed",
                "chat/toolCallResultConfirmed",
                "chat/turnCancelled",
            ),
        )
    }
}
