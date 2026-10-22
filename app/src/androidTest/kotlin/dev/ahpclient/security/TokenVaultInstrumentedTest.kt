package dev.ahpclient.security

import android.content.Context
import android.security.keystore.KeyInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.Secret
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device or emulator against the real AndroidKeyStore and a real DataStore file. */
@RunWith(AndroidJUnit4::class)
class TokenVaultInstrumentedTest {
    private val runId = UUID.randomUUID().toString().take(8)
    private val alias = "ahp-test-key-$runId"
    private val extraAliases = mutableListOf<String>()
    private lateinit var file: File
    private lateinit var job: Job
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var box: KeystoreSecureBox
    private lateinit var store: DataStoreBlobStore
    private lateinit var vault: TokenVault

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // Unique per run, so a leftover from an earlier failed run cannot make an assertion pass by accident.
    private val accessText = "ghu_access_$runId"
    private val refreshText = "ghr_refresh_$runId"
    private val token = GitHubToken(Secret(accessText), Secret(refreshText), Instant.parse("2026-10-23T04:00:00Z"))

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "vault-test-$runId.preferences_pb")
        job = SupervisorJob()
        dataStore = DataStoreBlobStore.dataStoreFor(file, CoroutineScope(job + Dispatchers.IO))
        box = KeystoreSecureBox(alias)
        store = DataStoreBlobStore(dataStore)
        vault = TokenVault(box, store)
    }

    @After
    fun tearDown() {
        runBlocking { job.cancelAndJoin() }
        file.delete()
        (extraAliases + alias).forEach { if (keyStore.containsAlias(it)) keyStore.deleteEntry(it) }
    }

    private fun key(): SecretKey = keyStore.getKey(alias, null) as SecretKey

    // JUnit 4 rejects test methods that return a value, and `= runBlocking { ...; assertThrows(...) }` returns its last expression.
    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }

    @Test
    fun savedTokenComesBackWhole() = runTest {
        vault.save(token)

        val loaded = (vault.load() as VaultLoad.Present).token

        assertEquals(accessText, loaded.access.reveal())
        assertEquals(refreshText, loaded.refresh?.reveal())
        assertEquals(token.expiresAt, loaded.expiresAt)
    }

    @Test
    fun theFileOnDiskHoldsCiphertextNotTheToken() = runTest {
        vault.save(token)

        // DataStore writes asynchronously through its own scope; the save above has completed its edit, so the file is final.
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)

        assertTrue("the DataStore file should exist and not be empty", raw.isNotEmpty())
        assertFalse("access token must not appear on disk", raw.contains(accessText))
        assertFalse("refresh token must not appear on disk", raw.contains(refreshText))
        assertFalse("the JSON field names must not appear on disk", raw.contains("\"access\""))
    }

    @Test
    fun theKeyNeverLeavesTheKeystoreAndIs256BitAesWithoutUserAuthentication() = runTest {
        vault.save(token)

        val key = key()
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo

        assertNull("key material must not be extractable", key.encoded)
        assertEquals("AES", key.algorithm)
        assertEquals(256, info.keySize)
        assertFalse("background monitoring needs the token while the phone is locked", info.isUserAuthenticationRequired)
        assertEquals(alias, info.keystoreAlias)
    }

    @Test
    fun eachSealUsesAFreshIvAndTheBlobHasTheDocumentedLayout() {
        val plain = "same plaintext".toByteArray()

        val first = box.seal(plain, TokenVault.AAD)
        val second = box.seal(plain, TokenVault.AAD)

        assertEquals("format byte", 1, first[0].toInt())
        assertEquals("1 + 12 byte IV + ciphertext + 16 byte tag", 1 + 12 + plain.size + 16, first.size)
        assertNotEquals("same IV reused", first.copyOfRange(1, 13).toList(), second.copyOfRange(1, 13).toList())
        assertNotEquals("identical ciphertext for identical input", first.toList(), second.toList())
        assertArrayEquals(plain, box.open(first, TokenVault.AAD))
    }

    @Test
    fun aBlobCannotBeOpenedForADifferentPurpose() {
        val blob = box.seal("secret".toByteArray(), TokenVault.AAD)

        assertThrows(VaultCryptoException::class.java) { box.open(blob, "another-slot".toByteArray()) }
    }

    @Test
    fun aTamperedBlobIsRejectedAndTheVaultWipesIt() = runTest {
        vault.save(token)
        val blob = store.read()!!
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        store.write(blob)

        val result = vault.load()

        assertTrue("expected Unreadable but was $result", result is VaultLoad.Unreadable)
        assertNull("the damaged blob must be removed", store.read())
        assertFalse("the key is destroyed with it", keyStore.containsAlias(alias))
    }

    @Test
    fun aKeyThatDisappearsMeansSignInAgain() = runTest {
        vault.save(token)
        keyStore.deleteEntry(alias) // what a restore onto a new device, or a Keystore reset, looks like

        val result = vault.load()

        assertTrue("expected Unreadable but was $result", result is VaultLoad.Unreadable)
        assertNull(store.read())
        assertEquals(VaultLoad.Absent, vault.load())
    }

    @Test
    fun signingInAgainAfterALostKeyWorks() = runTest {
        vault.save(token)
        keyStore.deleteEntry(alias)
        vault.load()

        vault.save(token)

        assertEquals(accessText, (vault.load() as VaultLoad.Present).token.access.reveal())
    }

    @Test
    fun clearRemovesTheBlobAndTheKey() = runTest {
        vault.save(token)
        assertTrue(keyStore.containsAlias(alias))

        vault.clear()

        assertNull(store.read())
        assertFalse(keyStore.containsAlias(alias))
        assertEquals(VaultLoad.Absent, vault.load())
    }

    @Test
    fun anotherInstanceWithTheSameAliasReadsTheTokenBecauseTheKeyPersists() = runTest {
        vault.save(token)

        val restarted = TokenVault(KeystoreSecureBox(alias), store)

        assertEquals(accessText, (restarted.load() as VaultLoad.Present).token.access.reveal())
    }

    @Test
    fun keysWithDifferentAliasesAreIndependent() = runTest {
        val otherAlias = "ahp-test-other-$runId".also(extraAliases::add)
        val blob = box.seal("secret".toByteArray(), TokenVault.AAD)
        KeystoreSecureBox(otherAlias).seal("x".toByteArray(), TokenVault.AAD) // creates the second key

        assertThrows(VaultCryptoException::class.java) { KeystoreSecureBox(otherAlias).open(blob, TokenVault.AAD) }
    }

    @Test
    fun textThatIsNotBase64IsTreatedAsADamagedBlob() = runTest {
        dataStore.edit { it[stringPreferencesKey("token_v1")] = "***not base64***" }

        val result = vault.load()

        assertTrue("expected Unreadable but was $result", result is VaultLoad.Unreadable)
        assertNull(store.read())
    }

    @Test
    fun aDamagedDataStoreFileDoesNotCrashAndReadsAsEmpty() = runTest {
        vault.save(token)
        job.cancelAndJoin()
        file.writeBytes(ByteArray(64) { 0x7f }) // not a valid preferences protobuf

        val reopenedJob = SupervisorJob()
        try {
            val reopened = DataStoreBlobStore(DataStoreBlobStore.dataStoreFor(file, CoroutineScope(reopenedJob + Dispatchers.IO)))

            assertNull("a corrupt file is replaced with an empty one", reopened.read())
        } finally {
            reopenedJob.cancelAndJoin()
        }
    }

    @Test
    fun theTokenSurvivesReopeningTheDataStoreFile() = runTest {
        vault.save(token)
        job.cancelAndJoin()

        val reopenedJob = SupervisorJob()
        try {
            val reopened = TokenVault(box, DataStoreBlobStore(DataStoreBlobStore.dataStoreFor(file, CoroutineScope(reopenedJob + Dispatchers.IO))))

            assertEquals(accessText, (reopened.load() as VaultLoad.Present).token.access.reveal())
        } finally {
            reopenedJob.cancelAndJoin()
        }
    }

    @Test
    fun theKeyIsCreatedOnFirstSaveNotBefore() = runTest {
        assertFalse(keyStore.containsAlias(alias))
        assertEquals(VaultLoad.Absent, vault.load())
        assertFalse("loading an empty vault must not create a key", keyStore.containsAlias(alias))

        vault.save(token)

        assertNotNull(keyStore.getKey(alias, null))
    }
}
