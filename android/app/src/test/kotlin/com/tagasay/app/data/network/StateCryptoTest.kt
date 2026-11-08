package com.tagasay.app.data.network

import com.tagasay.core.crypto.CryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class StateCryptoTest {

    private val crypto = CryptoEngine()
    private val verifier = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val master = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun keyIsDeterministicForSameAccount() {
        val first = StateCrypto.keyV2(crypto, master, "Alice")
        val second = StateCrypto.keyV2(crypto, master, "alice")
        assertArrayEquals(first, second)
        assertTrue(first.size == 32)
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val plaintext = """{"profile":{"displayName":"Alice"}}""".encodeToByteArray()
        val payload = StateCrypto.encryptV2(crypto, master, "alice", plaintext)
        val restored = StateCrypto.decryptV2(crypto, master, "alice", payload)
        assertArrayEquals(plaintext, restored)
    }

    @Test
    fun otherAccountCannotDecrypt() {
        val payload = StateCrypto.encryptV2(crypto, master, "alice", "secret".encodeToByteArray())
        assertThrows(Exception::class.java) {
            StateCrypto.decryptV2(crypto, master, "bob", payload)
        }
    }

    @Test
    fun stateHashIgnoresUpdatedAt() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val first = SyncedState(updatedAt = 1, settings = com.tagasay.app.data.model.AppSettings())
        val second = SyncedState(updatedAt = 999_999, settings = com.tagasay.app.data.model.AppSettings())
        assertTrue(StateHash.of(crypto, json, first) == StateHash.of(crypto, json, second))
    }

    @Test
    fun serverWithPasswordVerifierCannotDecryptV2() {
        val plaintext = """{"contacts":[{"username":"alice"}]}""".encodeToByteArray()
        val payload = StateCrypto.encryptV2(crypto, master, "alice", plaintext)
        // Сервер знает verifier, но не AccountMasterKey.
        assertThrows(Exception::class.java) {
            StateCrypto.decryptLegacy(crypto, verifier, "alice", payload)
        }
        val legacyKey = StateCrypto.legacyKey(crypto, verifier, "alice")
        val v2Key = StateCrypto.keyV2(crypto, master, "alice")
        assertFalse(legacyKey.contentEquals(v2Key))
    }
}
