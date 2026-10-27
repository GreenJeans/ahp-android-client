package dev.ahpclient.security

import dev.ahpclient.auth.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DirectHostVaultTest {
    private val box = FakeBox()
    private val store = FakeStore()
    private val vault = DirectHostVault(box, store)

    @Test
    fun `a token comes back for its own host only`() = runTest {
        vault.put("direct:1", Secret("one"))
        vault.put("direct:2", Secret("two"))

        assertEquals("one", vault.get("direct:1")?.reveal())
        assertEquals("two", vault.get("direct:2")?.reveal())
        assertNull(vault.get("direct:3"))
        assertTrue(vault.has("direct:1"))
    }

    @Test
    fun `the stored blob is sealed for this purpose and holds no plain token`() = runTest {
        vault.put("direct:1", Secret("hunter2-token"))

        assertTrue(DirectHostVault.AAD.contentEquals(box.lastAad))
        assertFalse(DirectHostVault.AAD.contentEquals(TokenVault.AAD))
        assertFalse(String(store.blob!!, Charsets.ISO_8859_1).contains("hunter2-token"), "the fake box reverses the text; a plain copy would still show")
    }

    @Test
    fun `removing a token keeps the others, and the last one removes the blob and key`() = runTest {
        vault.put("direct:1", Secret("one"))
        vault.put("direct:2", Secret("two"))

        vault.remove("direct:1")
        assertNull(vault.get("direct:1"))
        assertEquals("two", vault.get("direct:2")?.reveal())

        vault.remove("direct:2")
        assertNull(store.blob)
        assertEquals(1, box.destroyed)
    }

    @Test
    fun `a blank token keeps nothing`() = runTest {
        vault.put("direct:1", Secret("one"))
        vault.put("direct:1", Secret("  "))

        assertNull(vault.get("direct:1"))
        vault.remove("direct:9")
    }

    @Test
    fun `a blob that cannot be opened is dropped with its key and reads as no tokens`() = runTest {
        vault.put("direct:1", Secret("one"))
        box.keyExists = false

        assertNull(vault.get("direct:1"))
        assertNull(store.blob)

        vault.put("direct:1", Secret("again"))
        assertEquals("again", vault.get("direct:1")?.reveal())
    }

    @Test
    fun `a damaged blob is dropped`() = runTest {
        store.blob = byteArrayOf(1, 2, 3)

        assertNull(vault.get("direct:1"))
        assertNull(store.blob)
    }

    @Test
    fun `an unusable key is replaced once when saving, and a second failure surfaces`() = runTest {
        box.failNextSeals = 1
        vault.put("direct:1", Secret("one"))
        assertNotNull(store.blob)

        box.failNextSeals = 2
        assertFailsWith<VaultCryptoException> { vault.put("direct:2", Secret("two")) }
    }

    @Test
    fun `the github vault and this one do not open each other's blobs`() = runTest {
        val github = TokenVault(box, store)
        github.save(dev.ahpclient.auth.GitHubToken(Secret("ghu"), null, null))
        assertNull(vault.get("direct:1"))
    }
}
