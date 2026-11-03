package dev.ahpclient.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentsTest {
    private fun file(label: String = "a.txt", type: String = "text/plain", size: Int = 10) = ComposerAttachment(label, type, ByteArray(size) { 1 })

    @Test
    fun `images and text are accepted, anything else is not`() {
        listOf("image/png", "image/jpeg", "image/webp", "image/gif", "text/plain", "text/markdown", "text/x-kotlin", "application/json", "Text/Plain; charset=utf-8")
            .forEach { assertTrue(Attachments.accepts(it), it) }
        listOf("application/zip", "application/pdf", "application/octet-stream", "video/mp4", "image/svg+xml", "").forEach { assertFalse(Attachments.accepts(it), it) }
    }

    @Test
    fun `a type is stored lower case and without parameters`() {
        assertEquals("text/plain", file(type = "Text/Plain; charset=utf-8").contentType)
    }

    @Test
    fun `a label is trimmed, bounded and never blank`() {
        assertEquals("a b.txt", file(label = "  a b.txt \n").label)
        assertEquals(Attachments.MAX_LABEL, file(label = "x".repeat(500)).label.length)
        assertEquals("attachment", file(label = "   ").label)
    }

    @Test
    fun `a few small files of a good type have no problem`() {
        assertNull(Attachments.problem(emptyList(), vision = false))
        assertNull(Attachments.problem(List(Attachments.MAX_COUNT) { file() }, vision = null))
    }

    @Test
    fun `too many, too big, too big together, unsupported and empty files are each named`() {
        assertEquals(AttachmentProblem.TooMany(Attachments.MAX_COUNT), Attachments.problem(List(Attachments.MAX_COUNT + 1) { file() }, null))
        assertEquals(AttachmentProblem.TooBig("big.txt", Attachments.MAX_BYTES), Attachments.problem(listOf(file("big.txt", size = Attachments.MAX_BYTES + 1)), null))
        assertNull(Attachments.problem(listOf(file("edge.txt", size = Attachments.MAX_BYTES)), null))
        assertEquals(AttachmentProblem.TotalTooBig(Attachments.MAX_TOTAL_BYTES), Attachments.problem(List(2) { file(size = Attachments.MAX_BYTES) }, null))
        assertEquals(AttachmentProblem.Unsupported("a.zip"), Attachments.problem(listOf(file("a.zip", "application/zip")), null))
        assertEquals(AttachmentProblem.Empty("e.txt"), Attachments.problem(listOf(file("e.txt", size = 0)), null))
    }

    @Test
    fun `images are refused only when the host says the model cannot see`() {
        val image = file("p.png", "image/png")
        assertEquals(AttachmentProblem.NoVision, Attachments.problem(listOf(image), vision = false))
        assertNull(Attachments.problem(listOf(image), vision = true))
        assertNull(Attachments.problem(listOf(image), vision = null))
        assertNull(Attachments.problem(listOf(file()), vision = false))
    }

    @Test
    fun `every problem has words for the person`() {
        listOf(
            AttachmentProblem.TooMany(5),
            AttachmentProblem.TooBig("a", Attachments.MAX_BYTES),
            AttachmentProblem.TotalTooBig(Attachments.MAX_TOTAL_BYTES),
            AttachmentProblem.Unsupported("a"),
            AttachmentProblem.Empty("a"),
            AttachmentProblem.NoVision,
        ).forEach { assertTrue(Attachments.describe(it).isNotBlank()) }
        assertEquals("a is larger than 5 MB.", Attachments.describe(AttachmentProblem.TooBig("a", Attachments.MAX_BYTES)))
    }
}
