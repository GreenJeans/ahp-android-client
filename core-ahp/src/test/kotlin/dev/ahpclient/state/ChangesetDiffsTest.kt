package dev.ahpclient.state

import dev.ahpclient.ahp.AhpException
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChangesetFile
import com.microsoft.agenthostprotocol.generated.ContentEncoding
import com.microsoft.agenthostprotocol.generated.ResourceReadResult
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class ChangesetDiffsTest {
    private fun file(
        id: String = "f1",
        before: String? = "ahp-content:/before",
        after: String? = "ahp-content:/after",
        beforeSize: Long? = null,
        afterSize: Long? = null,
        added: Long? = null,
        removed: Long? = null,
    ): ChangesetFile {
        fun side(uri: String?, size: Long?) = uri?.let { """{"uri":"file:///work/a.kt","content":{"uri":"$it"${size?.let { s -> ""","sizeHint":$s""" }.orEmpty()}}}""" }
        val parts = listOfNotNull(
            side(before, beforeSize)?.let { """"before":$it""" },
            side(after, afterSize)?.let { """"after":$it""" },
            if (added != null || removed != null) """"diff":{"added":${added ?: 0},"removed":${removed ?: 0}}""" else null,
        )
        return Ahp.json.decodeFromString(ChangesetFile.serializer(), """{"id":"$id","edit":{${parts.joinToString(",")}}}""")
    }

    private fun text(data: String) = ResourceReadResult(data = data, encoding = ContentEncoding.UTF8)

    private fun base64(bytes: ByteArray) = ResourceReadResult(data = Base64.getEncoder().encodeToString(bytes), encoding = ContentEncoding.BASE64)

    private class Rig(var files: List<ChangesetFile>, val answers: MutableMap<String, () -> ResourceReadResult> = mutableMapOf()) {
        val asked = mutableListOf<String>()
        val diffs = ChangesetDiffs(files = { files }, read = { uri ->
            asked += uri
            (answers[uri] ?: error("unexpected read of $uri"))()
        })
    }

    private fun rig(files: List<ChangesetFile> = listOf(file()), before: String = "a\nb\nc\n", after: String = "a\nB\nc\n"): Rig =
        Rig(files).also {
            it.answers["ahp-content:/before"] = { text(before) }
            it.answers["ahp-content:/after"] = { text(after) }
        }

    @Test
    fun `a modified file reads its own two sides, before first, and returns the line diff`() = runTest {
        val rig = rig()

        val result = assertIs<DiffResult.Ready>(rig.diffs.load("f1"))

        assertEquals(listOf("ahp-content:/before", "ahp-content:/after"), rig.asked)
        assertEquals(1, result.diff.added)
        assertEquals(1, result.diff.removed)
    }

    @Test
    fun `an added file reads only its after side and a deleted one only its before side`() = runTest {
        val added = rig(files = listOf(file(before = null)))
        val deleted = rig(files = listOf(file(after = null)))

        assertEquals(3, assertIs<DiffResult.Ready>(added.diffs.load("f1")).diff.added)
        assertEquals(3, assertIs<DiffResult.Ready>(deleted.diffs.load("f1")).diff.removed)

        assertEquals(listOf("ahp-content:/after"), added.asked)
        assertEquals(listOf("ahp-content:/before"), deleted.asked)
    }

    @Test
    fun `an id the changeset does not list reads nothing, and the list is looked at each time`() = runTest {
        val rig = rig()

        assertEquals(DiffResult.NoSuchFile, rig.diffs.load("somewhere/else"))
        assertEquals(DiffResult.NoSuchFile, rig.diffs.load(""))
        assertTrue(rig.asked.isEmpty())

        rig.files = emptyList()
        assertEquals(DiffResult.NoSuchFile, rig.diffs.load("f1"))
        assertTrue(rig.asked.isEmpty(), "a file that left the changeset is not read")
    }

    @Test
    fun `a side that is too big by the host's own hint is never requested`() = runTest {
        val before = rig(files = listOf(file(beforeSize = ChangesetDiffs.MAX_BYTES + 1)))
        val after = rig(files = listOf(file(afterSize = ChangesetDiffs.MAX_BYTES + 1)))
        val fits = rig(files = listOf(file(beforeSize = ChangesetDiffs.MAX_BYTES, afterSize = ChangesetDiffs.MAX_BYTES)))

        assertEquals(DiffResult.TooLarge, before.diffs.load("f1"))
        assertTrue(before.asked.isEmpty())
        assertEquals(DiffResult.TooLarge, after.diffs.load("f1"))
        assertEquals(listOf("ahp-content:/before"), after.asked, "nothing is read after the side that is too big")
        assertIs<DiffResult.Ready>(fits.diffs.load("f1"))
    }

    @Test
    fun `an answer that is bigger than the hint promised, or too many lines, is too large`() = runTest {
        val bigAnswer = rig(before = "x".repeat(400_000))
        val manyLines = rig(after = (1..LineDiff.MAX_LINES + 1).joinToString("\n") { "l$it" })

        assertEquals(DiffResult.TooLarge, bigAnswer.diffs.load("f1"))
        assertEquals(DiffResult.TooLarge, manyLines.diffs.load("f1"))
    }

    @Test
    fun `two files that differ in more lines than the limit are too large to compare`() = runTest {
        val rig = rig(before = (1..600).joinToString("\n") { "old $it" }, after = (1..600).joinToString("\n") { "new $it" })

        assertEquals(DiffResult.TooLarge, rig.diffs.load("f1"))
    }

    @Test
    fun `a file that is not text is not shown`() = runTest {
        val nul = rig(after = "a\u0000b")
        assertEquals(DiffResult.Binary, nul.diffs.load("f1"))

        val encoded = rig()
        encoded.answers["ahp-content:/after"] = { base64(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 1)) }
        assertEquals(DiffResult.Binary, encoded.diffs.load("f1"))

        val notUtf8 = rig()
        notUtf8.answers["ahp-content:/after"] = { base64(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertEquals(DiffResult.Binary, notUtf8.diffs.load("f1"))

        val notEncoded = rig()
        notEncoded.answers["ahp-content:/after"] = { ResourceReadResult(data = "***not base64***", encoding = ContentEncoding.BASE64) }
        assertEquals(DiffResult.Binary, notEncoded.diffs.load("f1"))
    }

    @Test
    fun `text a host sends as base64 is decoded`() = runTest {
        val rig = rig()
        rig.answers["ahp-content:/after"] = { base64("a\nB\nc\n".toByteArray()) }

        val result = assertIs<DiffResult.Ready>(rig.diffs.load("f1"))

        assertEquals(1, result.diff.added)
    }

    @Test
    fun `a file with removed lines but no earlier version cannot be compared`() = runTest {
        val rig = rig(files = listOf(file(before = null, added = 3, removed = 2)))

        assertEquals(DiffResult.NoEarlierVersion, rig.diffs.load("f1"))
        assertTrue(rig.asked.isEmpty())
    }

    @Test
    fun `a file with an after side only and nothing removed is all added`() = runTest {
        val rig = rig(files = listOf(file(before = null, added = 3, removed = 0)))

        assertEquals(3, assertIs<DiffResult.Ready>(rig.diffs.load("f1")).diff.added)
    }

    @Test
    fun `a blank or very long content address is never used`() = runTest {
        val blank = rig(files = listOf(file(before = " ")))
        val long = rig(files = listOf(file(after = "ahp-content:/" + "x".repeat(3_000))))

        assertIs<DiffResult.Failed>(blank.diffs.load("f1"))
        assertIs<DiffResult.Failed>(long.diffs.load("f1"))
        assertTrue(blank.asked.isEmpty())
        assertEquals(listOf("ahp-content:/before"), long.asked, "the long address is never asked for")
    }

    @Test
    fun `a failure to read is a reason in words, not a crash, and the host's words are bounded`() = runTest {
        val refused = rig()
        refused.answers["ahp-content:/before"] = { throw AhpException.Rpc(-32008, "x".repeat(500), null) }
        val dropped = rig()
        dropped.answers["ahp-content:/before"] = { throw AhpException.ConnectionClosed(null) }
        val offline = rig()
        offline.answers["ahp-content:/before"] = { throw IOException("offline") }

        val said = assertIs<DiffResult.Failed>(refused.diffs.load("f1")).reason
        assertTrue(said.startsWith("The host said: "))
        assertTrue(said.length <= "The host said: ".length + 200, "the host's own words are cut: ${said.length}")
        assertIs<DiffResult.Failed>(dropped.diffs.load("f1"))
        assertIs<DiffResult.Failed>(offline.diffs.load("f1"))
    }

    @Test
    fun `cancelling while reading is not swallowed`() = runTest {
        val rig = rig()
        rig.answers["ahp-content:/before"] = { throw CancellationException("stop") }

        assertFailsWith<CancellationException> { rig.diffs.load("f1") }
    }
}
