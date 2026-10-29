package dev.ahpclient.probe

import dev.ahpclient.ahp.AhpConnection
import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.ahp.FakeSocket
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeTest {
    @Test
    fun `ping-seconds accepts zero to turn pings off and positive intervals, and rejects negative or non-numeric values`() {
        assertEquals(30, ProbeArgs.parse(emptyArray()).pingSeconds)
        assertEquals(0, ProbeArgs.parse(arrayOf("--ping-seconds", "0")).pingSeconds)
        assertEquals(45, ProbeArgs.parse(arrayOf("--ping-seconds", "45")).pingSeconds)

        assertFailsWith<IllegalArgumentException> { ProbeArgs.parse(arrayOf("--ping-seconds", "-1")) }
        assertFailsWith<IllegalArgumentException> { ProbeArgs.parse(arrayOf("--ping-seconds", "abc")) }
        assertFailsWith<IllegalArgumentException> { ProbeArgs.parse(arrayOf("--ping-seconds")) }
    }

    @Test
    fun `every probe option parses and out-of-range or unknown options fail`() {
        val parsed = ProbeArgs.parse(
            arrayOf(
                "--tunnel", "t1",
                "--instance", "i1",
                "--capture", "captures/out.jsonl",
                "--watch", "120",
                "--ping-seconds", "0",
                "--session", "s1",
                "--chat", "c1",
                "--direct", "ws://127.0.0.1:9187",
                "--census",
                "--census-sessions", "15",
                "--census-out", "captures/census.txt",
            ),
        )

        assertEquals("t1", parsed.tunnel)
        assertEquals("i1", parsed.instance)
        assertEquals(Paths.get("captures/out.jsonl"), parsed.capture)
        assertEquals(120, parsed.watchSeconds)
        assertEquals(0, parsed.pingSeconds)
        assertEquals("s1", parsed.session)
        assertEquals("c1", parsed.chat)
        assertEquals("ws://127.0.0.1:9187", parsed.direct)
        assertTrue(parsed.census)
        assertEquals(15, parsed.censusSessions)
        assertEquals(Paths.get("captures/census.txt"), parsed.censusOut)

        assertFailsWith<IllegalArgumentException> { ProbeArgs.parse(arrayOf("--watch", "-1")) }
        assertFailsWith<IllegalArgumentException> { ProbeArgs.parse(arrayOf("--census-sessions", "0")) }
        assertFailsWith<IllegalArgumentException> { ProbeArgs.parse(arrayOf("--census-sessions", "51")) }
        assertFailsWith<IllegalArgumentException> { ProbeArgs.parse(arrayOf("--unknown")) }
    }

    @Test
    fun `watch logs the elapsed seconds and close code when an idle socket is closed by the peer`() = runTest {
        val socket = FakeSocket()
        val ahp = AhpConnection(socket, backgroundScope, CapabilityPolicy.READ)
        val lines = mutableListOf<String>()
        val ticks = ArrayDeque(listOf(0L, TimeUnit.SECONDS.toNanos(100)))
        val probe = Probe(ProbeArgs(pingSeconds = 0, watchSeconds = 600), lines::add)

        socket.serverCloses(1006)
        runCurrent()
        probe.watch(ahp, seconds = 600, nowNs = { ticks.removeFirst() })

        assertTrue(lines.any { "ping=off" in it }, lines.toString())
        assertTrue(lines.any { "socket closed after 100s: Socket closed (code=1006, reason=(empty))" in it }, lines.toString())
    }
}
