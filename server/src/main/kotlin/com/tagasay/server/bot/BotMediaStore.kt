package com.tagasay.server.bot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Отдельное файловое хранилище ботов (BotMediaStore).
 *
 * - TTL 30 дней, лимит 50 МБ на файл, квоты на бота и на uploader/сутки;
 * - MIME определяется по magic bytes, а не по расширению/имени;
 * - имя файла никогда не используется как путь (случайное имя, безопасное
 *   расширение по MIME); запрещены `..`, абсолютные пути и symlink;
 * - HTML/SVG/XML не отдаются inline (`application/octet-stream` + attachment);
 * - ссылки подписаны HMAC и живут 1 час, без бот-токена в URL.
 */
class BotMediaStore(
    root: File?,
    private val secrets: BotSecrets,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val botQuotaBytes: Long = BOT_QUOTA_BYTES,
    private val dailyQuotaBytes: Long = DAILY_QUOTA_BYTES,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val log = LoggerFactory.getLogger(BotMediaStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()
    private val counter = AtomicLong()
    private val lock = Any()

    private val dir: File = (root?.let { File(it, "bot-files") }
        ?: Files.createTempDirectory("taga-bot-files").toFile())
    private val indexFile = File(dir, "index.json")
    private val files = HashMap<String, BotFile>()

    init {
        load()
    }

    fun maxFileBytes(): Long = maxFileBytes

    fun save(
        botId: String,
        uploader: String,
        kind: String,
        bytes: ByteArray,
        originalFileName: String,
        declaredMime: String = "",
    ): BotFile {
        if (!botId.matches(Regex("bot_\\d{1,18}"))) throw BotApiException(400, "invalid bot id")
        if (bytes.isEmpty()) throw BotApiException(400, "file is empty")
        if (bytes.size.toLong() > maxFileBytes) {
            throw BotApiException(413, "file is too large (max ${maxFileBytes / (1024 * 1024)} MB)")
        }
        val mime = detectMime(bytes)
        val normalizedKind = kind.lowercase()
        if (!allowedMime(normalizedKind, mime)) {
            throw BotApiException(415, "unsupported media type for $normalizedKind: $mime")
        }
        val timestamp = now()
        synchronized(lock) {
            cleanupExpired(timestamp)
            val botUsage = files.values.filter { it.botId == botId }.sumOf { it.size }
            if (botUsage + bytes.size > botQuotaBytes) {
                throw BotApiException(413, "bot storage quota exceeded")
            }
            val dayAgo = timestamp - DAY_MS
            val daily = files.values
                .filter { it.uploader.equals(uploader, true) && it.createdAt >= dayAgo }
                .sumOf { it.size }
            if (daily + bytes.size > dailyQuotaBytes) {
                throw BotApiException(413, "daily upload quota exceeded")
            }

            val fileId = "file_" + randomHex(16)
            val uniqueId = "fq_" + randomHex(8)
            val botDir = File(dir, botId)
            botDir.mkdirs()
            val target = File(botDir, UUID.randomUUID().toString() + extensionFor(mime))
            if (!isInside(target, dir)) throw BotApiException(400, "invalid storage path")
            target.writeBytes(bytes)
            setPermissions(target)

            val file = BotFile(
                fileId = fileId,
                fileUniqueId = uniqueId,
                botId = botId,
                uploader = uploader.lowercase(),
                kind = normalizedKind,
                fileName = sanitizeDisplayName(originalFileName, normalizedKind, mime),
                mimeType = mime,
                size = bytes.size.toLong(),
                sha256 = sha256(bytes),
                storedName = target.name,
                createdAt = timestamp,
                expiresAt = timestamp + TTL_MS,
            )
            files[fileId] = file
            saveIndex()
            return file
        }
    }

    fun get(botId: String, fileId: String): BotFile? = synchronized(lock) {
        val file = files[fileId] ?: return@synchronized null
        if (file.botId != botId) return@synchronized null
        if (file.expiresAt in 1..now()) {
            files.remove(fileId)
            deleteBytes(file)
            saveIndex()
            return@synchronized null
        }
        file
    }

    /** Только для доверенных серверных путей (подпись ссылки в inbox). */
    fun find(fileId: String): BotFile? = synchronized(lock) {
        val file = files[fileId] ?: return@synchronized null
        if (file.expiresAt in 1..now()) {
            files.remove(fileId)
            deleteBytes(file)
            saveIndex()
            return@synchronized null
        }
        file
    }

    fun open(botId: String, fileId: String): Pair<File, BotFile>? = synchronized(lock) {
        val file = get(botId, fileId) ?: return@synchronized null
        val path = bytesPath(file) ?: return@synchronized null
        path to file
    }

    /** Скачивание по подписанной ссылке (без бот-токена): проверка TTL и HMAC. */
    fun openSigned(fileId: String, expiresAt: Long, signature: String): Pair<File, BotFile>? = synchronized(lock) {
        if (expiresAt <= now()) return@synchronized null
        if (!secrets.verifyFileSignature(fileId, expiresAt, signature)) return@synchronized null
        val file = files[fileId] ?: return@synchronized null
        if (file.expiresAt in 1..now()) {
            files.remove(fileId)
            deleteBytes(file)
            saveIndex()
            return@synchronized null
        }
        val path = bytesPath(file) ?: return@synchronized null
        path to file
    }

    fun signedUrl(baseUrl: String, file: BotFile): String {
        val expires = now() + LINK_TTL_MS
        val signature = secrets.fileSignature(file.fileId, expires)
        val base = baseUrl.trimEnd('/')
        return "$base/bot/v1/file/${file.fileId}?expires=$expires&sig=$signature"
    }

    fun verifyExists(botId: String, fileId: String): BotFile? = get(botId, fileId)

    fun byteSize(botId: String, fileId: String): Long? = get(botId, fileId)?.size

    private fun bytesPath(file: BotFile): File? {
        val botDir = File(dir, file.botId)
        val candidate = if (file.storedName.isNotBlank()) {
            File(botDir, file.storedName)
        } else {
            // Совместимость с индексом без storedName.
            botDir.listFiles()?.firstOrNull { !it.isDirectory && it.length() == file.size } ?: return null
        }
        if (!candidate.exists() || candidate.isDirectory) return null
        if (Files.isSymbolicLink(candidate.toPath())) return null
        if (!isInside(candidate, dir)) return null
        if (candidate.length() != file.size) return null
        return candidate
    }

    private fun isInside(file: File, parent: File): Boolean {
        val parentPath = runCatching { parent.canonicalFile }.getOrElse { return false }
        val filePath = runCatching { file.canonicalFile }.getOrElse { return false }
        return filePath.path.startsWith(parentPath.path + File.separator)
    }

    private fun detectMime(bytes: ByteArray): String {
        fun startsWith(vararg values: Int): Boolean {
            if (bytes.size < values.size) return false
            return values.indices.all { (bytes[it].toInt() and 0xFF) == values[it] }
        }
        fun ascii(offset: Int, value: String): Boolean {
            if (bytes.size < offset + value.length) return false
            return value.indices.all { (bytes[offset + it].toInt() and 0xFF) == value[it].code }
        }
        return when {
            startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            ascii(0, "%PDF") -> "application/pdf"
            ascii(0, "OggS") -> "audio/ogg"
            startsWith(0x1A, 0x45, 0xDF, 0xA3) -> "video/webm"
            ascii(4, "ftypM4A") -> "audio/mp4"
            ascii(4, "ftyp") -> "video/mp4"
            ascii(0, "ID3") || startsWith(0xFF, 0xFB) || startsWith(0xFF, 0xF3) -> "audio/mpeg"
            ascii(0, "PK\u0003\u0004") -> "application/zip"
            looksLikeSvg(bytes) -> "image/svg+xml"
            looksLikeHtml(bytes) -> "text/html"
            else -> "application/octet-stream"
        }
    }

    private fun looksLikeSvg(bytes: ByteArray): Boolean =
        String(bytes.copyOfRange(0, minOf(bytes.size, 512)), Charsets.UTF_8)
            .lowercase()
            .let { it.contains("<svg") }

    private fun looksLikeHtml(bytes: ByteArray): Boolean =
        String(bytes.copyOfRange(0, minOf(bytes.size, 512)), Charsets.UTF_8)
            .trimStart()
            .lowercase()
            .let { it.startsWith("<!doctype html") || it.startsWith("<html") }

    private fun allowedMime(kind: String, mime: String): Boolean = when (kind) {
        "photo" -> mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        "voice" -> mime in setOf("audio/ogg", "audio/mpeg", "audio/mp4", "audio/wav")
        "audio" -> mime in setOf("audio/mpeg", "audio/ogg", "audio/mp4", "audio/wav")
        "video" -> mime in setOf("video/mp4", "video/webm")
        "document", "animation" -> true
        else -> false
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        "application/pdf" -> ".pdf"
        "audio/ogg" -> ".ogg"
        "audio/mpeg" -> ".mp3"
        "audio/mp4" -> ".m4a"
        "audio/wav" -> ".wav"
        "video/mp4" -> ".mp4"
        "video/webm" -> ".webm"
        "application/zip" -> ".zip"
        else -> ".bin"
    }

    private fun sanitizeDisplayName(original: String, kind: String, mime: String): String {
        val safe = original
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("\u0000", "")
            .trim()
            .take(128)
        if (safe.isNotEmpty() && !safe.contains("..") && safe != ".") return safe
        val base = when (kind) {
            "photo" -> "photo"
            "voice" -> "voice"
            "video" -> "video"
            "audio" -> "audio"
            else -> "document"
        }
        return base + extensionFor(mime)
    }

    /** Content-Type для отдачи: опасные типы никогда не inline. */
    fun safeContentType(mime: String): String = when (mime.lowercase()) {
        "text/html", "application/xhtml+xml", "image/svg+xml", "text/xml", "application/xml" ->
            "application/octet-stream"
        else -> mime
    }

    fun isInlineSafe(mime: String): Boolean = safeContentType(mime) == mime

    // ----------------------------------------------------------- persistence

    private fun load() {
        runCatching { dir.mkdirs() }
        if (!indexFile.exists()) return
        runCatching {
            val index = json.decodeFromString(BotFileIndex.serializer(), indexFile.readText())
            val timestamp = now()
            index.files.forEach { file ->
                if (file.expiresAt == 0L || file.expiresAt > timestamp) files[file.fileId] = file
            }
            cleanupExpired(timestamp)
        }.onFailure { log.warn("bot file index ignored: {}", it.javaClass.simpleName) }
    }

    private fun saveIndex() {
        runCatching {
            dir.mkdirs()
            val text = json.encodeToString(
                BotFileIndex.serializer(),
                BotFileIndex(files.values.sortedBy { it.createdAt }),
            )
            val temp = File(dir, "index.json.tmp")
            temp.writeText(text)
            setPermissions(temp)
            runCatching {
                Files.move(
                    temp.toPath(),
                    indexFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.onFailure {
                Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            setPermissions(indexFile)
        }.onFailure { log.warn("bot file index write failed: {}", it.javaClass.simpleName) }
    }

    private fun cleanupExpired(timestamp: Long) {
        val expired = files.values.filter { it.expiresAt in 1..timestamp }
        expired.forEach {
            files.remove(it.fileId)
            deleteBytes(it)
        }
        if (expired.isNotEmpty()) saveIndex()
    }

    private fun deleteBytes(file: BotFile) {
        val botDir = File(dir, file.botId)
        val candidate = if (file.storedName.isNotBlank()) {
            File(botDir, file.storedName)
        } else {
            botDir.listFiles()?.firstOrNull { !it.isDirectory && it.length() == file.size }
        }
        if (candidate != null && isInside(candidate, dir)) runCatching { candidate.delete() }
    }

    private fun setPermissions(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                PosixFilePermissions.fromString("rw-------"),
            )
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class BotFileIndex(val files: List<BotFile> = emptyList())

    companion object {
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        const val BOT_QUOTA_BYTES = 1024L * 1024 * 1024
        const val DAILY_QUOTA_BYTES = 100L * 1024 * 1024
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val LINK_TTL_MS = 60L * 60 * 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
