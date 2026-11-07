package com.tagasay.app.security

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tagasay.app.data.model.ScanReport
import com.tagasay.app.data.model.ScanVerdict
import com.tagasay.core.crypto.CryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Клиентский антивирус. Работает до шифрования/отправки файла и не требует
 * передачи содержимого на сервер.
 *
 * Уровни проверки:
 *  - сигнатура EICAR (тест антивируса);
 *  - реальный тип по magic bytes;
 *  - исполняемые форматы (PE/ELF/Mach-O) и APK;
 *  - несоответствие расширения и содержимого;
 *  - потенциально опасные макросы и двойные расширения;
 *  - архивные бомбы по размеру;
 *  - SHA-256 для репутационной проверки (в следующих версиях).
 */
class FileInspector(
    private val context: Context,
    private val crypto: CryptoEngine = CryptoEngine(),
) {

    suspend fun inspect(uri: Uri): ScanReport = withContext(Dispatchers.IO) {
        val meta = queryMeta(uri)
        val digest = MessageDigest.getInstance("SHA-256")
        val head = ByteArray(HEAD_LIMIT)
        var headSize = 0
        var total = 0L

        context.contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                val buffer = ByteArray(64 * 1024)
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                total += read
                if (headSize < HEAD_LIMIT) {
                    val take = minOf(read, HEAD_LIMIT - headSize)
                    buffer.copyInto(head, headSize, 0, take)
                    headSize += take
                }
            }
        } ?: return@withContext ScanReport(
            verdict = ScanVerdict.DANGEROUS,
            sha256 = "",
            sizeBytes = 0,
            mimeType = null,
            detectedType = "unknown",
            reasons = listOf("Не удалось прочитать файл"),
            fileName = meta.first,
        )

        val bytes = head.copyOf(headSize)
        val detected = detectType(bytes)
        val name = meta.first
        val declaredMime = meta.second
        val size = maxOf(total, meta.third)
        val extension = name.substringAfterLast('.', "").lowercase()

        val reasons = mutableListOf<String>()
        var verdict = ScanVerdict.SAFE

        fun warn(reason: String) {
            reasons += reason
            if (verdict == ScanVerdict.SAFE) verdict = ScanVerdict.WARNING
        }

        fun danger(reason: String) {
            reasons += reason
            verdict = ScanVerdict.DANGEROUS
        }

        if (bytes.containsAscii("EICAR-STANDARD-ANTIVIRUS-TEST-FILE")) {
            danger("Обнаружена сигнатура EICAR (тестовый вирус)")
        }

        when (detected) {
            "PE executable" -> danger("Исполняемый файл Windows (PE)")
            "ELF executable" -> danger("Исполняемый файл Linux (ELF)")
            "Mach-O executable" -> danger("Исполняемый файл macOS (Mach-O)")
            "APK" -> warn("Android-пакет (APK) — установка из мессенджера не рекомендуется")
            "ZIP" -> if (size > 100L * 1024 * 1024) {
                warn("Крупный архив: возможна архивная бомба")
            }
            else -> Unit
        }

        if (detected == "ZIP" || detected == "APK") {
            inspectArchive(uri).forEach { finding ->
                if (finding.dangerous) danger(finding.reason) else warn(finding.reason)
            }
        }

        if (isMacroDocument(extension)) {
            warn("Документ с поддержкой макросов")
        }
        if (extension in EXECUTABLE_EXTENSIONS && detected !in EXECUTABLE_TYPES) {
            warn("Расширение .$extension не соответствует содержимому ($detected)")
        }
        if (hasDoubleExtension(name)) {
            warn("Двойное расширение в имени файла")
        }
        if (declaredMime != null && detected == "unknown" && declaredMime.startsWith("image/")) {
            warn("MIME-тип обещает изображение, но сигнатура не распознана")
        }
        if (size == 0L) {
            warn("Пустой файл")
        }

        if (reasons.isEmpty()) {
            reasons += "Сигнатур не найдено, тип соответствует расширению"
        }

        ScanReport(
            verdict = verdict,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            sizeBytes = size,
            mimeType = declaredMime,
            detectedType = detected,
            reasons = reasons,
            fileName = name,
        )
    }

    private fun queryMeta(uri: Uri): Triple<String, String?, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return Triple(name, context.contentResolver.getType(uri), size)
    }

    private fun detectType(bytes: ByteArray): String {
        fun startsWith(vararg prefix: Int): Boolean =
            bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xff) == prefix[it] }

        return when {
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "PNG image"
            startsWith(0xFF, 0xD8, 0xFF) -> "JPEG image"
            startsWith(0x47, 0x49, 0x46, 0x38) -> "GIF image"
            startsWith(0x25, 0x50, 0x44, 0x46) -> "PDF"
            startsWith(0x50, 0x4B, 0x03, 0x04) ->
                if (bytes.containsAscii("AndroidManifest.xml") || bytes.containsAscii("classes.dex")) "APK" else "ZIP"
            startsWith(0x1F, 0x8B) -> "GZIP"
            startsWith(0x37, 0x7A, 0xBC, 0xAF) -> "7-Zip"
            startsWith(0x52, 0x61, 0x72, 0x21) -> "RAR"
            startsWith(0x4D, 0x5A) -> "PE executable"
            startsWith(0x7F, 0x45, 0x4C, 0x46) -> "ELF executable"
            startsWith(0xCF, 0xFA, 0xED, 0xFE) || startsWith(0xFE, 0xED, 0xFA, 0xCF) -> "Mach-O executable"
            startsWith(0x52, 0x49, 0x46, 0x46) && bytes.containsAscii("WEBP") -> "WebP image"
            startsWith(0x52, 0x49, 0x46, 0x46) && bytes.containsAscii("WAVE") -> "WAV audio"
            bytes.size >= 12 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" -> "MP4 video"
            startsWith(0x4F, 0x67, 0x67, 0x53) -> "OGG audio"
            startsWith(0x66, 0x4C, 0x61, 0x43) -> "FLAC audio"
            startsWith(0x49, 0x44, 0x33) -> "MP3 audio"
            else -> "unknown"
        }
    }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val needle = value.toByteArray()
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun isMacroDocument(extension: String): Boolean =
        extension in setOf("docm", "xlsm", "pptm", "xlam", "dotm")

    private fun hasDoubleExtension(name: String): Boolean {
        val parts = name.lowercase().split('.')
        if (parts.size < 3) return false
        return parts[parts.size - 2] in EXECUTABLE_EXTENSIONS ||
            parts[parts.size - 2] in setOf("pdf", "doc", "docx", "jpg", "png")
    }

    /**
     * Проверяет ZIP/APK на path traversal и архивные бомбы. Читает данные
     * потоково и останавливается при превышении лимитов, не распаковывая
     * архив на диск.
     */
    private fun inspectArchive(uri: Uri): List<ArchiveFinding> {
        val findings = mutableListOf<ArchiveFinding>()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                findings += inspectArchiveStream(raw)
            }
        }.onFailure {
            findings += ArchiveFinding(
                "Не удалось проверить архив: ${it.message ?: "ошибка"}",
                false,
            )
        }
        return findings
    }

    companion object {
        private const val HEAD_LIMIT = 1024 * 1024
        private val EXECUTABLE_TYPES = setOf("PE executable", "ELF executable", "Mach-O executable", "APK")
        private val EXECUTABLE_EXTENSIONS = setOf("exe", "dll", "so", "elf", "apk", "bat", "cmd", "ps1", "vbs", "js", "jar", "msi", "scr")
    }
}

internal data class ArchiveFinding(val reason: String, val dangerous: Boolean)

private const val MAX_ARCHIVE_ENTRIES = 10_000
private const val MAX_ARCHIVE_UNCOMPRESSED = 200L * 1024 * 1024

/**
 * Потоково проверяет ZIP на path traversal и архивные бомбы, не распаковывая
 * архив на диск. Вынесено из класса, чтобы покрывать тестами без Android.
 */
internal fun inspectArchiveStream(
    input: InputStream,
    maxEntries: Int = MAX_ARCHIVE_ENTRIES,
    maxUncompressed: Long = MAX_ARCHIVE_UNCOMPRESSED,
): List<ArchiveFinding> {
    val findings = mutableListOf<ArchiveFinding>()
    ZipInputStream(BufferedInputStream(input)).use { zip ->
        var entries = 0
        var total = 0L
        var stop = false
        val buffer = ByteArray(64 * 1024)
        while (!stop) {
            val entry = zip.nextEntry ?: break
            entries++
            val name = entry.name.orEmpty()
            if (isUnsafeArchivePath(name)) {
                findings += ArchiveFinding("Небезопасный путь в архиве: $name", true)
            }
            if (entries > maxEntries) {
                findings += ArchiveFinding(
                    "Слишком много файлов в архиве (> $maxEntries)",
                    true,
                )
                stop = true
                break
            }
            if (!entry.isDirectory) {
                while (true) {
                    val read = zip.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxUncompressed) {
                        findings += ArchiveFinding(
                            "Архив распаковывается больше ${maxUncompressed / (1024 * 1024)} МиБ — возможна бомба",
                            true,
                        )
                        stop = true
                        break
                    }
                }
            }
            if (!stop) zip.closeEntry()
        }
    }
    return findings
}

internal fun isUnsafeArchivePath(name: String): Boolean {
    val normalized = name.replace('\\', '/')
    return normalized.startsWith("/") ||
        normalized.startsWith("..") ||
        normalized.contains("../") ||
        Regex("^[A-Za-z]:").containsMatchIn(normalized)
}
