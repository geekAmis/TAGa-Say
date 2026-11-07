package com.tagasay.tools.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UsePqRatchet
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val b64 = Base64.getEncoder()
private val b64d = Base64.getDecoder()

@Serializable data class ChallengeRequest(val deviceKey: String)
@Serializable data class ChallengeResponse(val challenge: String, val expiresAt: Long = 0)
@Serializable data class RegisterRequest(
    val deviceId: String,
    val username: String,
    val displayName: String,
    val phoneHash: String? = null,
    val identityKey: String,
    val signingKey: String,
    val signedPrekey: String,
    val signedPrekeySignature: String,
    val signedPrekeyId: Int,
    val registrationId: Int,
    val signalDeviceId: Int = 1,
    val kyberPrekey: String,
    val kyberPrekeySignature: String,
    val kyberPrekeyId: Int,
    val oneTimePrekeys: List<String> = emptyList(),
    val challenge: String,
    val challengeSignature: String,
    val deviceInfo: String = "e2e-peer",
)
@Serializable data class RegisterResponse(val accessToken: String, val deliveryToken: String)
@Serializable data class KeyBundleDto(
    val username: String,
    val registrationId: Int,
    val deviceId: Int,
    val identityKey: String,
    val signedPrekeyId: Int,
    val signedPrekey: String,
    val signedPrekeySignature: String,
    val kyberPrekeyId: Int,
    val kyberPrekey: String,
    val kyberPrekeySignature: String,
)
@Serializable data class SendEnvelopeRequest(
    val id: String,
    val recipient: String,
    val ciphertext: String,
    val sizeClass: Int = 0,
    val ttlSeconds: Long = 2_592_000,
    val ackToken: String = "",
)
@Serializable data class EnvelopeDto(
    val id: String,
    val sender: String,
    val ciphertext: String,
    val sizeClass: Int = 0,
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
)
@Serializable data class AckRequest(val ids: List<String>)

fun main(args: Array<String>) {
    val server = (args.getOrNull(0) ?: "http://127.0.0.1:8080").trimEnd('/')
    val username = (args.getOrNull(1) ?: "anna").lowercase()
    val display = args.getOrNull(2) ?: "Anna Peer"
    val peer = Peer(server, username, display)
    Runtime.getRuntime().addShutdownHook(Thread { peer.stop() })
    peer.run()
}

class Peer(private val server: String, private val username: String, private val display: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val identity = IdentityKeyPair.generate()
    private val registrationId = 1000 + SecureRandom().nextInt(10_000)
    private val signedPreKeyPair = ECKeyPair.generate()
    private val signedPreKeyId = 1
    private val signedPreKey = SignedPreKeyRecord(
        signedPreKeyId,
        System.currentTimeMillis(),
        signedPreKeyPair,
        identity.privateKey.calculateSignature(signedPreKeyPair.publicKey.serialize()),
    )
    private val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
    private val kyberPreKey = KyberPreKeyRecord(
        1,
        System.currentTimeMillis(),
        kyberKeyPair,
        identity.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize()),
    )
    private val store = InMemorySignalProtocolStore(identity, registrationId).apply {
        storeSignedPreKey(signedPreKeyId, signedPreKey)
        storeKyberPreKey(1, kyberPreKey)
    }
    private val signing = generateSigningKey()
    private var token: String? = null
    @Volatile private var stopped = false

    fun run() {
        register()
        println("READY as @$username (peer device). Type text to send to @lilanga, Ctrl+C to exit.")
        Thread(::pollLoop, "peer-poll").apply { isDaemon = true }.start()
        System.getenv("PEER_SEND")?.takeIf { it.isNotBlank() }?.let { send("lilanga", it) }
        if (System.getenv("PEER_HOLD") == "1") {
            while (!stopped) Thread.sleep(1_000)
            return
        }
        while (true) {
            val line = readLine() ?: break
            if (line.isBlank()) continue
            if (line == "/quit") break
            val target = if (line.startsWith("to ")) line.substringAfter(' ').substringBefore(' ') else "lilanga"
            val text = if (line.startsWith("to ")) line.substringAfter(' ').substringAfter(' ') else line
            send(target, text)
        }
        stop()
    }

    fun stop() { stopped = true }

    private fun register() {
        val challenge = call(
            "/v1/auth/challenge",
            json.encodeToString(ChallengeRequest(b64.encodeToString(signing.second))),
        ).let { json.decodeFromString(ChallengeResponse.serializer(), it) }
        val signer = Ed25519Signer().apply {
            init(true, signing.first)
            update(challenge.challenge.toByteArray(), 0, challenge.challenge.length)
        }
        val response = json.decodeFromString(
            RegisterResponse.serializer(),
            call(
                "/v1/auth/register",
                json.encodeToString(
                    RegisterRequest(
                        deviceId = UUID.randomUUID().toString(),
                        username = username,
                        displayName = display,
                        identityKey = b64.encodeToString(identity.publicKey.serialize()),
                        signingKey = b64.encodeToString(signing.second),
                        signedPrekey = b64.encodeToString(signedPreKeyPair.publicKey.serialize()),
                        signedPrekeySignature = b64.encodeToString(signedPreKey.signature),
                        signedPrekeyId = signedPreKeyId,
                        registrationId = registrationId,
                        kyberPrekey = b64.encodeToString(kyberKeyPair.publicKey.serialize()),
                        kyberPrekeySignature = b64.encodeToString(kyberPreKey.signature),
                        kyberPrekeyId = 1,
                        challenge = challenge.challenge,
                        challengeSignature = b64.encodeToString(signer.generateSignature()),
                    ),
                ),
            ),
        )
        token = response.accessToken
        println("registered @$username")
    }

    private fun pollLoop() {
        while (!stopped) {
            try {
                val raw = get("/v1/envelope?limit=100")
                val envelopes = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(EnvelopeDto.serializer()), raw)
                if (envelopes.isNotEmpty()) {
                    val ids = mutableListOf<String>()
                    for (envelope in envelopes) {
                        runCatching {
                            val payload = b64d.decode(envelope.ciphertext)
                            val type = payload[0].toInt()
                            val body = payload.copyOfRange(1, payload.size)
                            val address = SignalProtocolAddress(envelope.sender, 1)
                            val cipher = SessionCipher(store, address)
                            val plain = when (type) {
                                3 -> cipher.decrypt(PreKeySignalMessage(body), UsePqRatchet.YES)
                                else -> cipher.decrypt(SignalMessage(body))
                            }
                            val text = plain.decodeToString()
                            println("[${envelope.sender}] $text")
                            System.getenv("PEER_AUTOREPLY")?.takeIf { it.isNotBlank() }?.let {
                                send(envelope.sender, "$it: $text")
                            }
                        }.onFailure { println("decrypt failed: ${it.message}") }
                        ids += envelope.id
                    }
                    post("/v1/envelope/ack", json.encodeToString(AckRequest(ids)))
                }
            } catch (e: Exception) {
                println("poll error: ${e.message}")
            }
            Thread.sleep(1500)
        }
    }

    private fun send(target: String, text: String) {
        try {
            val address = SignalProtocolAddress(target.trimStart('@'), 1)
            if (!store.containsSession(address)) {
                val bundle = json.decodeFromString(
                    KeyBundleDto.serializer(),
                    get("/v1/keys/${target.trimStart('@')}"),
                )
                val preKey = PreKeyBundle(
                    bundle.registrationId,
                    bundle.deviceId,
                    PreKeyBundle.NULL_PRE_KEY_ID,
                    null,
                    bundle.signedPrekeyId,
                    ECPublicKey(b64d.decode(bundle.signedPrekey)),
                    b64d.decode(bundle.signedPrekeySignature),
                    IdentityKey(b64d.decode(bundle.identityKey)),
                    bundle.kyberPrekeyId,
                    KEMPublicKey(b64d.decode(bundle.kyberPrekey)),
                    b64d.decode(bundle.kyberPrekeySignature),
                )
                SessionBuilder(store, address).process(preKey, UsePqRatchet.YES)
            }
            val cipher = SessionCipher(store, address).encrypt(text.toByteArray())
            val payload = byteArrayOf(cipher.type.toByte()) + cipher.serialize()
            val envelope = SendEnvelopeRequest(
                id = UUID.randomUUID().toString(),
                recipient = target.trimStart('@'),
                ciphertext = b64.encodeToString(payload),
            )
            post("/v1/envelope", json.encodeToString(envelope))
            println("sent to @$target: $text")
        } catch (e: Exception) {
            println("send error: ${e.message}")
        }
    }

    private fun call(path: String, body: String): String = post(path, body)
    private fun post(path: String, body: String): String = request(path, body)
    private fun get(path: String): String = request(path, null)

    private fun request(path: String, body: String?): String {
        val builder = Request.Builder().url("$server$path")
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (body == null) builder.get() else builder.post(body.toRequestBody(JSON))
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("$path -> ${response.code}: $text")
            return text
        }
    }

    private fun generateSigningKey(): Pair<Ed25519PrivateKeyParameters, ByteArray> {
        val generator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }
        val pair = generator.generateKeyPair()
        val private = pair.private as Ed25519PrivateKeyParameters
        val public = pair.public as Ed25519PublicKeyParameters
        return private to public.encoded
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
