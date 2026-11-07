package com.tagasay.app.data.crypto

import android.content.Context
import android.util.Base64
import com.tagasay.app.data.local.DeviceKeyStore
import com.tagasay.core.crypto.CryptoEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * Хранилище libsignal, зашифрованное мастер-ключом устройства (AES-256-GCM).
 *
 * Сессии/identity/prekeys сериализуются в один blob. Это позволяет пережить
 * перезапуск приложения и сохранить ratchet-состояние.
 */
class SignalStore(
    context: Context,
    private val keyStore: DeviceKeyStore,
    private val crypto: CryptoEngine = CryptoEngine(),
) : SignalProtocolStore, SenderKeyStore {

    private val file = File(context.filesDir, "signal_store.bin")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val random = SecureRandom()

    private var identityKeyPair: IdentityKeyPair
    private var registrationId: Int
    private val preKeys = mutableMapOf<Int, PreKeyRecord>()
    private val signedPreKeys = mutableMapOf<Int, SignedPreKeyRecord>()
    private val kyberPreKeys = mutableMapOf<Int, KyberPreKeyRecord>()
    private val sessions = mutableMapOf<String, SessionRecord>()
    private val identities = mutableMapOf<String, IdentityKey>()

    init {
        val snapshot = loadSnapshot()
        if (snapshot != null) {
            identityKeyPair = IdentityKeyPair(Base64.decode(snapshot.identity, Base64.NO_WRAP))
            registrationId = snapshot.registrationId
            snapshot.preKeys.forEach { (id, data) ->
                runCatching { PreKeyRecord(Base64.decode(data, Base64.NO_WRAP)) }
                    .onSuccess { preKeys[id] = it }
            }
            snapshot.signedPreKeys.forEach { (id, data) ->
                runCatching { SignedPreKeyRecord(Base64.decode(data, Base64.NO_WRAP)) }
                    .onSuccess { signedPreKeys[id] = it }
            }
            snapshot.kyberPreKeys.forEach { (id, data) ->
                runCatching { KyberPreKeyRecord(Base64.decode(data, Base64.NO_WRAP)) }
                    .onSuccess { kyberPreKeys[id] = it }
            }
            snapshot.sessions.forEach { (key, data) ->
                runCatching { SessionRecord(Base64.decode(data, Base64.NO_WRAP)) }
                    .onSuccess { sessions[key] = it }
            }
            snapshot.identities.forEach { (key, data) ->
                runCatching { IdentityKey(Base64.decode(data, Base64.NO_WRAP)) }
                    .onSuccess { identities[key] = it }
            }
        } else {
            identityKeyPair = IdentityKeyPair.generate()
            registrationId = 1 + random.nextInt(16_000)
            ensureSignedPreKey()
            ensureKyberPreKey()
            persist()
        }
        if (signedPreKeys.isEmpty()) ensureSignedPreKey()
        if (kyberPreKeys.isEmpty()) ensureKyberPreKey()
    }

    // ------------------------------------------------------- IdentityKeyStore

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange = synchronized(lock) {
        val key = addressKey(address)
        val previous = identities[key]
        identities[key] = identityKey
        persist()
        if (previous != null && previous != identityKey) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = synchronized(lock) {
        // TOFU: доверяем первому ключу, при смене UI покажет предупреждение.
        val known = identities[addressKey(address)]
        known == null || known == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        synchronized(lock) { identities[addressKey(address)] }

    // ------------------------------------------------------------- PreKeyStore

    override fun loadPreKey(preKeyId: Int): PreKeyRecord = synchronized(lock) {
        preKeys[preKeyId] ?: throw InvalidKeyIdException("No such prekey $preKeyId")
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = synchronized(lock) {
        preKeys[preKeyId] = record
        persist()
    }

    override fun containsPreKey(preKeyId: Int): Boolean = synchronized(lock) { preKeys.containsKey(preKeyId) }

    override fun removePreKey(preKeyId: Int) = synchronized(lock) {
        preKeys.remove(preKeyId)
        persist()
    }

    // -------------------------------------------------------- SignedPreKeyStore

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = synchronized(lock) {
        signedPreKeys[signedPreKeyId]
            ?: throw InvalidKeyIdException("No such signed prekey $signedPreKeyId")
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = synchronized(lock) {
        signedPreKeys.values.toList()
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = synchronized(lock) {
        signedPreKeys[signedPreKeyId] = record
        persist()
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        synchronized(lock) { signedPreKeys.containsKey(signedPreKeyId) }

    override fun removeSignedPreKey(signedPreKeyId: Int) = synchronized(lock) {
        signedPreKeys.remove(signedPreKeyId)
        persist()
    }

    // ------------------------------------------------------------ SessionStore

    override fun loadSession(address: SignalProtocolAddress): SessionRecord = synchronized(lock) {
        sessions[addressKey(address)] ?: SessionRecord()
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        synchronized(lock) {
            val result = addresses.mapNotNull { sessions[addressKey(it)] }
            if (result.isEmpty()) throw org.signal.libsignal.protocol.NoSessionException("No sessions")
            result
        }

    override fun getSubDeviceSessions(name: String): List<Int> = synchronized(lock) {
        sessions.keys
            .filter { it.startsWith("$name:") }
            .mapNotNull { it.substringAfterLast(':').toIntOrNull() }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = synchronized(lock) {
        sessions[addressKey(address)] = record
        persist()
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        synchronized(lock) { sessions.containsKey(addressKey(address)) }

    override fun deleteSession(address: SignalProtocolAddress) = synchronized(lock) {
        sessions.remove(addressKey(address))
        persist()
    }

    override fun deleteAllSessions(name: String) = synchronized(lock) {
        sessions.keys.removeAll { it.startsWith("$name:") }
        persist()
    }

    // ----------------------------------------------------------- KyberPreKeyStore

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = synchronized(lock) {
        kyberPreKeys[kyberPreKeyId]
            ?: throw InvalidKeyIdException("No such kyber prekey $kyberPreKeyId")
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = synchronized(lock) {
        kyberPreKeys.values.toList()
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = synchronized(lock) {
        kyberPreKeys[kyberPreKeyId] = record
        persist()
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        synchronized(lock) { kyberPreKeys.containsKey(kyberPreKeyId) }

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) = synchronized(lock) {
        // Alpha: relay advertises один Kyber-prekey и не умеет принимать новый.
        // Не удаляем приватную часть, иначе второй отправитель не сможет
        // установить сессию. Ротация появится вместе с пулом prekey на сервере.
    }

    // ---------------------------------------------------------- SenderKeyStore

    override fun storeSenderKey(address: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        // Группы появятся на следующем этапе; интерфейс нужен libsignal.
    }

    override fun loadSenderKey(address: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord =
        throw UnsupportedOperationException("Group sender keys are not enabled in this build")

    // ---------------------------------------------------------------- helpers

    fun ensureSignedPreKey(id: Int = 1): SignedPreKeyRecord = synchronized(lock) {
        signedPreKeys[id] ?: run {
            val pair = ECKeyPair.generate()
            val signature = identityKeyPair.privateKey.calculateSignature(pair.publicKey.serialize())
            val record = SignedPreKeyRecord(id, System.currentTimeMillis(), pair, signature)
            signedPreKeys[id] = record
            persist()
            record
        }
    }

    fun identityPublicBase64(): String =
        Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP)

    fun signedPreKeyRecord(): SignedPreKeyRecord = ensureSignedPreKey()

    fun signedPreKeyPublicBase64(): String =
        Base64.encodeToString(signedPreKeyRecord().keyPair.publicKey.serialize(), Base64.NO_WRAP)

    fun signedPreKeySignatureBase64(): String =
        Base64.encodeToString(signedPreKeyRecord().signature, Base64.NO_WRAP)

    fun signedPreKeyId(): Int = signedPreKeyRecord().id

    fun ensureKyberPreKey(id: Int = 1): KyberPreKeyRecord = synchronized(lock) {
        kyberPreKeys[id] ?: run {
            val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signature = identityKeyPair.privateKey.calculateSignature(pair.publicKey.serialize())
            val record = KyberPreKeyRecord(id, System.currentTimeMillis(), pair, signature)
            kyberPreKeys[id] = record
            persist()
            record
        }
    }

    fun kyberPreKeyPublicBase64(): String =
        Base64.encodeToString(ensureKyberPreKey().keyPair.publicKey.serialize(), Base64.NO_WRAP)

    fun kyberPreKeySignatureBase64(): String =
        Base64.encodeToString(ensureKyberPreKey().signature, Base64.NO_WRAP)

    fun kyberPreKeyId(): Int = ensureKyberPreKey().id

    fun registrationIdValue(): Int = registrationId

    private fun addressKey(address: SignalProtocolAddress): String = "${address.name}:${address.deviceId}"

    private fun loadSnapshot(): Snapshot? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<Snapshot>(
                keyStore.decryptLocal(file.readText(), AAD).decodeToString(),
            )
        }.getOrNull()
    }

    private fun persist() {
        val snapshot = Snapshot(
            identity = Base64.encodeToString(identityKeyPair.serialize(), Base64.NO_WRAP),
            registrationId = registrationId,
            preKeys = preKeys.mapValues { Base64.encodeToString(it.value.serialize(), Base64.NO_WRAP) },
            signedPreKeys = signedPreKeys.mapValues { Base64.encodeToString(it.value.serialize(), Base64.NO_WRAP) },
            kyberPreKeys = kyberPreKeys.mapValues { Base64.encodeToString(it.value.serialize(), Base64.NO_WRAP) },
            sessions = sessions.mapValues { Base64.encodeToString(it.value.serialize(), Base64.NO_WRAP) },
            identities = identities.mapValues { Base64.encodeToString(it.value.serialize(), Base64.NO_WRAP) },
        )
        val encrypted = keyStore.encryptLocal(
            json.encodeToString(Snapshot.serializer(), snapshot).encodeToByteArray(),
            AAD,
        )
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(encrypted)
        if (!temp.renameTo(file)) {
            file.writeText(encrypted)
            temp.delete()
        }
    }

    @Serializable
    private data class Snapshot(
        val identity: String,
        val registrationId: Int,
        val preKeys: Map<Int, String> = emptyMap(),
        val signedPreKeys: Map<Int, String> = emptyMap(),
        val kyberPreKeys: Map<Int, String> = emptyMap(),
        val sessions: Map<String, String> = emptyMap(),
        val identities: Map<String, String> = emptyMap(),
    )

    companion object {
        private const val AAD = "taga/signal/v1"
    }
}
