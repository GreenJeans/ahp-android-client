package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChangesSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChangeStatsTest {
    @Test
    fun `the totals read as plus and minus in brackets, and are spoken in words`() {
        val stats = ChangeStats.of(ChangesSummary(additions = 1072, deletions = 8, files = 14))!!

        assertEquals("(+1072, \u22128)", stats.text)
        assertEquals("1072 lines added, 8 removed, in 14 files", stats.spoken)
    }

    @Test
    fun `one line and one file are singular`() {
        assertEquals("1 line added, 0 removed, in 1 file", ChangeStats.of(ChangesSummary(additions = 1, deletions = 0, files = 1))!!.spoken)
    }

    @Test
    fun `a missing half counts as none, and the file count is optional`() {
        val stats = ChangeStats.of(ChangesSummary(additions = 5))!!

        assertEquals("(+5, \u22120)", stats.text)
        assertEquals("5 lines added, 0 removed", stats.spoken)
    }

    @Test
    fun `nothing changed, or nothing said, is nothing to show`() {
        assertNull(ChangeStats.of(null))
        assertNull(ChangeStats.of(ChangesSummary()))
        assertNull(ChangeStats.of(ChangesSummary(additions = 0, deletions = 0, files = 3)))
    }

    @Test
    fun `a negative total from the host is not shown as one`() {
        assertEquals("(+7, \u22120)", ChangeStats.of(ChangesSummary(additions = 7, deletions = -3))!!.text)
        assertNull(ChangeStats.of(ChangesSummary(additions = -1, deletions = -1)))
    }
}