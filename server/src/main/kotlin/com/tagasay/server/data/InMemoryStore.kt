package com.tagasay.server.data

import com.tagasay.server.model.Device
import com.tagasay.server.model.Envelope
import com.tagasay.server.model.Room
import com.tagasay.server.model.UserProfile
import com.tagasay.server.model.UserState
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
    private val states = ConcurrentHashMap<String, UserState>() // username -> encrypted state
    private val rooms = ConcurrentHashMap<String, Room>() // room id -> room

    init {
        load()
    }

    fun newChallenge(deviceKey: String): String {
        val now = System.currentTimeMillis()
        if (challenges.size >= MAX_CHALLENGES) {
            challenges.entries.removeIf { it.value <= now }
        }
        val challenge = randomToken()
        challenges["$deviceKey:$challenge"] = now + CHALLENGE_TTL_MS
        return challenge
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

    fun putEnvelope(
        token: String,
        envelope: Envelope,
        maxPerMailbox: Int = MAX_MAILBOX_ENVELOPES,
    ): Boolean {
        val now = System.currentTimeMillis()
        val queue = mailboxes.computeIfAbsent(token) { mutableListOf() }
        queue.removeAll { it.expiresAt in 1..now }
        if (queue.size >= maxPerMailbox) return false
        queue.add(envelope)
        persist()
        return true
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

    fun state(username: String): UserState? = states[username.lowercase()]

    suspend fun putState(username: String, ciphertext: String, updatedAt: Long): UserState =
        mutex.withLock {
            val key = username.lowercase()
            val next = UserState(
                version = (states[key]?.version ?: 0) + 1,
                ciphertext = ciphertext,
                updatedAt = if (updatedAt > 0) updatedAt else System.currentTimeMillis(),
            )
            states[key] = next
            persist()
            next
        }

    suspend fun updateUserProfile(username: String, displayName: String, bio: String): UserProfile? =
        mutex.withLock {
            val key = username.lowercase()
            val current = users[key] ?: return@withLock null
            val next = current.copy(
                displayName = displayName.take(64).ifBlank { current.displayName },
                bio = bio.take(300),
            )
            users[key] = next
            persist()
            next
        }

    // ---------------------------------------------------------------- rooms

    fun room(id: String): Room? = rooms[id]

    fun roomByUsername(username: String): Room? =
        rooms.values.firstOrNull { it.username.equals(username.trimStart('@'), true) }

    fun roomsFor(username: String): List<Room> {
        val clean = username.lowercase()
        return rooms.values
            .filter { clean == it.owner.lowercase() || it.members.any { m -> m.equals(clean, true) } }
            .sortedBy { it.createdAt }
    }

    fun searchRooms(username: String): List<Room> {
        val clean = username.lowercase()
        return rooms.values
            .filter { it.channel }
            .filter {
                it.username.contains(clean, true) || it.title.contains(clean, true)
            }
            .sortedBy { it.createdAt }
    }

    suspend fun createRoom(room: Room): Room? = mutex.withLock {
        if (roomByUsername(room.username) != null) return@withLock null
        val cleanOwner = room.owner.lowercase()
        val next = room.copy(
            id = room.id.ifBlank { "room_${System.currentTimeMillis()}_${(0..999).random()}" },
            username = room.username.trimStart('@').lowercase(),
            owner = cleanOwner,
            admins = room.admins.map { it.lowercase() }.distinct(),
            members = (room.members + cleanOwner).map { it.lowercase().trimStart('@') }.distinct(),
            createdAt = if (room.createdAt > 0) room.createdAt else System.currentTimeMillis(),
        )
        rooms[next.id] = next
        persist()
        next
    }

    suspend fun joinRoom(id: String, username: String): Room? = mutex.withLock {
        val room = rooms[id] ?: return@withLock null
        val clean = username.lowercase()
        val next = room.copy(members = (room.members + clean).distinct())
        rooms[id] = next
        persist()
        next
    }

    suspend fun leaveRoom(id: String, username: String): Room? = mutex.withLock {
        val room = rooms[id] ?: return@withLock null
        val clean = username.lowercase()
        val members = room.members.filterNot { it.equals(clean, true) }
        if (members.isEmpty()) {
            rooms.remove(id)
            persist()
            return@withLock null
        }
        val next = room.copy(
            members = members,
            owner = if (room.owner.equals(clean, true)) members.first() else room.owner,
            admins = room.admins.filterNot { it.equals(clean, true) },
        )
        rooms[id] = next
        persist()
        next
    }

    suspend fun changeRoomMember(
        id: String,
        actor: String,
        target: String,
        remove: Boolean,
    ): Room? = mutex.withLock {
        val room = rooms[id] ?: return@withLock null
        val cleanActor = actor.lowercase()
        val isAdmin = room.owner.equals(cleanActor, true) ||
            room.admins.any { it.equals(cleanActor, true) }
        if (!isAdmin) return@withLock null
        val cleanTarget = target.trimStart('@').lowercase()
        if (cleanTarget == room.owner.lowercase() && remove) return@withLock null
        val members = if (remove) {
            room.members.filterNot { it.equals(cleanTarget, true) }
        } else {
            (room.members + cleanTarget).distinct()
        }
        val next = room.copy(
            members = members,
            admins = if (remove) {
                room.admins.filterNot { it.equals(cleanTarget, true) }
            } else {
                room.admins
            },
        )
        rooms[id] = next
        persist()
        next
    }

    suspend fun updateRoom(
        id: String,
        actor: String,
        title: String?,
        description: String?,
    ): Room? = mutex.withLock {
        val room = rooms[id] ?: return@withLock null
        val cleanActor = actor.lowercase()
        val isAdmin = room.owner.equals(cleanActor, true) ||
            room.admins.any { it.equals(cleanActor, true) }
        if (!isAdmin) return@withLock null
        val next = room.copy(
            title = title?.take(80)?.ifBlank { room.title } ?: room.title,
            description = description?.take(300) ?: room.description,
        )
        rooms[id] = next
        persist()
        next
    }

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
            snapshot.states.forEach { (username, state) -> states[username] = state }
            snapshot.rooms.forEach { room -> rooms[room.id] = room }
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
                states = states.toMap(),
                rooms = rooms.values.toList(),
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
        val states: Map<String, UserState> = emptyMap(),
        val rooms: List<Room> = emptyList(),
    )

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val CHALLENGE_TTL_MS = 5 * 60 * 1000L
        private const val MAX_CHALLENGES = 50_000
        private const val MAX_MAILBOX_ENVELOPES = 1_000
    }
}
