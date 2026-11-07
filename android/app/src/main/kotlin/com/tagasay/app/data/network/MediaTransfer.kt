package com.tagasay.app.data.network

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.ScanVerdict
import com.tagasay.core.crypto.CryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Шифрование и передача вложений.
 *
 * Файл шифруется на устройстве AES-256-GCM. На relay уходит только ciphertext;
 * ключ и метаданные передаются отдельным E2EE-конвертом. AAD связывает
 * шифротекст с SHA-256 и именем файла, поэтому подмена вложений невозможна.
 */
class MediaTransfer(
    private val context: Context,
    private val crypto: CryptoEngine,
    private val api: TagaApi,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class Prepared(val mediaId: String, val keyBase64: String, val cachedUri: Uri)

    suspend fun prepare(attachment: Attachment, source: Uri): Prepared {
        val plaintext = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                ?: error("Не удалось прочитать файл")
        }
        require(plaintext.isNotEmpty()) { "Файл пуст" }
        require(plaintext.size.toLong() <= MAX_PLAINTEXT_BYTES) { "Файл слишком большой" }

        val actual = crypto.sha256Hex(plaintext)
        require(actual.equals(attachment.sha256, ignoreCase = true)) {
            "Файл изменился после проверки антивирусом"
        }

        val cachedUri = withContext(Dispatchers.IO) { cachePlaintext(attachment, plaintext) }
        val key = crypto.randomBytes(KEY_BYTES)
        val ciphertext = crypto.aesGcmEncrypt(key, plaintext, aadFor(attachment))
        val uploaded = api.uploadMedia(ciphertext, crypto.sha256Hex(ciphertext))
        return Prepared(
            mediaId = uploaded.id,
            keyBase64 = Base64.encodeToString(key, Base64.NO_WRAP),
            cachedUri = cachedUri,
        )
    }

    /**
     * Скачивает, расшифровывает и кеширует файл. Возвращает content:// URI,
     * который можно открыть через FileProvider.
     */
    suspend fun download(attachment: Attachment): Uri {
        val mediaId = attachment.mediaId ?: error("У вложения нет mediaId")
        val keyBase64 = attachment.keyBase64 ?: error("У вложения нет ключа")
        val key = Base64.decode(keyBase64, Base64.NO_WRAP)
        require(key.size == KEY_BYTES) { "Некорректный ключ вложения" }

        val ciphertext = api.downloadMedia(mediaId)
        val plaintext = crypto.aesGcmDecrypt(key, ciphertext, aadFor(attachment))
        val actual = crypto.sha256Hex(plaintext)
        require(actual.equals(attachment.sha256, ignoreCase = true)) {
            "SHA-256 вложения не совпадает"
        }

        return withContext(Dispatchers.IO) { cachePlaintext(attachment, plaintext) }
    }

    private fun cachePlaintext(attachment: Attachment, plaintext: ByteArray): Uri {
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        val extension = attachment.name
            .substringAfterLast('.', "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(12)
            .ifBlank { "bin" }
        val file = File(dir, "${attachment.sha256.take(32)}.$extension")
        if (!file.exists() || file.length() != plaintext.size.toLong()) {
            file.writeBytes(plaintext)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun encode(wire: MediaWire): ByteArray =
        MEDIA_PREFIX + json.encodeToString(MediaWire.serializer(), wire).encodeToByteArray()

    fun decode(payload: ByteArray): MediaWire? {
        if (payload.size < MEDIA_PREFIX.size) return null
        for (index in MEDIA_PREFIX.indices) {
            if (payload[index] != MEDIA_PREFIX[index]) return null
        }
        val body = payload.copyOfRange(MEDIA_PREFIX.size, payload.size)
        return runCatching {
            json.decodeFromString(MediaWire.serializer(), body.decodeToString())
        }.getOrNull()
    }

    private fun aadFor(attachment: Attachment): ByteArray =
        "taga/media/v1|${attachment.sha256}|${attachment.name}".encodeToByteArray()

    @Serializable
    data class MediaWire(
        val kind: String,
        val name: String,
        val mimeType: String? = null,
        val sizeBytes: Long,
        val sha256: String,
        val scanVerdict: String = ScanVerdict.SAFE.name,
        val mediaId: String,
        val keyBase64: String,
        val caption: String = "",
        val replyToText: String? = null,
    )

    companion object {
        private const val KEY_BYTES = 32
        private const val CACHE_DIR = "media_cache"
        const val MAX_PLAINTEXT_BYTES = 50L * 1024 * 1024

        private val MEDIA_PREFIX = "TAGA-MEDIA\u0001".toByteArray()

        fun wireToAttachment(wire: MediaWire): Attachment = Attachment(
            name = wire.name,
            mimeType = wire.mimeType,
            sizeBytes = wire.sizeBytes,
            sha256 = wire.sha256,
            scanVerdict = runCatching { ScanVerdict.valueOf(wire.scanVerdict) }
                .getOrDefault(ScanVerdict.WARNING),
            mediaId = wire.mediaId,
            keyBase64 = wire.keyBase64,
        )
    }
}
