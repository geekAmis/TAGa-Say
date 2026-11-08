package com.tagasay.app.data.network

import com.tagasay.core.crypto.CryptoEngine
import java.util.Base64

/**
 * Криптография состояния пользователя. Вынесена отдельно, чтобы её можно
 * было проверить unit-тестами без Android-контекста.
 */
object StateCrypto {

    fun key(crypto: CryptoEngine, verifierBase64: String, username: String): ByteArray {
        val verifier = Base64.getDecoder().decode(verifierBase64)
        require(verifier.isNotEmpty()) { "empty password verifier" }
        return crypto.hkdfSha256(
            ikm = verifier,
            salt = username.lowercase().encodeToByteArray(),
            info = "taga/state/v1".encodeToByteArray(),
            length = 32,
        )
    }

    fun aad(username: String): ByteArray =
        "taga/state/v1|${username.lowercase()}".encodeToByteArray()

    fun encrypt(
        crypto: CryptoEngine,
        verifierBase64: String,
        username: String,
        plaintext: ByteArray,
    ): ByteArray = crypto.aesGcmEncrypt(
        key(crypto, verifierBase64, username),
        plaintext,
        aad(username),
    )

    fun decrypt(
        crypto: CryptoEngine,
        verifierBase64: String,
        username: String,
        payload: ByteArray,
    ): ByteArray = crypto.aesGcmDecrypt(
        key(crypto, verifierBase64, username),
        payload,
        aad(username),
    )
}
