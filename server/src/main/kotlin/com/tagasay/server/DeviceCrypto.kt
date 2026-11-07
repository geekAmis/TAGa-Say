package com.tagasay.server

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/**
 * Проверка подписей Ed25519. Сервер не хранит приватные ключи и не может
 * расшифровать содержимое конвертов.
 */
object DeviceCrypto {

    private val random = SecureRandom()

    fun verify(publicKeyBase64: String, message: ByteArray, signatureBase64: String): Boolean =
        runCatching {
            val publicKey = Base64.getDecoder().decode(publicKeyBase64)
            val signature = Base64.getDecoder().decode(signatureBase64)
            if (publicKey.size != 32 || signature.size != 64) return false
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        }.getOrDefault(false)

    fun randomToken(bytes: Int = 32): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    fun isValidUsername(username: String): Boolean =
        username.matches(Regex("[A-Za-z0-9_]{3,32}"))

    fun isValidBase64Key(value: String, expectedBytes: Int): Boolean =
        runCatching { Base64.getDecoder().decode(value).size == expectedBytes }.getOrDefault(false)

    /** libsignal ECPublicKey/IdentityKey: 32 или 33 байта (с type-байтом). */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    fun isValidSignalKey(value: String): Boolean =
        runCatching {
            val size = Base64.getDecoder().decode(value).size
            size == 32 || size == 33
        }.getOrDefault(false)
}
