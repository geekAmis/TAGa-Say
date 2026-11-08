package com.tagasay.app.data.network

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.tagasay.app.data.model.Story
import com.tagasay.core.crypto.CryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Шифрованная передача медиа историй. Сервер получает только ciphertext;
 * ключ хранится в зашифрованном состоянии пользователя.
 */
class StoryTransfer(
    private val context: Context,
    private val crypto: CryptoEngine,
) {
    data class Prepared(
        val mediaId: String,
        val keyBase64: String,
        val sha256: String,
        val mimeType: String,
        val sizeBytes: Long,
        val localUri: String,
    )

    suspend fun upload(api: TagaApi, source: Uri): Prepared {
        val mime = context.contentResolver.getType(source) ?: "image/jpeg"
        val plaintext = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                ?: error("Не удалось прочитать изображение")
        }
        require(plaintext.isNotEmpty()) { "Пустое изображение" }
        require(plaintext.size <= MAX_BYTES) { "История слишком большая" }

        val sha = crypto.sha256Hex(plaintext)
        val key = crypto.randomBytes(KEY_BYTES)
        val ciphertext = crypto.aesGcmEncrypt(key, plaintext, aad(sha, mime))
        val uploaded = api.uploadMedia(ciphertext, crypto.sha256Hex(ciphertext))
        val local = withContext(Dispatchers.IO) { cache(sha, mime, plaintext) }
        return Prepared(
            mediaId = uploaded.id,
            keyBase64 = Base64.encodeToString(key, Base64.NO_WRAP),
            sha256 = sha,
            mimeType = mime,
            sizeBytes = plaintext.size.toLong(),
            localUri = local.toURI().toString(),
        )
    }

    suspend fun download(api: TagaApi, story: Story): String? {
        val mediaId = story.mediaId ?: return null
        val keyBase64 = story.keyBase64 ?: return null
        val sha = story.sha256 ?: return null
        val mime = story.mimeType ?: "image/jpeg"
        val key = Base64.decode(keyBase64, Base64.NO_WRAP)
        if (key.size != KEY_BYTES) return null
        val ciphertext = api.downloadMedia(mediaId)
        val plaintext = crypto.aesGcmDecrypt(key, ciphertext, aad(sha, mime))
        if (!crypto.sha256Hex(plaintext).equals(sha, ignoreCase = true)) return null
        val file = withContext(Dispatchers.IO) { cache(sha, mime, plaintext) }
        return file.toURI().toString()
    }

    private fun cache(sha: String, mime: String, plaintext: ByteArray): File {
        val dir = File(context.filesDir, "stories").apply { mkdirs() }
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            mime.contains("mp4") -> "mp4"
            else -> "jpg"
        }
        val file = File(dir, "${sha.take(32)}.$ext")
        if (!file.exists() || file.length() != plaintext.size.toLong()) {
            file.writeBytes(plaintext)
        }
        return file
    }

    private fun aad(sha: String, mime: String): ByteArray =
        "taga/story/v1|$sha|$mime".encodeToByteArray()

    private companion object {
        const val KEY_BYTES = 32
        const val MAX_BYTES = 25 * 1024 * 1024
    }
}
