package com.tagasay.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UsePqRatchet
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * Проверяет, что связка libsignal (X3DH + Double Ratchet + PQXDH) корректно
 * устанавливает сессию и шифрует сообщения на реальном Android-устройстве.
 */
@RunWith(AndroidJUnit4::class)
class SignalProtocolTest {

    @Test
    fun x3dhPqxdhSessionRoundTrip() {
        val aliceIdentity = IdentityKeyPair.generate()
        val bobIdentity = IdentityKeyPair.generate()
        val alice = InMemorySignalProtocolStore(aliceIdentity, 1001)
        val bob = InMemorySignalProtocolStore(bobIdentity, 1002)
        val aliceAddress = SignalProtocolAddress("alice", 1)
        val bobAddress = SignalProtocolAddress("bob", 1)

        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKey = SignedPreKeyRecord(
            1,
            System.currentTimeMillis(),
            signedPreKeyPair,
            bobIdentity.privateKey.calculateSignature(signedPreKeyPair.publicKey.serialize()),
        )
        bob.storeSignedPreKey(1, signedPreKey)

        val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyber = KyberPreKeyRecord(
            1,
            System.currentTimeMillis(),
            kyberPair,
            bobIdentity.privateKey.calculateSignature(kyberPair.publicKey.serialize()),
        )
        bob.storeKyberPreKey(1, kyber)

        val bundle = PreKeyBundle(
            1002,
            1,
            PreKeyBundle.NULL_PRE_KEY_ID,
            null,
            1,
            signedPreKeyPair.publicKey,
            signedPreKey.signature,
            IdentityKey(bobIdentity.publicKey.serialize()),
            1,
            kyberPair.publicKey,
            kyber.signature,
        )
        SessionBuilder(alice, bobAddress).process(bundle, UsePqRatchet.YES)

        val plaintext = "Привет из instrumented-теста".toByteArray()
        val first = SessionCipher(alice, bobAddress).encrypt(plaintext)
        val decrypted = SessionCipher(bob, aliceAddress)
            .decrypt(PreKeySignalMessage(first.serialize()), UsePqRatchet.YES)
        assertArrayEquals(plaintext, decrypted)

        val reply = "Ответ по Double Ratchet".toByteArray()
        val second = SessionCipher(bob, aliceAddress).encrypt(reply)
        val replyPlain = SessionCipher(alice, bobAddress).decrypt(SignalMessage(second.serialize()))
        assertArrayEquals(reply, replyPlain)
    }
}
