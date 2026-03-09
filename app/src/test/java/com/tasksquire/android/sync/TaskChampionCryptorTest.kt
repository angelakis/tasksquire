package com.tasksquire.android.sync

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Tests for TaskChampion encryption implementation.
 *
 * Verifies:
 * - Encryption round-trip (encrypt then decrypt recovers original plaintext)
 * - AAD construction (17 bytes: app_id + version UUID)
 * - Envelope format (version byte + 12-byte nonce + ciphertext)
 * - Key derivation consistency
 * - Different nonces produce different ciphertexts
 * - Wrong key/secret fails to decrypt
 * - Wrong version ID in AAD fails to decrypt
 */
class TaskChampionCryptorTest {

    private val testClientId = "d1b2c3d4-e5f6-7890-abcd-ef1234567890"
    private val testSecret = "my-super-secret-passphrase"
    private val testVersionId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    @Test
    fun `encrypt then decrypt recovers plaintext`() {
        val cryptor = TaskChampionCryptor(testClientId, testSecret)
        val plaintext = "Hello, TaskChampion!".toByteArray()

        val sealed = cryptor.seal(testVersionId, plaintext)
        val decrypted = cryptor.unseal(testVersionId, sealed)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt then decrypt recovers empty payload`() {
        val cryptor = TaskChampionCryptor(testClientId, testSecret)
        val plaintext = ByteArray(0)

        val sealed = cryptor.seal(testVersionId, plaintext)
        val decrypted = cryptor.unseal(testVersionId, sealed)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt then decrypt recovers large payload`() {
        val cryptor = TaskChampionCryptor(testClientId, testSecret)
        val plaintext = ByteArray(100_000) { (it % 256).toByte() }

        val sealed = cryptor.seal(testVersionId, plaintext)
        val decrypted = cryptor.unseal(testVersionId, sealed)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `envelope format is correct`() {
        val cryptor = TaskChampionCryptor(testClientId, testSecret)
        val plaintext = "test".toByteArray()

        val sealed = cryptor.seal(testVersionId, plaintext)

        // Envelope: [1 byte version] [12 bytes nonce] [ciphertext + 16 byte auth tag]
        assertTrue("Envelope too small", sealed.size > 1 + 12 + 16)
        assertEquals("Version byte should be 0x01", 0x01.toByte(), sealed[0])
    }

    @Test
    fun `different encryptions produce different nonces`() {
        val cryptor = TaskChampionCryptor(testClientId, testSecret)
        val plaintext = "same plaintext".toByteArray()

        val sealed1 = cryptor.seal(testVersionId, plaintext)
        val sealed2 = cryptor.seal(testVersionId, plaintext)

        // Nonces are bytes 1..12 — should differ between encryptions
        val nonce1 = sealed1.copyOfRange(1, 13)
        val nonce2 = sealed2.copyOfRange(1, 13)
        assertFalse("Nonces should be unique per encryption", nonce1.contentEquals(nonce2))

        // But both should decrypt to the same plaintext
        assertArrayEquals(plaintext, cryptor.unseal(testVersionId, sealed1))
        assertArrayEquals(plaintext, cryptor.unseal(testVersionId, sealed2))
    }

    @Test
    fun `wrong secret fails to decrypt`() {
        val cryptor1 = TaskChampionCryptor(testClientId, testSecret)
        val cryptor2 = TaskChampionCryptor(testClientId, "wrong-secret")
        val plaintext = "sensitive data".toByteArray()

        val sealed = cryptor1.seal(testVersionId, plaintext)

        try {
            cryptor2.unseal(testVersionId, sealed)
            fail("Should have thrown SyncException for wrong secret")
        } catch (e: SyncException) {
            // Expected: authentication tag verification fails
        }
    }

    @Test
    fun `wrong version ID in AAD fails to decrypt`() {
        val cryptor = TaskChampionCryptor(testClientId, testSecret)
        val plaintext = "test data".toByteArray()
        val wrongVersionId = "11111111-2222-3333-4444-555555555555"

        val sealed = cryptor.seal(testVersionId, plaintext)

        try {
            cryptor.unseal(wrongVersionId, sealed)
            fail("Should have thrown SyncException for wrong version ID")
        } catch (e: SyncException) {
            // Expected: AAD mismatch causes authentication failure
        }
    }

    @Test
    fun `wrong client ID fails to decrypt`() {
        val cryptor1 = TaskChampionCryptor(testClientId, testSecret)
        val cryptor2 = TaskChampionCryptor("99999999-8888-7777-6666-555555555555", testSecret)
        val plaintext = "test data".toByteArray()

        val sealed = cryptor1.seal(testVersionId, plaintext)

        try {
            cryptor2.unseal(testVersionId, sealed)
            fail("Should have thrown SyncException for wrong client ID")
        } catch (e: SyncException) {
            // Expected: different key derived from different client ID (salt)
        }
    }

    @Test
    fun `null secret treated as empty string`() {
        val cryptor1 = TaskChampionCryptor(testClientId, null)
        val cryptor2 = TaskChampionCryptor(testClientId, null)
        val plaintext = "test".toByteArray()

        val sealed = cryptor1.seal(testVersionId, plaintext)
        val decrypted = cryptor2.unseal(testVersionId, sealed)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = SyncException::class)
    fun `unseal rejects invalid envelope version`() {
        val cryptor = TaskChampionCryptor(testClientId, testSecret)
        val badEnvelope = ByteArray(30)
        badEnvelope[0] = 0x02 // Wrong version byte

        cryptor.unseal(testVersionId, badEnvelope)
    }

    @Test(expected = SyncException::class)
    fun `unseal rejects too-small envelope`() {
        val cryptor = TaskChampionCryptor(testClientId, testSecret)
        val tooSmall = byteArrayOf(0x01, 0x00, 0x00) // Only 3 bytes

        cryptor.unseal(testVersionId, tooSmall)
    }

    @Test(expected = SyncException::class)
    fun `invalid UUID in client ID throws SyncException`() {
        TaskChampionCryptor("not-a-uuid", testSecret)
    }

    @Test
    fun `encrypt with nil UUID version ID works`() {
        val cryptor = TaskChampionCryptor(testClientId, testSecret)
        val nilVersion = "00000000-0000-0000-0000-000000000000"
        val plaintext = "first sync".toByteArray()

        val sealed = cryptor.seal(nilVersion, plaintext)
        val decrypted = cryptor.unseal(nilVersion, sealed)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `JSON operations round trip through encryption`() {
        val cryptor = TaskChampionCryptor(testClientId, testSecret)
        val json = """[{"Create":{"uuid":"56e0be07-c61f-494c-a54c-bdcfdd52d2a7"}}]"""
        val plaintext = json.toByteArray(Charsets.UTF_8)

        val sealed = cryptor.seal(testVersionId, plaintext)
        val decrypted = cryptor.unseal(testVersionId, sealed)

        assertEquals(json, decrypted.toString(Charsets.UTF_8))
    }
}
