package dev.ahpclient.ahp

/** Thrown before any bytes leave the device when a call is outside the active tier. */
class CapabilityDeniedException(val method: String, val actionType: String?) :
    Exception("Capability denied: '$method'${actionType?.let { " (action '$it')" }.orEmpty()} is not allowed by this client's tier")

/**
 * The single gate every outbound AHP frame passes through. Phase 1 is read-only by construction: only [READ] exists,
 * and it allows exactly the calls that observe state. A later tier adds an explicit entry here; it is never inferred.
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

    companion object {
        const val DISPATCH_ACTION = "dispatchAction"

        /** Phases 1 and 2: connect, observe, page through history. `unsubscribe` is a notification in AHP 1.0 but is still a method name on the wire. */
        val READ = CapabilityPolicy(
            name = "READ",
            methods = setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns"),
            actionTypes = emptySet(),
        )
    }
}
