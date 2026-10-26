package dev.ahpclient.state

import dev.ahpclient.ahp.ConfigFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/** A chat's own settings: read from the snapshot's raw reply, then kept up to date from the actions the library cannot decode. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatConfigStoreTest {
    private fun TestScope.store() = ChatStore(CHAT_A, backgroundScope, { })

    private fun env(action: String, seq: Long) = chatEnvelope(CHAT_A, action, seq)

    private fun configChanged(config: String, replace: Boolean? = null) =
        """{"type":"chat/configChanged","config":$config${replace?.let { ""","replace":$it""" }.orEmpty()}}"""

    private fun facts(vararg values: Pair<String, String>) = ConfigFacts(values.associate { (k, v) -> k to JsonPrimitive(v) }, null)

    private fun ChatStore.own() = ownConfig.value.mapValues { (it.value as JsonPrimitive).content }

    @Test
    fun `a chat has no settings of its own until the host says so`() = runTest {
        val store = store()

        store.seed(chatSnapshot(CHAT_A, 10))

        assertEquals(emptyMap(), store.own())
    }

    @Test
    fun `the snapshot's own values are the starting point`() = runTest {
        val store = store()

        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan", "autoApprove" to "assisted"))

        assertEquals(mapOf("mode" to "plan", "autoApprove" to "assisted"), store.own())
    }

    @Test
    fun `a change from the host is merged in`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan"))

        store.apply(env(configChanged("""{"autoApprove":"autoApprove"}"""), 11))

        assertEquals(mapOf("mode" to "plan", "autoApprove" to "autoApprove"), store.own())
    }

    @Test
    fun `a change replaces the same setting`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan"))

        store.apply(env(configChanged("""{"mode":"autopilot"}"""), 11))

        assertEquals(mapOf("mode" to "autopilot"), store.own())
    }

    @Test
    fun `replace swaps the chat's values instead of merging`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan", "autoApprove" to "assisted"))

        store.apply(env(configChanged("""{"mode":"interactive"}""", replace = true), 11))

        assertEquals(mapOf("mode" to "interactive"), store.own())
    }

    @Test
    fun `replace as a string is not replace`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan"))

        store.apply(env("""{"type":"chat/configChanged","config":{"autoApprove":"assisted"},"replace":"true"}""", 11))

        assertEquals(mapOf("mode" to "plan", "autoApprove" to "assisted"), store.own())
    }

    @Test
    fun `a null value takes a setting away`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan", "autoApprove" to "assisted"))

        store.apply(env(configChanged("""{"mode":null}"""), 11))

        assertEquals(mapOf("autoApprove" to "assisted"), store.own())
    }

    @Test
    fun `settings this app does not read are never kept`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10))

        store.apply(env(configChanged("""{"permissions":{"allow":["rm"]},"sandboxEnabled":"off","mode":"plan"}"""), 11))

        assertEquals(mapOf("mode" to "plan"), store.own())
    }

    @Test
    fun `an action already seen is not applied twice, and an older one is dropped`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan"))
        store.apply(env(configChanged("""{"mode":"autopilot"}"""), 12))

        store.apply(env(configChanged("""{"mode":"interactive"}"""), 12))
        store.apply(env(configChanged("""{"mode":"interactive"}"""), 11))

        assertEquals(mapOf("mode" to "autopilot"), store.own())
    }

    @Test
    fun `a change that arrived before the snapshot is applied over it`() = runTest {
        val store = store()

        store.apply(env(configChanged("""{"mode":"autopilot"}"""), 11))
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan", "autoApprove" to "assisted"))

        assertEquals(mapOf("mode" to "autopilot", "autoApprove" to "assisted"), store.own())
    }

    @Test
    fun `a change older than the snapshot is not applied over it`() = runTest {
        val store = store()

        store.apply(env(configChanged("""{"mode":"autopilot"}"""), 9))
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan"))

        assertEquals(mapOf("mode" to "plan"), store.own())
    }

    @Test
    fun `a new snapshot replaces what was held, and one with none clears it`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan"))

        store.seed(chatSnapshot(CHAT_A, 20), ConfigFacts(emptyMap(), null))

        assertEquals(emptyMap(), store.own())
    }

    @Test
    fun `a snapshot with no facts at all leaves what is known`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan"))

        store.seed(chatSnapshot(CHAT_A, 20), null)

        assertEquals(mapOf("mode" to "plan"), store.own())
    }

    @Test
    fun `other actions do not touch the settings`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10), facts("mode" to "plan"))

        store.apply(env(TURN_STARTED, 11))
        store.apply(env("""{"type":"chat/somethingNew","config":{"mode":"autopilot"}}""", 12))

        assertEquals(mapOf("mode" to "plan"), store.own())
    }
}
