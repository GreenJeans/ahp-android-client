package dev.ahpclient.state

import dev.ahpclient.ahp.error
import dev.ahpclient.ahp.result
import dev.ahpclient.tunnel.DevTunnelsException
import com.microsoft.agenthostprotocol.generated.Implementation
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")

    private val firstSessionOnly: String =
        Json.parseToJsonElement(fixture("live/list-sessions.json")).jsonObject["items"]!!.jsonArray.first().let { """{"items":[$it]}""" }

    private class Rig(val connector: ScriptedConnector, val host: HostSession) {
        val socket get() = connector.sockets.last()
    }

    private fun TestScope.rig(vararg scripts: HostScript, clientId: String = "client-1"): Rig {
        val connector = ScriptedConnector(*scripts)
        return Rig(connector, HostSession(connector, backgroundScope, info, Backoff(), clientId))
    }

    private suspend fun HostSession.awaitState(match: (ConnState) -> Boolean): ConnState = withTimeout(300_000) { connection.first(match) }

    private suspend fun Rig.start(): Rig = also {
        host.start()
        host.awaitState { it == ConnState.Online }
    }

    private fun Rig.titles() = host.root.sessions.value.map { it.title }

    private fun notificationFrame(method: String, params: String) = notification(method, params)

    @Test
    fun `connects, seeds the root state and builds the sorted session list`() = runTest {
        val rig = rig(HostScript()).start()

        assertEquals(ConnState.Online, rig.host.connection.value)
        assertEquals(1, rig.host.root.root.value!!.agents.size)
        assertEquals(
            listOf("Session E (synthetic: input needed)", "Session F (synthetic: error)", "Session D", "Session C", "Session B", "Session A"),
            rig.titles(),
        )
        assertEquals(listOf("initialize", "listSessions"), rig.socket.sentMethods)
    }

    @Test
    fun `a summary change merges into the row and re-sorts it`() = runTest {
        val rig = rig(HostScript()).start()

        rig.socket.serverSends(notificationFrame("root/sessionSummaryChanged", fixture("live/session-summary-changed.json")))
        runCurrent()

        assertEquals(
            listOf("Session E (synthetic: input needed)", "Session C", "Session F (synthetic: error)", "Session D", "Session B", "Session A"),
            rig.titles(),
        )
        val changed = rig.host.root.sessions.value.first { it.resource == SESSION_C }
        assertEquals("Waiting for approval", changed.activity)
        assertEquals("Session C", changed.title)
    }

    @Test
    fun `a metadata only change, as seen live, leaves the row alone`() = runTest {
        val rig = rig(HostScript()).start()
        val before = rig.host.root.sessions.value

        rig.socket.serverSends(notificationFrame("root/sessionSummaryChanged", """{"channel":"ahp-root://","session":"$SESSION_A","changes":{"_meta":{"git":{"branchName":"x"}}}}"""))
        runCurrent()

        val after = rig.host.root.sessions.value
        assertEquals(before.map { it.copy(meta = null) }, after.map { it.copy(meta = null) })
        assertNotNull(after.first { it.resource == SESSION_A }.meta)
    }

    @Test
    fun `sessions can be added and removed, and an update for an unknown session is ignored`() = runTest {
        val rig = rig(HostScript()).start()

        rig.socket.serverSends(notificationFrame("root/sessionAdded", """{"channel":"ahp-root://","summary":{"resource":"ahp-session:/00000000-0000-4000-8000-0000000000aa","provider":"copilotcli","title":"Brand new","status":8,"createdAt":"2026-10-30T09:00:00.000Z","modifiedAt":"2026-10-30T09:00:00.000Z"}}"""))
        rig.socket.serverSends(notificationFrame("root/sessionRemoved", """{"channel":"ahp-root://","session":"$SESSION_B"}"""))
        rig.socket.serverSends(notificationFrame("root/sessionSummaryChanged", """{"channel":"ahp-root://","session":"ahp-session:/never-seen","changes":{"title":"ghost"}}"""))
        runCurrent()

        assertEquals(6, rig.titles().size)
        assertTrue("Brand new" in rig.titles())
        assertFalse("Session B" in rig.titles())
        assertFalse("ghost" in rig.titles())
    }

    @Test
    fun `a notification that cannot be decoded is counted and does not corrupt the list`() = runTest {
        val rig = rig(HostScript()).start()
        val before = rig.titles()

        rig.socket.serverSends(notificationFrame("root/sessionAdded", """{"channel":"ahp-root://","summary":"nope"}"""))
        rig.socket.serverSends("not json")
        runCurrent()

        assertEquals(before, rig.titles())
        assertEquals(1, rig.host.root.decodeFailures.value)
        assertEquals(1, rig.host.undecodableFrames.value)
    }

    @Test
    fun `an open session is seeded from its snapshot and follows later actions`() = runTest {
        val rig = rig(HostScript()).start()

        val store = rig.host.openSession(SESSION_C)
        runCurrent()
        assertEquals("Session C", store.state.value!!.title)
        assertEquals(listOf("initialize", "listSessions", "subscribe"), rig.socket.sentMethods)

        rig.socket.serverSends(actionFrame(SESSION_C, titleChanged("Renamed"), SNAPSHOT_SEQ + 1))
        runCurrent()
        assertEquals("Renamed", store.state.value!!.title)

        rig.socket.serverSends(actionFrame(SESSION_C, titleChanged("Stale"), SNAPSHOT_SEQ))
        runCurrent()
        assertEquals("Renamed", store.state.value!!.title, "an action at or below the last applied sequence is a duplicate")
    }

    @Test
    fun `a session opened before connecting arrives with the first initialize`() = runTest {
        val rig = rig(HostScript())
        val store = rig.host.openSession(SESSION_C)

        rig.start()

        assertEquals("Session C", store.state.value!!.title)
        assertEquals(listOf("initialize", "listSessions"), rig.socket.sentMethods)
    }

    @Test
    fun `a session the host does not know is marked gone`() = runTest {
        val rig = rig(HostScript(missingSessions = setOf(SESSION_C))).start()

        val store = rig.host.openSession(SESSION_C)
        runCurrent()

        assertIs<SessionGoneException>(store.error.value)
        assertNull(store.state.value)
    }

    @Test
    fun `closing drops the subscription only when the last opener leaves`() = runTest {
        val rig = rig(HostScript()).start()
        val first = rig.host.openSession(SESSION_C)
        assertSame(first, rig.host.openSession(SESSION_C))
        runCurrent()

        rig.host.closeSession(SESSION_C)
        runCurrent()
        assertFalse("unsubscribe" in rig.socket.sentMethods)

        rig.host.closeSession(SESSION_C)
        runCurrent()
        val unsubscribe = rig.socket.sent.first { it["method"]?.jsonPrimitive?.content == "unsubscribe" }
        assertNull(unsubscribe["id"])
        assertEquals(SESSION_C, unsubscribe["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reconnect resumes with the same client id and last seen sequence, replays actions and refetches the list`() = runTest {
        val replay = """{"type":"replay","actions":[{"channel":"$SESSION_C","action":${titleChanged("After reconnect")},"serverSeq":126570}],"missing":[]}"""
        val rig = rig(HostScript(), HostScript(listJson = firstSessionOnly, onReconnect = { id -> result(id, replay) })).start()
        val store = rig.host.openSession(SESSION_C)
        runCurrent()
        rig.socket.serverSends(actionFrame(SESSION_C, titleChanged("Before drop"), 126_565))
        runCurrent()

        rig.socket.serverCloses(1011)
        val waiting = rig.host.awaitState { it is ConnState.Reconnecting }
        assertEquals(1, assertIs<ConnState.Reconnecting>(waiting).attempt)
        rig.host.awaitState { it == ConnState.Online }

        assertEquals(listOf("client-1" to 0L, "client-1" to 126_565L), rig.connector.connects)
        assertEquals(listOf("reconnect", "listSessions"), rig.socket.sentMethods)
        val params = rig.socket.sent.first()["params"]!!.jsonObject
        assertEquals("client-1", params["clientId"]!!.jsonPrimitive.content)
        assertEquals(126_565L, params["lastSeenServerSeq"]!!.jsonPrimitive.long)
        assertEquals(listOf("ahp-root://", SESSION_C), params["subscriptions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("After reconnect", store.state.value!!.title)
        assertEquals(1, rig.titles().size, "the list was replaced, because notifications are not replayed")
    }

    @Test
    fun `reconnect starts over with initialize when the host rejects the client`() = runTest {
        val rig = rig(HostScript(), HostScript(onReconnect = { id -> error(id, -32602, "unknown client") })).start()
        val store = rig.host.openSession(SESSION_C)
        runCurrent()
        rig.socket.serverSends(actionFrame(SESSION_C, titleChanged("Before drop"), 126_565))
        runCurrent()
        assertEquals("Before drop", store.state.value!!.title)

        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }
        rig.host.awaitState { it == ConnState.Online }

        assertEquals(listOf("reconnect", "initialize", "listSessions"), rig.socket.sentMethods)
        assertEquals("Session C", store.state.value!!.title, "a fresh snapshot replaced the state")
    }

    @Test
    fun `a snapshot reconnect result replaces session state and a skipped session subscribes again`() = runTest {
        val covered = """{"type":"snapshot","snapshots":[${sessionSnapshot(SESSION_C, 126_600, "Fresh snapshot")}]}"""
        val skipped = """{"type":"snapshot","snapshots":[]}"""
        for ((reply, title, methods) in listOf(
            Triple(covered, "Fresh snapshot", listOf("reconnect", "listSessions")),
            Triple(skipped, "Session C", listOf("reconnect", "listSessions", "subscribe")),
        )) {
            val rig = rig(HostScript(), HostScript(onReconnect = { id -> result(id, reply) })).start()
            val store = rig.host.openSession(SESSION_C)
            runCurrent()

            rig.socket.serverCloses(1011)
            rig.host.awaitState { it is ConnState.Reconnecting }
            rig.host.awaitState { it == ConnState.Online }

            assertEquals(title, store.state.value!!.title)
            assertEquals(methods, rig.socket.sentMethods)
            rig.host.stop()
        }
    }

    @Test
    fun `a session the host lost during the gap is marked gone and not subscribed again`() = runTest {
        val replay = """{"type":"replay","actions":[],"missing":["$SESSION_C"]}"""
        val rig = rig(HostScript(), HostScript(onReconnect = { id -> result(id, replay) })).start()
        val store = rig.host.openSession(SESSION_C)
        runCurrent()

        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }
        rig.host.awaitState { it == ConnState.Online }

        assertIs<SessionGoneException>(store.error.value)
        assertEquals(listOf("reconnect", "listSessions"), rig.socket.sentMethods)
    }

    @Test
    fun `transient failures back off one second then two and reset after success`() = runTest {
        val rig = rig(HostScript(failure = IOException("boom")), HostScript(failure = IOException("boom")), HostScript())

        rig.start()

        assertEquals(3, rig.connector.connects.size)
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals(ConnState.Online, rig.host.connection.value)
    }

    @Test
    fun `an expired sign in stops retrying`() = runTest {
        val rig = rig(HostScript(failure = DevTunnelsException.AuthExpired()))

        rig.host.start()
        val failed = rig.host.awaitState { it is ConnState.Failed }
        advanceTimeBy(600_000)

        assertIs<DevTunnelsException.AuthExpired>((failed as ConnState.Failed).cause)
        assertEquals(1, rig.connector.connects.size)
    }

    @Test
    fun `stop closes the socket and ends the session`() = runTest {
        val rig = rig(HostScript()).start()
        val socket = rig.socket

        rig.host.stop()

        assertEquals(ConnState.Stopped, rig.host.connection.value)
        assertFalse(socket.send("{}"), "the socket is closed")
    }
}
