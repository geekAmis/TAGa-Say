package com.tagasay.server.data

import com.tagasay.server.model.Device
import com.tagasay.server.model.Envelope
import com.tagasay.server.model.UserProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory хранилище для alpha-сервера. Не хранит открытый текст.
 * Заменяется на PostgreSQL + Redis на следующем этапе (см. ROADMAP).
 */
class InMemoryStore {

    private val random = SecureRandom()
    private val mutex = Mutex()

    private val challenges = ConcurrentHashMap<String, Long>()
    private val devices = ConcurrentHashMap<String, Device>()
    private val tokens = ConcurrentHashMap<String, String>() // bearer -> deviceId
    private val deliveryTokens = ConcurrentHashMap<String, String>() // delivery token -> deviceId
    private val mailboxes = ConcurrentHashMap<String, MutableList<Envelope>>()
    private val users = ConcurrentHashMap<String, UserProfile>()
    private val phoneHashes = ConcurrentHashMap<String, String>() // hash -> username

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
    }

    fun envelopes(token: String, limit: Int): List<Envelope> {
        val queue = mailboxes[token] ?: return emptyList()
        val now = System.currentTimeMillis()
        queue.removeAll { it.expiresAt in 1..now }
        return queue.take(limit)
    }

    fun ack(token: String, ids: Set<String>) {
        mailboxes[token]?.removeAll { it.id in ids }
    }

    fun deleteDeliveryToken(token: String): String? = deliveryTokens.remove(token)

    fun rotateDeliveryToken(deviceId: String): String? {
        val old = deliveryTokens.entries.firstOrNull { it.value == deviceId }?.key ?: return null
        deliveryTokens.remove(old)
        val next = randomToken()
        deliveryTokens[next] = deviceId
        return next
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val CHALLENGE_TTL_MS = 5 * 60 * 1000L
    }
}
