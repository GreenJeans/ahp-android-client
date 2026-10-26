package dev.ahpclient.ahp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Thrown before any bytes leave the device when a call is outside the active tier. */
class CapabilityDeniedException(val method: String, val actionType: String?) :
    Exception("Capability denied: '$method'${actionType?.let { " (action '$it')" }.orEmpty()} is not allowed by this client's tier")

/**
 * The single gate every outbound AHP frame passes through. [READ] is the default and allows exactly the calls that
 * observe state. [INTERACT] adds `dispatchAction` for the few action types a person acts with, and [checkContent] holds the ones whose content must match too. A tier is chosen by the
 * caller when the connection is made, never inferred, and a method or action type that is not listed is refused before
 * any bytes leave the device.
 *
 * Never allowed in any tier without a new decision (see the spec's Design Notes): `authenticate`, `resource*` methods
 * other than `resourceRead`, `terminal/input`, `session/activeClientSet`, MCP start/stop and customization toggles.
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

    /**
     * Looks inside an action whose type is allowed, for the types whose content matters. A chat's setting is the one: its type
     * alone would let a bug or a hostile reply change any per-chat property (the terminal commands a tool may run unasked, the
     * sandbox), so it must name exactly one of the properties in [ChatConfigKeys.WRITABLE], set to one of that property's words,
     * and nothing else. The other allowed types carry only ids and text the host checks itself.
     *
     * @throws CapabilityDeniedException when [action] is not an exact match for what this app may send.
     */
    fun checkContent(actionType: String?, action: JsonObject) {
        if (actionType != CHAT_CONFIG_CHANGED) return
        val denied = CapabilityDeniedException(DISPATCH_ACTION, actionType)
        if (action.keys.any { it != "type" && it != "config" }) throw denied
        val config = action["config"] as? JsonObject ?: throw denied
        val (key, value) = config.entries.singleOrNull() ?: throw denied
        val allowed = ChatConfigKeys.WRITABLE[key] ?: throw denied
        val word = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw denied
        if (word !in allowed) throw denied
    }

    /** True when this tier can send any action at all. */
    val canDispatch: Boolean get() = DISPATCH_ACTION in methods

    /** True when this tier can create or remove sessions and chats. */
    val canManage: Boolean get() = MANAGE_COMMANDS.all { it in methods }

    companion object {
        const val DISPATCH_ACTION = "dispatchAction"

        /** Sets one of a chat's own settings (protocol 1.1.0). Allowed only with the content [checkContent] accepts. */
        const val CHAT_CONFIG_CHANGED = "chat/configChanged"

        /**
         * Connect, observe, page through history, and read the content a changed file points at. `resourceRead` is the only
         * `resource*` method here: it changes nothing on the host, and the app asks only for content addresses the open
         * changeset itself lists (see `ChangesetDiffs`). `unsubscribe` is a notification in AHP 1.0 but is still a method name on the wire.
         */
        private val READ_METHODS = setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns", "resourceRead")

        val READ = CapabilityPolicy(name = "READ", methods = READ_METHODS, actionTypes = emptySet())

        private val INTERACT_ACTIONS = setOf(
            "chat/turnStarted",
            "chat/pendingMessageSet",
            "chat/pendingMessageRemoved",
            "chat/inputAnswerChanged",
            "chat/inputCompleted",
            "chat/toolCallConfirmed",
            "chat/toolCallResultConfirmed",
            "chat/turnCancelled",
            CHAT_CONFIG_CHANGED,
        )

        /**
         * READ plus the actions that let a person take part in a chat: send a message, answer the agent's question, approve or
         * deny a tool, and stop a turn. Nothing that changes a session itself, runs a command directly or touches files.
         */
        val INTERACT = CapabilityPolicy(
            name = "INTERACT",
            methods = READ_METHODS + DISPATCH_ACTION,
            actionTypes = INTERACT_ACTIONS,
        )

        /** The commands that create or remove a session or chat. Nothing else in [MANAGE] changes what exists. */
        val MANAGE_COMMANDS = setOf("createSession", "createChat", "disposeSession", "disposeChat")

        /**
         * A question the host answers about a session that does not exist yet: which settings would it offer for this agent in
         * this folder. It changes nothing, and it is only asked while starting a session, so it sits with [MANAGE].
         */
        val MANAGE_QUERIES = setOf("resolveSessionConfig")

        /**
         * What marking a session read or archived, or a changed file reviewed, dispatches. Each is reversible with the same
         * action, and none of them changes anything the agent runs or any file.
         */
        val MANAGE_ACTIONS = setOf("session/isReadChanged", "session/isArchivedChanged", "changeset/filesReviewChanged")

        /**
         * INTERACT plus looking after sessions: mark one read or archived, mark a changed file reviewed, start a chat or a session, and remove one. A
         * separate permission from INTERACT, because creating a session starts an agent on the user's machine and removing
         * one cannot be undone. Still nothing that runs a command directly, touches files or signs in to anything.
         */
        val MANAGE = CapabilityPolicy(
            name = "MANAGE",
            methods = READ_METHODS + DISPATCH_ACTION + MANAGE_COMMANDS + MANAGE_QUERIES,
            actionTypes = INTERACT_ACTIONS + MANAGE_ACTIONS,
        )
    }
}
