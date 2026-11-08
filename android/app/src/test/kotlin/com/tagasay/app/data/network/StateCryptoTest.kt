package com.tagasay.app.data.network

import com.tagasay.core.crypto.CryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class StateCryptoTest {

    private val crypto = CryptoEngine()
    private val verifier = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun keyIsDeterministicForSameAccount() {
        val first = StateCrypto.key(crypto, verifier, "Alice")
        val second = StateCrypto.key(crypto, verifier, "alice")
        assertArrayEquals(first, second)
        assertTrue(first.size == 32)
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val plaintext = """{"profile":{"displayName":"Alice"}}""".encodeToByteArray()
        val payload = StateCrypto.encrypt(crypto, verifier, "alice", plaintext)
        val restored = StateCrypto.decrypt(crypto, verifier, "alice", payload)
        assertArrayEquals(plaintext, restored)
    }

    @Test
    fun otherAccountCannotDecrypt() {
        val plaintext = "secret".encodeToByteArray()
        val payload = StateCrypto.encrypt(crypto, verifier, "alice", plaintext)
        assertThrows(Exception::class.java) {
            StateCrypto.decrypt(crypto, verifier, "bob", payload)
        }
    }
}
