package dev.ahpclient.state

import dev.ahpclient.ahp.ChatConfigKeys
import dev.ahpclient.ahp.CapabilityPolicy
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.StateAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one action that changes a chat's own setting: `chat/configChanged` with a single property from [ChatConfigKeys.WRITABLE] set to
 * one of its words. The 1.0.0 library has no type for it, so it is assembled as structured JSON and decoded through the library's own
 * serializer (which keeps an unrecognised type as it is), like [SessionActions]. A key or a word outside the list is refused here, and
 * again by [CapabilityPolicy.checkContent] before anything is sent. Nothing here talks to the host.
 */
object ChatConfigActions {
    /** @throws IllegalArgumentException when [key] is not a setting this app may write, or [value] is not one of its words. */
    fun set(key: String, value: String): StateAction {
        require(ChatConfigKeys.WRITABLE[key]?.contains(value) == true) { "Not a setting this app may write" }
        return Ahp.json.decodeFromJsonElement(
            StateAction.serializer(),
            buildJsonObject {
                put("type", CapabilityPolicy.CHAT_CONFIG_CHANGED)
                put("config", buildJsonObject { put(key, value) })
            },
        )
    }
}

/** What the device check says when a setting is loosened. This app's own words, never the host's, so they cannot be changed by what the host calls things. */
object ChatConfigPrompt {
    const val TITLE = "Give this chat more freedom?"

    /** The line under the title: what the person is about to allow. */
    fun name(control: ConfigControl, value: String): String = when {
        control.kind == ConfigKind.MODE && value == "autopilot" -> "Autopilot: the agent works on its own"
        control.kind == ConfigKind.APPROVALS && ChatConfig.rank(control.kind, control.key, value) == 2 -> "Allow all: tool calls run without asking"
        control.kind == ConfigKind.APPROVALS -> "Assisted: the host decides which tool calls need you"
        else -> "${control.title}: ${ChatConfig.labelOf(control.kind, control.key, value)}"
    }

    const val SUMMARY = "Applies to this chat until you change it back."
}