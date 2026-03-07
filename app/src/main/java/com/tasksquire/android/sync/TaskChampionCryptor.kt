package com.tasksquire.android.sync

import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * TaskChampion sync protocol encryption:
 * - PBKDF2-HMAC-SHA256 (600000 iterations), salt = client UUID bytes
 * - ChaCha20-Poly1305
 * - AAD = [task app id=1][version UUID bytes]
 * - Envelope = [version=1][12-byte nonce][ciphertext+tag]
 */
class TaskChampionCryptor(
    clientId: String,
    secret: String?
) {
    companion object {
        private const val PBKDF2_ITERATIONS = 600_000
        private const val KEY_LEN_BITS = 256
        private const val ENVELOPE_VERSION: Byte = 1
        private const val NONCE_LEN = 12
        private const val AAD_APP_ID: Byte = 1
        private const val AUTH_TAG_BITS = 128
    }

    private val random = SecureRandom()
    private val key = deriveKey(clientId, secret.orEmpty())

    fun seal(versionId: String, payload: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val aad = makeAad(versionId)

        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), AUTH_TAG_BITS, nonce, aad))

        val out = ByteArray(cipher.getOutputSize(payload.size))
        var offset = cipher.processBytes(payload, 0, payload.size, out, 0)
        offset += cipher.doFinal(out, offset)
        val sealed = out.copyOf(offset)

        return ByteArray(1 + nonce.size + sealed.size).also { envelope ->
            envelope[0] = ENVELOPE_VERSION
            System.arraycopy(nonce, 0, envelope, 1, nonce.size)
            System.arraycopy(sealed, 0, envelope, 1 + nonce.size, sealed.size)
        }
    }

    fun unseal(versionId: String, envelope: ByteArray): ByteArray {
        if (envelope.size <= 1 + NONCE_LEN) {
            throw SyncException("Encrypted payload envelope is too small")
        }
        if (envelope[0] != ENVELOPE_VERSION) {
            throw SyncException("Unsupported encryption envelope version ${envelope[0]}")
        }

        val nonce = envelope.copyOfRange(1, 1 + NONCE_LEN)
        val sealed = envelope.copyOfRange(1 + NONCE_LEN, envelope.size)
        val aad = makeAad(versionId)

        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), AUTH_TAG_BITS, nonce, aad))
        return try {
            val out = ByteArray(cipher.getOutputSize(sealed.size))
            var offset = cipher.processBytes(sealed, 0, sealed.size, out, 0)
            offset += cipher.doFinal(out, offset)
            out.copyOf(offset)
        } catch (e: Exception) {
            throw SyncException("Failed to decrypt sync payload: ${e.message}", e)
        }
    }

    private fun deriveKey(clientId: String, secret: String): ByteArray {
        val salt = uuidBytes(clientId)
        val spec = PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun makeAad(versionId: String): ByteArray {
        val versionBytes = uuidBytes(versionId)
        val aad = ByteArray(1 + versionBytes.size)
        aad[0] = AAD_APP_ID
        System.arraycopy(versionBytes, 0, aad, 1, versionBytes.size)
        return aad
    }

    private fun uuidBytes(uuid: String): ByteArray {
        val parsed = try {
            UUID.fromString(uuid)
        } catch (e: Exception) {
            throw SyncException("Invalid UUID '$uuid' used in sync cryptor", e)
        }
        val msb = parsed.mostSignificantBits
        val lsb = parsed.leastSignificantBits
        return ByteArray(16).also { bytes ->
            for (i in 0 until 8) {
                bytes[i] = ((msb ushr ((7 - i) * 8)) and 0xFF).toByte()
            }
            for (i in 0 until 8) {
                bytes[8 + i] = ((lsb ushr ((7 - i) * 8)) and 0xFF).toByte()
            }
        }
    }
}
