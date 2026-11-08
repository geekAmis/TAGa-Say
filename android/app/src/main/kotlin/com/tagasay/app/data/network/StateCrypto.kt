package com.tagasay.app.data.network

import com.tagasay.core.crypto.CryptoEngine
import java.util.Base64

/**
 * Криптография состояния пользователя.
 *
 * v2: случайный AccountMasterKey живёт только на устройстве (обёрнут ключом
 * Android Keystore). Сервер не знает его и не может вывести из verifier пароля.
 * v1 (legacy) выводился из verifier и скомпрометирован: используется только
 * для однократной миграции, после чего состояние перезаписывается в v2.
 */
object StateCrypto {

    fun keyV2(crypto: CryptoEngine, masterKey: ByteArray, username: String): ByteArray {
        require(masterKey.size == 32) { "master key must be 32 bytes" }
        return crypto.hkdfSha256(
            ikm = masterKey,
            salt = username.lowercase().encodeToByteArray(),
            info = "taga/state/v2".encodeToByteArray(),
            length = 32,
        )
    }

    fun aadV2(username: String): ByteArray =
        "taga/state/v2|${username.lowercase()}".encodeToByteArray()

    fun encryptV2(
        crypto: CryptoEngine,
        masterKey: ByteArray,
        username: String,
        plaintext: ByteArray,
    ): ByteArray = crypto.aesGcmEncrypt(
        keyV2(crypto, masterKey, username),
        plaintext,
        aadV2(username),
    )

    fun decryptV2(
        crypto: CryptoEngine,
        masterKey: ByteArray,
        username: String,
        payload: ByteArray,
    ): ByteArray = crypto.aesGcmDecrypt(
        keyV2(crypto, masterKey, username),
        payload,
        aadV2(username),
    )

    // ------------------------------------------------------- legacy v1 (migration)

    fun legacyKey(crypto: CryptoEngine, verifierBase64: String, username: String): ByteArray {
        val verifier = Base64.getDecoder().decode(verifierBase64)
        require(verifier.isNotEmpty()) { "empty password verifier" }
        return crypto.hkdfSha256(
            ikm = verifier,
            salt = username.lowercase().encodeToByteArray(),
            info = "taga/state/v1".encodeToByteArray(),
            length = 32,
        )
    }

    fun legacyAad(username: String): ByteArray =
        "taga/state/v1|${username.lowercase()}".encodeToByteArray()

    fun decryptLegacy(
        crypto: CryptoEngine,
        verifierBase64: String,
        username: String,
        payload: ByteArray,
    ): ByteArray = crypto.aesGcmDecrypt(
        legacyKey(crypto, verifierBase64, username),
        payload,
        legacyAad(username),
    )
}
