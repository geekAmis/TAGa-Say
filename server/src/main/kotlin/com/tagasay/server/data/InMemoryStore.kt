package com.tagasay.server.data

import com.tagasay.server.model.Device
import com.tagasay.server.model.Envelope
import com.tagasay.server.model.UserProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory хранилище для alpha-сервера. Не хранит открытый текст.
 * Заменяется на PostgreSQL + Redis на следующем этапе (см. ROADMAP).
 */
class InMemoryStore(private val persistPath: String? = null) {

    private val random = SecureRandom()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val challenges = ConcurrentHashMap<String, Long>()
    private val devices = ConcurrentHashMap<String, Device>()
    private val tokens = ConcurrentHashMap<String, String>() // bearer -> deviceId
    private val deliveryTokens = ConcurrentHashMap<String, String>() // delivery token -> deviceId
    private val mailboxes = ConcurrentHashMap<String, MutableList<Envelope>>()
    private val users = ConcurrentHashMap<String, UserProfile>()
    private val phoneHashes = ConcurrentHashMap<String, String>() // hash -> username

    init {
        load()
    }

    fun newChallenge(deviceKey: String): String = randomToken().also {
        challenges["$deviceKey:$it"] = System.currentTimeMillis() + CHALLENGE_TTL_MS
    }

    fun consumeChallenge(deviceKey: String, challenge: String): Boolean {
        val expires = challenges.remove("$deviceKey:$challenge") ?: return false
        return expires > System.currentTimeMillis()
    }

    suspend fun register(device: Device, profile: UserProfile, phoneHash: String?): Pair<String, String> =
        mutex.withLock {
            // Перерегистрация username должна заменять старое устройство, иначе
            // envelope уйдёт в устаревший mailbox.
            devices.values
                .filter { it.username.equals(device.username, ignoreCase = true) }
                .forEach { old ->
                    devices.remove(old.deviceId)
                    tokens.entries.removeIf { it.value == old.deviceId }
                    deliveryTokens.entries.removeIf { it.value == old.deviceId }
                }
            val access = randomToken()
            val delivery = randomToken()
            devices[device.deviceId] = device
            users[device.username] = profile
            tokens[access] = device.deviceId
            deliveryTokens[delivery] = device.deviceId
            if (phoneHash != null) phoneHashes[phoneHash] = device.username
            persist()
            access to delivery
        }

    fun deviceByToken(token: String): Device? =
        tokens[token]?.let { devices[it] }

    fun usernameByDeliveryToken(token: String): String? =
        deliveryTokens[token]?.let { devices[it]?.username }

    fun deliveryTokenFor(deviceId: String): String? =
        deliveryTokens.entries.firstOrNull { it.value == deviceId }?.key

    fun user(username: String): UserProfile? = users[username.lowercase()]

    fun deviceByUsername(username: String): Device? =
        devices.values.firstOrNull { it.username.equals(username, ignoreCase = true) }

    fun updateDevice(device: Device) {
        devices[device.deviceId] = device
    }

    fun usersByPhoneHashes(hashes: List<String>): List<String> =
        hashes.mapNotNull { phoneHashes[it] }

    fun putEnvelope(token: String, envelope: Envelope) {
        mailboxes.computeIfAbsent(token) { mutableListOf() }.add(envelope)
        persist()
    }

    fun envelopes(token: String, limit: Int): List<Envelope> {
        val queue = mailboxes[token] ?: return emptyList()
        val now = System.currentTimeMillis()
        queue.removeAll { it.expiresAt in 1..now }
        return queue.take(limit)
    }

    fun ack(token: String, ids: Set<String>) {
        mailboxes[token]?.removeAll { it.id in ids }
        persist()
    }

    fun deleteDeliveryToken(token: String): String? = deliveryTokens.remove(token)

    fun rotateDeliveryToken(deviceId: String): String? {
        val old = deliveryTokens.entries.firstOrNull { it.value == deviceId }?.key ?: return null
        deliveryTokens.remove(old)
        val next = randomToken()
        deliveryTokens[next] = deviceId
        persist()
        return next
    }

    // ------------------------------------------------------------ persistence

    private fun load() {
        val path = persistPath ?: return
        val file = File(path)
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString(Snapshot.serializer(), file.readText())
            snapshot.devices.forEach { devices[it.deviceId] = it }
            snapshot.users.forEach { users[it.username] = it }
            snapshot.tokens.forEach { (token, device) -> tokens[token] = device }
            snapshot.deliveryTokens.forEach { (token, device) -> deliveryTokens[token] = device }
            snapshot.mailboxes.forEach { (token, list) -> mailboxes[token] = list.toMutableList() }
        }
    }

    private fun persist() {
        val path = persistPath ?: return
        synchronized(this) {
            val file = File(path)
            file.parentFile?.mkdirs()
            val snapshot = Snapshot(
                devices = devices.values.toList(),
                users = users.values.toList(),
                tokens = tokens.toMap(),
                deliveryTokens = deliveryTokens.toMap(),
                mailboxes = mailboxes.mapValues { it.value.toList() },
            )
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    file.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
        }
    }

    @Serializable
    private data class Snapshot(
        val devices: List<Device> = emptyList(),
        val users: List<UserProfile> = emptyList(),
        val tokens: Map<String, String> = emptyMap(),
        val deliveryTokens: Map<String, String> = emptyMap(),
        val mailboxes: Map<String, List<Envelope>> = emptyMap(),
    )

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val CHALLENGE_TTL_MS = 5 * 60 * 1000L
    }
}
