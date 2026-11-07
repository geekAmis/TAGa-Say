package com.tagasay.app.data.network

import android.util.Base64
import com.tagasay.app.data.crypto.SignalStore
import com.tagasay.core.crypto.MessageFraming
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UsePqRatchet
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

/**
 * X3DH + Double Ratchet через libsignal.
 *
 * Первый конверт к получателю — PreKeySignalMessage, далее обычный SignalMessage.
 * Тип сообщения кодируется первым байтом ciphertext (2/3), чтобы relay не разбирал
 * криптографический конверт.
 *
 * Перед шифрованием открытый текст кадрируется ([MessageFraming]): добавляется
 * length-prefix и padding до класса размера, поэтому relay не видит точную длину.
 */
class CryptoMessenger(
    private val store: SignalStore,
    private val api: TagaApi,
) {

    private val identityCache = mutableMapOf<String, ByteArray>()

    fun identityPublicKey(): ByteArray = store.getIdentityKeyPair().publicKey.serialize()

    fun identityPrivateKey(): ECPrivateKey = store.getIdentityKeyPair().privateKey

    fun knownIdentity(username: String): ByteArray? =
        store.getIdentity(address(username))?.serialize()

    /** Identity-ключ получателя для sealed sender (кэшируется). */
    suspend fun recipientIdentity(username: String): ByteArray {
        val clean = username.trimStart('@').lowercase()
        identityCache[clean]?.let { return it }
        store.getIdentity(address(clean))?.let { known ->
            return known.serialize().also { identityCache[clean] = it }
        }
        val bundle = api.keyBundle(clean)
        val key = IdentityKey(Base64.decode(bundle.identityKey, Base64.NO_WRAP)).serialize()
        identityCache[clean] = key
        return key
    }

    suspend fun encryptFor(username: String, plaintext: ByteArray): ByteArray {
        val address = address(username)
        if (!store.containsSession(address)) {
            establishSession(username, address)
        }
        val ciphertext = SessionCipher(store, address).encrypt(MessageFraming.pad(plaintext))
        return byteArrayOf(ciphertext.type.toByte()) + ciphertext.serialize()
    }

    suspend fun decryptFrom(sender: String, payload: ByteArray): ByteArray {
        require(payload.size > 1) { "empty signal message" }
        val type = payload[0].toInt()
        val body = payload.copyOfRange(1, payload.size)
        val address = address(sender)
        val cipher = SessionCipher(store, address)
        val plaintext = when (type) {
            PRE_KEY_TYPE -> cipher.decrypt(PreKeySignalMessage(body), UsePqRatchet.YES)
            WHISPER_TYPE -> cipher.decrypt(SignalMessage(body))
            else -> error("unsupported signal message type $type")
        }
        // unpad терпим к сырому тексту старых клиентов: без magic возвращает как есть.
        return MessageFraming.unpad(plaintext)
    }

    private suspend fun establishSession(username: String, address: SignalProtocolAddress) {
        val bundle = api.keyBundle(username)
        val identity = IdentityKey(Base64.decode(bundle.identityKey, Base64.NO_WRAP))
        identityCache[username.trimStart('@').lowercase()] = identity.serialize()
        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            PreKeyBundle.NULL_PRE_KEY_ID,
            null,
            bundle.signedPrekeyId,
            ECPublicKey(Base64.decode(bundle.signedPrekey, Base64.NO_WRAP)),
            Base64.decode(bundle.signedPrekeySignature, Base64.NO_WRAP),
            identity,
            bundle.kyberPrekeyId,
            KEMPublicKey(Base64.decode(bundle.kyberPrekey, Base64.NO_WRAP)),
            Base64.decode(bundle.kyberPrekeySignature, Base64.NO_WRAP),
        )
        SessionBuilder(store, address).process(preKeyBundle, UsePqRatchet.YES)
    }

    private fun address(username: String) =
        SignalProtocolAddress(username.trimStart('@').lowercase(), 1)

    companion object {
        private const val WHISPER_TYPE = 2
        private const val PRE_KEY_TYPE = 3
    }
}
