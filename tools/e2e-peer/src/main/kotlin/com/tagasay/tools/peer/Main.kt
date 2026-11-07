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
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val b64 = Base64.getEncoder()
private val b64d = Base64.getDecoder()
private const val PEER_VERIFIER = "peer-verifier-0123456789abcdef0123456789abcdef"
private const val PEER_DEVICE_HASH = "peer-device-hash-0123456789abcdef0123456789abcdef"

@Serializable data class ChallengeRequest(val deviceKey: String)
@Serializable data class ChallengeResponse(val challenge: String, val expiresAt: Long = 0)
@Serializable data class RegisterRequest(
    val deviceId: String,
    val username: String,
    val displayName: String,
    val phoneHash: String? = null,
    val passwordVerifier: String = PEER_VERIFIER,
    val deviceHash: String = PEER_DEVICE_HASH,
    val totpCode: String = "",
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

@Serializable data class WireAttachment(
    val kind: String = "FILE",
    val name: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0,
    val sha256: String = "",
    val scanVerdict: String = "SAFE",
    val mediaId: String,
    val keyBase64: String,
)

@Serializable data class WireDto(
    val v: Int = 1,
    val type: String,
    val id: String = "",
    val timestamp: Long = 0,
    val text: String = "",
    val kind: String = "TEXT",
    val sticker: String? = null,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val forwardedFrom: String? = null,
    val attachment: WireAttachment? = null,
    val ids: List<String> = emptyList(),
)

@Serializable data class StoredPeerState(
    val identity: String,
    val registrationId: Int,
    val signedPreKey: String,
    val kyberPreKey: String,
    val signingPrivate: String,
    val signingPublic: String,
)

/** Совпадает с MessageFraming в android/core/crypto. */
object Framing {    private val MAGIC = byteArrayOf(0x54, 0x47, 0x46, 0x31) // "TGF1"
    private val CLASSES = intArrayOf(256, 1024, 4096, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024)

    fun pad(payload: ByteArray): ByteArray {
        val needed = 8 + payload.size
        val target = CLASSES.firstOrNull { it >= needed } ?: needed
        val out = ByteArray(target)
        MAGIC.copyInto(out, 0)
        out[4] = (payload.size ushr 24).toByte()
        out[5] = (payload.size ushr 16).toByte()
        out[6] = (payload.size ushr 8).toByte()
        out[7] = payload.size.toByte()
        payload.copyInto(out, 8)
        return out
    }

    fun unpad(framed: ByteArray): ByteArray {
        if (framed.size < 8) return framed
        for (i in MAGIC.indices) if (framed[i] != MAGIC[i]) return framed
        val length = ((framed[4].toInt() and 0xff) shl 24) or
            ((framed[5].toInt() and 0xff) shl 16) or
            ((framed[6].toInt() and 0xff) shl 8) or
            (framed[7].toInt() and 0xff)
        if (length < 0 || length > framed.size - 8) return framed
        return framed.copyOfRange(8, 8 + length)
    }
}

private val WIRE_PREFIX = "TAGA-WIRE\u0001".toByteArray()

private fun decodeWire(payload: ByteArray): WireDto? {
    if (payload.size <= WIRE_PREFIX.size) return null
    for (i in WIRE_PREFIX.indices) if (payload[i] != WIRE_PREFIX[i]) return null
    return runCatching {
        json.decodeFromString(
            WireDto.serializer(),
            payload.copyOfRange(WIRE_PREFIX.size, payload.size).decodeToString(),
        )
    }.getOrNull()
}

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

    private val stateFile = File(System.getenv("PEER_STATE") ?: "peer-state.json")
    private val savedState: StoredPeerState? = runCatching {
        if (stateFile.exists()) {
            json.decodeFromString(StoredPeerState.serializer(), stateFile.readText())
        } else {
            null
        }
    }.getOrNull()

    private val identity = savedState?.let {
        runCatching { IdentityKeyPair(b64d.decode(it.identity)) }.getOrNull()
    } ?: IdentityKeyPair.generate()
    private val registrationId = savedState?.registrationId ?: (1000 + SecureRandom().nextInt(10_000))
    private val signedPreKeyId = 1
    private val signedPreKey = savedState?.let {
        runCatching { SignedPreKeyRecord(b64d.decode(it.signedPreKey)) }.getOrNull()
    } ?: run {
        val pair = ECKeyPair.generate()
        SignedPreKeyRecord(
            signedPreKeyId,
            System.currentTimeMillis(),
            pair,
            identity.privateKey.calculateSignature(pair.publicKey.serialize()),
        )
    }
    private val kyberPreKey = savedState?.let {
        runCatching { KyberPreKeyRecord(b64d.decode(it.kyberPreKey)) }.getOrNull()
    } ?: run {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        KyberPreKeyRecord(
            1,
            System.currentTimeMillis(),
            pair,
            identity.privateKey.calculateSignature(pair.publicKey.serialize()),
        )
    }
    private val store = InMemorySignalProtocolStore(identity, registrationId).apply {
        storeSignedPreKey(signedPreKeyId, signedPreKey)
        storeKyberPreKey(1, kyberPreKey)
    }
    private val signing = savedState?.let {
        runCatching {
            Ed25519PrivateKeyParameters(b64d.decode(it.signingPrivate), 0) to
                b64d.decode(it.signingPublic)
        }.getOrNull()
    } ?: generateSigningKey()
    private var token: String? = null
    @Volatile private var stopped = false

    init {
        if (savedState == null) saveState()
    }

    private fun saveState() {
        val state = StoredPeerState(
            identity = b64.encodeToString(identity.serialize()),
            registrationId = registrationId,
            signedPreKey = b64.encodeToString(signedPreKey.serialize()),
            kyberPreKey = b64.encodeToString(kyberPreKey.serialize()),
            signingPrivate = b64.encodeToString(signing.first.encoded),
            signingPublic = b64.encodeToString(signing.second),
        )
        stateFile.writeText(json.encodeToString(StoredPeerState.serializer(), state))
    }

    fun run() {
        register()
        println("READY as @$username (peer device). Type text to send to @lilanga, Ctrl+C to exit.")
        Thread(::pollLoop, "peer-poll").apply { isDaemon = true }.start()
        Thread(::watchCommands, "peer-cmd").apply { isDaemon = true }.start()
        System.getenv("PEER_SEND")?.takeIf { it.isNotBlank() }?.let { text ->
            val target = System.getenv("PEER_TO")?.takeIf { it.isNotBlank() } ?: "lilanga"
            val id = send(target, text)
            val edit = System.getenv("PEER_EDIT")?.takeIf { it.isNotBlank() }
            val delete = System.getenv("PEER_DELETE") == "1"
            Thread {
                Thread.sleep(4_000)
                if (edit != null) {
                    sendWire(
                        target,
                        WireDto(type = "edit", id = id, timestamp = System.currentTimeMillis(), text = edit),
                    )
                    println("edited $id -> $edit")
                }
                if (delete) {
                    Thread.sleep(3_000)
                    sendWire(
                        target,
                        WireDto(type = "delete", id = id, timestamp = System.currentTimeMillis()),
                    )
                    println("deleted $id")
                }
            }.apply { isDaemon = true }.start()
        }
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

    /**
     * Позволяет управлять живым peer через файл: send:текст, edit:<id>|текст,
     * delete:<id>, read:<id1,id2>. Удобно для ручных E2E-проверок.
     */
    private fun watchCommands() {
        val file = File(System.getenv("PEER_CMD") ?: "peer-cmd.txt")
        val target = System.getenv("PEER_TO")?.takeIf { it.isNotBlank() } ?: "lilanga"
        var last = ""
        while (!stopped) {
            val command = runCatching { if (file.exists()) file.readText().trim() else "" }
                .getOrDefault("")
            if (command.isNotEmpty() && command != last) {
                last = command
                runCatching {
                    when {
                        command.startsWith("send:", true) ->
                            send(target, command.substringAfter(':'))
                        command.startsWith("edit:", true) -> {
                            val body = command.substringAfter(':')
                            val id = body.substringBefore('|')
                            val text = body.substringAfter('|', "edited")
                            sendWire(
                                target,
                                WireDto(type = "edit", id = id, timestamp = System.currentTimeMillis(), text = text),
                            )
                            println("cmd edit $id -> $text")
                        }
                        command.startsWith("delete:", true) -> {
                            val id = command.substringAfter(':')
                            sendWire(
                                target,
                                WireDto(type = "delete", id = id, timestamp = System.currentTimeMillis()),
                            )
                            println("cmd delete $id")
                        }
                        command.startsWith("read:", true) -> {
                            val ids = command.substringAfter(':').split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            sendWire(
                                target,
                                WireDto(type = "read", id = UUID.randomUUID().toString(), ids = ids),
                            )
                            println("cmd read $ids")
                        }
                    }
                }.onFailure { println("cmd failed: ${it.message}") }
            }
            Thread.sleep(700)
        }
    }

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
                        signedPrekey = b64.encodeToString(signedPreKey.keyPair.publicKey.serialize()),
                        signedPrekeySignature = b64.encodeToString(signedPreKey.signature),
                        signedPrekeyId = signedPreKeyId,
                        registrationId = registrationId,
                        kyberPrekey = b64.encodeToString(kyberPreKey.keyPair.publicKey.serialize()),
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
                            val plain = Framing.unpad(
                                when (type) {
                                    3 -> cipher.decrypt(PreKeySignalMessage(body), UsePqRatchet.YES)
                                    else -> cipher.decrypt(SignalMessage(body))
                                },
                            )
                            val wire = decodeWire(plain)
                            if (wire != null) {
                                when (wire.type) {
                                    "text", "media" -> {
                                        println("[${envelope.sender}] ${wire.text}")
                                        sendWire(
                                            envelope.sender,
                                            WireDto(
                                                type = "delivered",
                                                id = UUID.randomUUID().toString(),
                                                ids = listOf(wire.id),
                                            ),
                                        )
                                        System.getenv("PEER_AUTOREPLY")?.takeIf { it.isNotBlank() }?.let {
                                            send(envelope.sender, "$it: ${wire.text}")
                                        }
                                    }
                                    "edit" -> println("[edit ${wire.id}] ${wire.text}")
                                    "delete" -> println("[delete ${wire.id}]")
                                    "delivered" -> println("[delivered] ${wire.ids}")
                                    "read" -> println("[read] ${wire.ids}")
                                    else -> println("[wire ${wire.type}]")
                                }
                            } else {
                                val text = plain.decodeToString()
                                println("[${envelope.sender}] $text")
                                System.getenv("PEER_AUTOREPLY")?.takeIf { it.isNotBlank() }?.let {
                                    send(envelope.sender, "$it: $text")
                                }
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

    private fun send(target: String, text: String): String {
        val id = UUID.randomUUID().toString()
        sendWire(
            target,
            WireDto(
                type = "text",
                id = id,
                timestamp = System.currentTimeMillis(),
                text = text,
            ),
        )
        println("sent to @$target: $text (id=$id)")
        return id
    }

    private fun sendWire(target: String, wire: WireDto) =
        sendPlain(
            target,
            Framing.pad(WIRE_PREFIX + json.encodeToString(WireDto.serializer(), wire).encodeToByteArray()),
            null,
        )

    private fun sendPlain(target: String, plaintext: ByteArray, successLog: String?) {
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
            val cipher = SessionCipher(store, address).encrypt(plaintext)
            val payload = byteArrayOf(cipher.type.toByte()) + cipher.serialize()
            val envelope = SendEnvelopeRequest(
                id = UUID.randomUUID().toString(),
                recipient = target.trimStart('@'),
                ciphertext = b64.encodeToString(payload),
            )
            post("/v1/envelope", json.encodeToString(envelope))
            if (successLog != null) println(successLog)
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
