package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal const val CFG_CHAT = "ahp-chat:/cfg-main"
internal const val CFG_SUBAGENT = "ahp-chat:/cfg-sub"

/** The approvals property as the host publishes it (VS Code's `autoApprove`), with the host's own labels and descriptions. */
internal const val AUTO_APPROVE_PROPERTY =
    """{"type":"string","title":"Approvals","description":"Tool approval behavior for this session","enum":["default","assisted","autoApprove"],
        "enumLabels":["Manual permissions","Assisted permissions","Allow all"],
        "enumDescriptions":["Asks when approval settings don't apply","Evaluates risk before running tools","Runs tool calls without asking"],
        "default":"default","sessionMutable":true,"chatMutable":true}"""

/** The Copilot agent's own approvals property, used when the host has no `autoApprove`. */
internal const val APPROVAL_MODE_PROPERTY =
    """{"type":"string","title":"Approval mode","enum":["manual","assisted","allow-all"],"default":"manual","sessionMutable":true,"chatMutable":true}"""

internal const val MODE_PROPERTY =
    """{"type":"string","title":"Agent Mode","enum":["interactive","plan","autopilot"],"enumLabels":["Interactive","Plan","Autopilot"],
        "enumDescriptions":["Step-by-step collaboration","Plan first, execute when ready","Works autonomously within permissions"],
        "default":"interactive","sessionMutable":true,"chatMutable":true}"""

internal const val EFFECTIVE_APPROVAL_PROPERTY = """{"type":"string","title":"Effective approvals","enum":["default","assisted","autoApprove"],"readOnly":true,"chatMutable":true}"""

internal const val AVAILABLE_APPROVAL_PROPERTY = """{"type":"array","title":"Allowed approvals","items":{"type":"string","title":"Level"},"readOnly":true}"""

internal fun properties(vararg entries: Pair<String, String>): String = entries.joinToString(",") { (key, schema) -> "\"$key\":$schema" }

/** A session whose schema holds [properties] (see [properties]) and whose own values are [values], a JSON object body without braces. */
internal fun configSession(
    properties: String = properties("autoApprove" to AUTO_APPROVE_PROPERTY, "mode" to MODE_PROPERTY),
    values: String = """"autoApprove":"default","mode":"interactive"""",
    chats: String = """[{"resource":"$CFG_CHAT","title":"Main","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z"}]""",
): SessionState = Ahp.json.decodeFromString(
    SessionState.serializer(),
    """{"provider":"copilotcli","title":"Fix the build","status":1,"lifecycle":"ready","activeClients":[],"chats":$chats,
        "config":{"schema":{"type":"object","properties":{$properties}},"values":{$values}}}""",
)

internal val ALL_CHAT_MUTABLE = setOf("mode", "autoApprove", "approvalMode", "effectiveApprovalMode")

internal fun own(vararg entries: Pair<String, String>): Map<String, JsonElement> = entries.associate { (key, value) -> key to JsonPrimitive(value) }
