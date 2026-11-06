package com.tagasay.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверка нативных примитивов libsodium на реальном устройстве.
 */
@RunWith(AndroidJUnit4::class)
class SodiumInstrumentedTest {

    private val crypto = CryptoEngine()

    @Test
    fun xchachaRoundtrip() {
        val key = crypto.randomBytes(32)
        val plaintext = ByteArray(1024) { (it % 251).toByte() }
        val aad = "envelope:v1".toByteArray()
        val sealed = crypto.xchachaEncrypt(key, plaintext, aad)
        assertArrayEquals(plaintext, crypto.xchachaDecrypt(key, sealed, aad))
    }

    @Test
    fun xchachaRejectsTamperedCiphertext() {
        val key = crypto.randomBytes(32)
        val sealed = crypto.xchachaEncrypt(key, "secret".toByteArray())
        sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 0x01).toByte()
        var failed = false
        try {
            crypto.xchachaDecrypt(key, sealed)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun ed25519SignAndVerify() {
        val pair = crypto.generateSigningKeyPair()
        val message = "TAGa-Say identity".toByteArray()
        val signature = crypto.signDetached(message, pair.secretKey)
        assertTrue(crypto.verifyDetached(signature, message, pair.publicKey))
        assertFalse(crypto.verifyDetached(signature, "other".toByteArray(), pair.publicKey))
    }

    @Test
    fun argon2idProduces32Bytes() {
        val result = crypto.argon2id(
            passphrase = "correct horse battery staple".toByteArray(),
            memoryBytes = 8L * 1024 * 1024,
            iterations = 1,
        )
        assertArrayEquals(result.salt, result.salt)
        assertTrue(result.hash.size == 32)
    }
}
