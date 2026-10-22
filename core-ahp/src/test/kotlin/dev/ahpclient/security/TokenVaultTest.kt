package dev.ahpclient.security

import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.Secret
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TokenVaultTest {
    private val token = GitHubToken(Secret("ghu_access_value"), Secret("ghr_refresh_value"), Instant.parse("2026-10-23T04:00:00Z"))
    private val box = FakeBox()
    private val store = FakeStore()
    private val vault = TokenVault(box, store)

    @Test
    fun `an empty vault reports Absent`() = runTest {
        assertEquals(VaultLoad.Absent, vault.load())
    }

    @Test
    fun `a saved token comes back whole`() = runTest {
        vault.save(token)

        val loaded = assertIs<VaultLoad.Present>(vault.load()).token

        assertEquals("ghu_access_value", loaded.access.reveal())
        assertEquals("ghr_refresh_value", loaded.refresh?.reveal())
        assertEquals(token.expiresAt, loaded.expiresAt)
    }

    @Test
    fun `optional fields survive being absent`() = runTest {
        vault.save(GitHubToken(Secret("only-access"), null, null))

        val loaded = assertIs<VaultLoad.Present>(vault.load()).token

        assertNull(loaded.refresh)
        assertNull(loaded.expiresAt)
    }

    @Test
    fun `the blob is sealed with the vault's purpose binding and holds no plaintext`() = runTest {
        vault.save(token)

        assertContentEquals(TokenVault.AAD, box.lastAad)
        assertFalse(String(store.blob!!, Charsets.ISO_8859_1).contains("ghu_access_value"), "the FakeBox reverses the text, so this only guards the vault not storing plaintext itself")
    }

    @Test
    fun `saving again replaces the token`() = runTest {
        vault.save(token)
        vault.save(GitHubToken(Secret("second"), null, null))

        assertEquals("second", assertIs<VaultLoad.Present>(vault.load()).token.access.reveal())
    }

    @Test
    fun `a lost key wipes the blob and asks for a new sign in`() = runTest {
        vault.save(token)
        box.keyExists = false

        val result = assertIs<VaultLoad.Unreadable>(vault.load())

        assertTrue(result.reason.contains("decrypted"))
        assertNull(store.blob)
        assertEquals(VaultLoad.Absent, vault.load(), "wiped, so the next load is simply empty")
    }

    @Test
    fun `a damaged blob is wiped the same way`() = runTest {
        vault.save(token)
        store.blob = byteArrayOf(1, 2, 3)

        assertIs<VaultLoad.Unreadable>(vault.load())

        assertNull(store.blob)
        assertTrue(box.destroyed >= 1, "the key is destroyed too, so nothing stale lingers")
    }

    @Test
    fun `a blob that decrypts but is not a token is wiped`() = runTest {
        store.blob = byteArrayOf(0x42, 0x58) + "not json".toByteArray().reversedArray()
        box.lastAad = TokenVault.AAD

        val result = assertIs<VaultLoad.Unreadable>(vault.load())

        assertTrue(result.reason.contains("could not be read"))
        assertNull(store.blob)
    }

    @Test
    fun `a disk error is not treated as a lost credential`() = runTest {
        vault.save(token)
        store.readFailure = IOException("disk busy")

        assertFailsWith<IOException> { vault.load() }

        store.readFailure = null
        assertIs<VaultLoad.Present>(vault.load())
        assertEquals(0, store.deletes)
    }

    @Test
    fun `an unusable key does not block signing in again`() = runTest {
        box.failNextSeals = 1

        vault.save(token)

        assertEquals(1, box.destroyed, "the broken key was replaced once")
        assertIs<VaultLoad.Present>(vault.load())
    }

    @Test
    fun `a key that stays unusable surfaces the failure`() = runTest {
        box.failNextSeals = 2

        assertFailsWith<VaultCryptoException> { vault.save(token) }
        assertNull(store.blob)
    }

    @Test
    fun `clear removes the blob and then the key`() = runTest {
        vault.save(token)

        vault.clear()

        assertNull(store.blob)
        assertFalse(box.keyExists)
        assertEquals(VaultLoad.Absent, vault.load())
    }

    @Test
    fun `clear on an empty vault is harmless`() = runTest {
        vault.clear()

        assertEquals(VaultLoad.Absent, vault.load())
    }

    @Test
    fun `an unreadable result never carries token text`() = runTest {
        vault.save(token)
        box.keyExists = false

        val result = vault.load()

        assertFalse("$result".contains("ghu_access_value"))
    }
}

class TokenCodecTest {
    @Test
    fun `round trips every field`() {
        val token = GitHubToken(Secret("a"), Secret("r"), Instant.parse("2026-10-23T04:00:00.123Z"))

        val back = TokenCodec.decode(TokenCodec.encode(token))

        assertEquals("a", back.access.reveal())
        assertEquals("r", back.refresh?.reveal())
        assertEquals(token.expiresAt, back.expiresAt)
    }

    @Test
    fun `rejects anything it cannot read, with a typed error`() {
        listOf("", "not json", "[]", """{"v":1}""", """{"v":2,"access":"x"}""", """{"access":"x"}""", """{"v":1,"access":"x","expiresAt":"tomorrow"}""", """{"v":1,"access":5}""").forEach { text ->
            assertFailsWith<TokenCodecException>(text) { TokenCodec.decode(text.toByteArray()) }
        }
    }

    @Test
    fun `error messages never contain the token`() {
        val message = assertFailsWith<TokenCodecException> {
            TokenCodec.decode("""{"v":1,"access":"ghu_secret","expiresAt":"tomorrow"}""".toByteArray())
        }.message!!

        assertFalse(message.contains("ghu_secret"))
    }
}
