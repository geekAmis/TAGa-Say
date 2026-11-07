package com.tagasay.app.data.crypto

import android.util.Base64
import com.tagasay.core.crypto.CryptoEngine
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey

/**
 * Sealed sender: идентификатор отправителя шифруется на identity-ключ
 * получателя (ECDH + HKDF + AES-256-GCM). Relay видит только ciphertext
 * открытого конверта и не сохраняет, кто его отправил.
 *
 * Формат blob: ephemeralPublicKey (33 B) || AES-GCM(nonce || payload+tag).
 * payload: version(1) || nameLen(1) || username || identityKey(33) || padding.
 * AAD привязан к username получателя.
 */
object SealedSender {

    private const val VERSION = 1
    private const val INFO = "taga/sealed/v1"
    private const val IDENTITY_BYTES = 33
    private const val MAX_USERNAME = 32
    private const val PAYLOAD_SIZE = 96

    data class Data(val username: String, val identityKey: ByteArray)

    fun seal(
        recipientUsername: String,
        recipientIdentityKey: ByteArray,
        senderUsername: String,
        senderIdentityKey: ByteArray,
        crypto: CryptoEngine,
    ): String {
        val recipient = recipientUsername.trimStart('@').lowercase()
        val sender = senderUsername.trimStart('@').lowercase()
        require(sender.length in 3..MAX_USERNAME) { "invalid sender username" }
        require(senderIdentityKey.size == IDENTITY_BYTES) { "invalid sender identity key" }

        val recipientKey = ECPublicKey(normalize(recipientIdentityKey))
        val ephemeral = ECKeyPair.generate()
        val shared = ephemeral.privateKey.calculateAgreement(recipientKey)
        val key = crypto.hkdfSha256(shared, ByteArray(0), INFO.toByteArray(), 32)

        val nameBytes = sender.toByteArray()
        val payload = ByteArray(PAYLOAD_SIZE)
        payload[0] = VERSION.toByte()
        payload[1] = nameBytes.size.toByte()
        nameBytes.copyInto(payload, 2)
        senderIdentityKey.copyInto(payload, 2 + nameBytes.size)

        val aad = "$INFO|$recipient".toByteArray()
        val encrypted = crypto.aesGcmEncrypt(key, payload, aad)
        return Base64.encodeToString(ephemeral.publicKey.serialize() + encrypted, Base64.NO_WRAP)
    }

    fun unseal(
        recipientIdentityPrivateKey: ECPrivateKey,
        recipientUsername: String,
        sealedBase64: String,
        crypto: CryptoEngine,
    ): Data? = runCatching {
        val recipient = recipientUsername.trimStart('@').lowercase()
        val bytes = Base64.decode(sealedBase64, Base64.NO_WRAP)
        require(bytes.size > IDENTITY_BYTES)
        val ephemeral = ECPublicKey(bytes.copyOfRange(0, IDENTITY_BYTES))
        val shared = recipientIdentityPrivateKey.calculateAgreement(ephemeral)
        val key = crypto.hkdfSha256(shared, ByteArray(0), INFO.toByteArray(), 32)
        val aad = "$INFO|$recipient".toByteArray()
        val payload = crypto.aesGcmDecrypt(
            key,
            bytes.copyOfRange(IDENTITY_BYTES, bytes.size),
            aad,
        )
        require(payload.size >= 2 + IDENTITY_BYTES)
        require(payload[0].toInt() == VERSION)
        val nameLen = payload[1].toInt() and 0xff
        require(nameLen in 3..MAX_USERNAME)
        require(payload.size >= 2 + nameLen + IDENTITY_BYTES)
        val username = payload.copyOfRange(2, 2 + nameLen)
            .decodeToString()
            .trimStart('@')
            .lowercase()
        val identity = payload.copyOfRange(2 + nameLen, 2 + nameLen + IDENTITY_BYTES)
        Data(username, identity)
    }.getOrNull()

    /** bundle может содержать 32-байтный ключ без type-байта. */
    private fun normalize(key: ByteArray): ByteArray =
        if (key.size == IDENTITY_BYTES) key else byteArrayOf(0x05) + key
}
