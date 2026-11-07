package com.tagasay.app.data.network

import android.util.Base64
import com.tagasay.app.data.crypto.SignalStore
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UsePqRatchet
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
 */
class CryptoMessenger(
    private val store: SignalStore,
    private val api: TagaApi,
) {

    suspend fun encryptFor(username: String, plaintext: ByteArray): ByteArray {
        val address = address(username)
        if (!store.containsSession(address)) {
            establishSession(username, address)
        }
        val ciphertext = SessionCipher(store, address).encrypt(plaintext)
        return byteArrayOf(ciphertext.type.toByte()) + ciphertext.serialize()
    }

    suspend fun decryptFrom(sender: String, payload: ByteArray): ByteArray {
        require(payload.size > 1) { "empty signal message" }
        val type = payload[0].toInt()
        val body = payload.copyOfRange(1, payload.size)
        val address = address(sender)
        val cipher = SessionCipher(store, address)
        return when (type) {
            PRE_KEY_TYPE -> cipher.decrypt(PreKeySignalMessage(body), UsePqRatchet.YES)
            WHISPER_TYPE -> cipher.decrypt(SignalMessage(body))
            else -> error("unsupported signal message type $type")
        }
    }

    private suspend fun establishSession(username: String, address: SignalProtocolAddress) {
        val bundle = api.keyBundle(username)
        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            PreKeyBundle.NULL_PRE_KEY_ID,
            null,
            bundle.signedPrekeyId,
            ECPublicKey(Base64.decode(bundle.signedPrekey, Base64.NO_WRAP)),
            Base64.decode(bundle.signedPrekeySignature, Base64.NO_WRAP),
            IdentityKey(Base64.decode(bundle.identityKey, Base64.NO_WRAP)),
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
