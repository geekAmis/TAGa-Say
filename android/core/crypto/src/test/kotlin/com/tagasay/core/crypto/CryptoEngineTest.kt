package com.tagasay.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoEngineTest {

    private val crypto = CryptoEngine()

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = (0x00..0x0c).map { it.toByte() }.toByteArray()
        val info = (0xf0..0xf9).map { it.toByte() }.toByteArray()
        val okm = crypto.hkdfSha256(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun `master key derivation is deterministic and derives distinct keys`() {
        val secret = ByteArray(32) { 7 }
        val salt = ByteArray(32) { 3 }
        val a = crypto.deriveMasterKey(secret, salt)
        val b = crypto.deriveMasterKey(secret, salt)
        val c = crypto.deriveMasterKey(secret, ByteArray(32) { 4 })
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertEquals(32, a.size)
    }

    @Test
    fun `aes gcm roundtrip and tamper detection`() {
        val key = crypto.randomBytes(32)
        val plaintext = "Привет, TAGa-Say!".toByteArray()
        val aad = "chat:42".toByteArray()
        val sealed = crypto.aesGcmEncrypt(key, plaintext, aad)
        assertArrayEquals(plaintext, crypto.aesGcmDecrypt(key, sealed, aad))
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        var failed = false
        try {
            crypto.aesGcmDecrypt(key, sealed, aad)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `sha256 fingerprint is stable`() {
        val key = ByteArray(32) { 1 }
        assertTrue(crypto.fingerprint(key).isNotBlank())
        assertEquals(crypto.sha256Hex(key), crypto.sha256(key).toHex())
    }
}
