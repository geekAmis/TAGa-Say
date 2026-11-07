package com.tagasay.server.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Файловое хранилище шифрованных вложений.
 *
 * Сервер видит только непрозрачный ciphertext: ключ AES-GCM передаётся
 * исключительно внутри E2EE-конверта. Идентификатор — случайная capability,
 * которую невозможно угадать, поэтому перебор не даёт доступа к файлам.
 *
 * Метаданные (размер, SHA-256 ciphertext, TTL) лежат в index.json без имён
 * файлов, отправителей и получателей — это минимизирует утечку метаданных.
 */
class MediaStore(
    private val rootDir: String,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()
    private val blobsDir = File(rootDir, "blobs")
    private val indexFile = File(rootDir, "index.json")
    private val items = ConcurrentHashMap<String, MediaMeta>()

    init {
        load()
    }

    fun put(ciphertext: ByteArray, sha256: String): MediaMeta {
        require(ciphertext.isNotEmpty()) { "empty media" }
        require(ciphertext.size.toLong() <= maxBytes) { "media too large" }
        val now = System.currentTimeMillis()
        val meta = MediaMeta(
            id = randomId(),
            sha256 = sha256,
            size = ciphertext.size.toLong(),
            createdAt = now,
            expiresAt = now + ttlMs,
        )
        blobsDir.mkdirs()
        val file = File(blobsDir, "${meta.id}.bin")
        file.writeBytes(ciphertext)
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                file.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
        }
        items[meta.id] = meta
        persist()
        return meta
    }

    fun get(id: String): Pair<MediaMeta, ByteArray>? {
        val meta = items[id] ?: return null
        if (meta.expiresAt in 1..System.currentTimeMillis()) {
            delete(id)
            return null
        }
        val file = File(blobsDir, "$id.bin")
        if (!file.exists()) {
            items.remove(id)
            persist()
            return null
        }
        return meta to file.readBytes()
    }

    fun delete(id: String): Boolean {
        if (items.remove(id) == null) return false
        File(blobsDir, "$id.bin").delete()
        persist()
        return true
    }

    // ------------------------------------------------------------ persistence

    private fun load() {
        if (!indexFile.exists()) return
        runCatching {
            val index = json.decodeFromString(MediaIndex.serializer(), indexFile.readText())
            index.items.forEach { items[it.id] = it }
            purgeExpired()
        }
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val expired = items.values.filter { it.expiresAt in 1..now }.map { it.id }
        expired.forEach { delete(it) }
    }

    private fun persist() {
        synchronized(this) {
            rootDirFile().mkdirs()
            val index = MediaIndex(items.values.toList())
            val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
            temp.writeText(json.encodeToString(MediaIndex.serializer(), index))
            if (!temp.renameTo(indexFile)) {
                indexFile.writeText(temp.readText())
                temp.delete()
            }
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    indexFile.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
        }
    }

    private fun rootDirFile(): File = File(rootDir)

    private fun randomId(): String {
        val bytes = ByteArray(ID_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    @Serializable
    data class MediaMeta(
        val id: String,
        val sha256: String,
        val size: Long,
        val createdAt: Long,
        val expiresAt: Long,
    )

    @Serializable
    private data class MediaIndex(val items: List<MediaMeta> = emptyList())

    companion object {
        const val DEFAULT_MAX_BYTES = 50L * 1024 * 1024
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val ID_BYTES = 24

        fun defaultRoot(): String {
            val explicit = System.getenv("TAGASAY_MEDIA")
            if (!explicit.isNullOrBlank()) return explicit
            val data = System.getenv("TAGASAY_DATA")
            if (!data.isNullOrBlank()) {
                val parent = File(data).parentFile ?: File(".")
                return File(parent, "media").path
            }
            return "data/media"
        }
    }
}
