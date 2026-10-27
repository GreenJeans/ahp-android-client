package dev.ahpclient.security

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.SessionSummary
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.Secret
import dev.ahpclient.state.fixture
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SessionListCacheTest {
    private val sessions = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items

    private val box = FakeBox()
    private val store = FakeStore()
    private val cache = SessionListCache(box, store)

    /** [count] sessions, session 0 the oldest, so the newest are the highest numbers. */
    private fun many(count: Int, title: (Int) -> String = { "Session $it" }): List<SessionSummary> = (0 until count).map { n ->
        sessions.first().copy(
            resource = "ahp-session:/%08d-0000-4000-8000-000000000000".format(n),
            title = title(n),
            modifiedAt = "2026-10-22T10:%02d:%02d.000Z".format(n / 60 % 60, n % 60),
        )
    }

    @Test
    fun `a saved list reads back whole, for the host it was saved for`() = runTest {
        cache.save("tunnel-1", sessions)

        assertEquals(sessions.sortedByDescending { it.modifiedAt }, cache.load("tunnel-1"))
    }

    @Test
    fun `nothing saved reads as absent`() = runTest {
        assertNull(cache.load("tunnel-1"))
        assertEquals(0, store.deletes)
    }

    @Test
    fun `what is kept is sealed for its own purpose, not the token's`() = runTest {
        cache.save("tunnel-1", sessions)

        assertContentEquals(SessionListCache.AAD, box.lastAad)
        assertTrue(!SessionListCache.AAD.contentEquals(TokenVault.AAD))
    }

    @Test
    fun `more than 500 sessions are cut to the 500 most recently changed`() = runTest {
        val all = many(600)

        cache.save("tunnel-1", all.shuffled())

        val kept = assertNotNull(cache.load("tunnel-1"))
        assertEquals(SessionListCache.MAX_ITEMS, kept.size)
        assertEquals(all.takeLast(500).map { it.resource }.toSet(), kept.map { it.resource }.toSet())
    }

    @Test
    fun `a list over the size limit drops the oldest until it fits`() = runTest {
        val big = "x".repeat(40_000)
        val all = many(120) { "$it $big" }

        cache.save("tunnel-1", all)

        val kept = assertNotNull(cache.load("tunnel-1"))
        assertTrue(kept.size in 1 until all.size, "kept ${kept.size}")
        assertTrue(assertNotNull(store.blob).size <= SessionListCache.MAX_BYTES)
        assertEquals(all.takeLast(kept.size).map { it.resource }.toSet(), kept.map { it.resource }.toSet(), "the newest are the ones that stay")
    }

    @Test
    fun `a blob that is damaged is removed and reads as absent`() = runTest {
        store.blob = byteArrayOf(1, 2, 3)

        assertNull(cache.load("tunnel-1"))

        assertNull(store.blob)
        assertEquals(1, store.deletes)
        assertEquals(0, box.destroyed, "the key is the vault's as well, so it is never destroyed here")
    }

    @Test
    fun `a blob that cannot be decrypted is removed and reads as absent`() = runTest {
        cache.save("tunnel-1", sessions)
        box.keyExists = false

        assertNull(cache.load("tunnel-1"))

        assertNull(store.blob)
        assertEquals(0, box.destroyed)
    }

    @Test
    fun `a token blob offered as a list is refused and removed`() = runTest {
        TokenVault(box, store).save(GitHubToken(Secret("ghu_saved"), null, null))

        assertNull(cache.load("tunnel-1"))

        assertNull(store.blob)
    }

    @Test
    fun `a list blob offered as a token is refused`() = runTest {
        cache.save("tunnel-1", sessions)

        val loaded = TokenVault(box, store).load()

        assertIs<VaultLoad.Unreadable>(loaded)
    }

    @Test
    fun `a list saved for another host reads as absent and is removed`() = runTest {
        cache.save("tunnel-1", sessions)

        assertNull(cache.load("tunnel-2"))

        assertNull(store.blob)
        assertNull(cache.load("tunnel-1"), "it is gone, not just hidden")
    }

    @Test
    fun `a list of another format version reads as absent and is removed`() = runTest {
        store.blob = box.seal("""{"v":2,"tunnelId":"tunnel-1","savedAt":"2026-10-22T10:00:00Z","items":[]}""".toByteArray(), SessionListCache.AAD)

        assertNull(cache.load("tunnel-1"))

        assertNull(store.blob)
    }

    @Test
    fun `content that is not a list is removed`() = runTest {
        listOf(
            "not json at all",
            "[]",
            """{"v":1,"tunnelId":"tunnel-1","savedAt":"x"}""",
            """{"v":1,"tunnelId":"tunnel-1","savedAt":"x","items":[{"title":"missing the rest"}]}""",
        ).forEach { content ->
            store.blob = box.seal(content.toByteArray(), SessionListCache.AAD)

            assertNull(cache.load("tunnel-1"), content)

            assertNull(store.blob, content)
        }
    }

    @Test
    fun `a disk error on read is not a verdict on the blob`() = runTest {
        cache.save("tunnel-1", sessions)
        store.readFailure = IOException("disk busy")

        assertNull(cache.load("tunnel-1"))

        store.readFailure = null
        assertNotNull(cache.load("tunnel-1"), "it was left alone")
    }

    @Test
    fun `a blob that cannot be removed does not fail the load`() = runTest {
        store.blob = byteArrayOf(9)
        store.deleteFailure = IOException("read only")

        assertNull(cache.load("tunnel-1"))
    }

    @Test
    fun `a key that will not seal is reported and left alone`() = runTest {
        box.failNextSeals = 1

        assertFailsWith<VaultCryptoException> { cache.save("tunnel-1", sessions) }

        assertEquals(0, box.destroyed)
        assertNull(store.blob)
    }

    @Test
    fun `clear removes the list`() = runTest {
        cache.save("tunnel-1", sessions)

        cache.clear()

        assertNull(store.blob)
        assertNull(cache.load("tunnel-1"))
        assertEquals(0, box.destroyed, "wiping the list leaves the key to the vault")
    }

    @Test
    fun `saving again replaces what was there`() = runTest {
        cache.save("tunnel-1", sessions)
        cache.save("tunnel-2", many(2))

        assertNull(cache.load("tunnel-1"))
    }
}
