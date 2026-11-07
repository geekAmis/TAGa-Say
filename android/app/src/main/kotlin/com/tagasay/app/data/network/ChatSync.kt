package com.tagasay.app.data.network

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.tagasay.app.data.AppRepository
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.MessageKind
import java.util.UUID

/**
 * Синхронизация личных чатов с relay: отправка E2EE-конвертов и приём очереди.
 */
class ChatSync(
    private val api: TagaApi,
    private val messenger: CryptoMessenger,
    private val repository: AppRepository,
    private val mediaTransfer: MediaTransfer,
) {

    suspend fun send(chatId: String, text: String): Result<Unit> = runCatching {
        val chat = directChat(chatId)
        val ciphertext = messenger.encryptFor(chat.username, text.toByteArray())
        sendEnvelope(chat, ciphertext)
    }

    /**
     * Загружает шифрованный файл на relay и отправляет E2EE-конверт
     * с ключом и метаданными. Сервер не видит ни файл, ни ключ.
     */
    suspend fun sendAttachment(
        chatId: String,
        kind: MessageKind,
        attachment: Attachment,
        source: Uri,
        caption: String,
        replyToText: String?,
    ): Result<Uri> = runCatching {
        val chat = directChat(chatId)
        val prepared = mediaTransfer.prepare(attachment, source)
        val wire = MediaTransfer.MediaWire(
            kind = kind.name,
            name = attachment.name,
            mimeType = attachment.mimeType,
            sizeBytes = attachment.sizeBytes,
            sha256 = attachment.sha256,
            scanVerdict = attachment.scanVerdict.name,
            mediaId = prepared.mediaId,
            keyBase64 = prepared.keyBase64,
            caption = caption,
            replyToText = replyToText,
        )
        val ciphertext = messenger.encryptFor(chat.username, mediaTransfer.encode(wire))
        sendEnvelope(chat, ciphertext)
        prepared.cachedUri
    }

    /**
     * Скачивает и расшифровывает вложение. Возвращает локальный content:// URI.
     */
    suspend fun downloadAttachment(attachment: Attachment): Uri =
        mediaTransfer.download(attachment)

    private suspend fun directChat(chatId: String): com.tagasay.app.data.model.Chat {
        val chat = repository.chat(chatId) ?: error("chat not found")
        require(chat.type == ChatType.PRIVATE) { "only direct chats are supported" }
        require(chat.username.isNotBlank()) { "chat has no peer username" }
        return chat
    }

    private suspend fun sendEnvelope(chat: com.tagasay.app.data.model.Chat, ciphertext: ByteArray) {
        api.sendEnvelope(
            SendEnvelopeRequest(
                id = UUID.randomUUID().toString(),
                recipient = chat.username,
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                sizeClass = sizeClassFor(ciphertext.size),
            ),
        )
    }

    /**
     * Забирает и расшифровывает очередь. Возвращает количество принятых сообщений.
     * Повреждённый конверт подтверждается (ack), чтобы не блокировать очередь.
     */
    suspend fun poll(): Int {
        val envelopes = api.fetchEnvelopes()
        if (envelopes.isEmpty()) return 0
        var received = 0
        val ackIds = mutableListOf<String>()
        for (envelope in envelopes) {
            val payload = runCatching {
                Base64.decode(envelope.ciphertext, Base64.NO_WRAP)
            }.getOrNull()
            if (payload != null) {
                val plaintext = runCatching {
                    messenger.decryptFrom(envelope.sender, payload)
                }.onFailure {
                    Log.w("TAGaSync", "decrypt failed from ${envelope.sender}: ${it.message}", it)
                }.getOrNull()
                if (plaintext != null) {
                    val wire = mediaTransfer.decode(plaintext)
                    if (wire != null) {
                        repository.receiveMessage(
                            senderUsername = envelope.sender,
                            text = wire.caption,
                            kind = runCatching { MessageKind.valueOf(wire.kind) }
                                .getOrDefault(MessageKind.FILE),
                            attachment = MediaTransfer.wireToAttachment(wire),
                            replyToText = wire.replyToText,
                        )
                    } else {
                        repository.receiveMessage(
                            senderUsername = envelope.sender,
                            text = plaintext.decodeToString(),
                        )
                    }
                    received++
                }
            }
            ackIds += envelope.id
        }
        api.ack(ackIds)
        return received
    }

    private fun sizeClassFor(size: Int): Int = when {
        size <= 256 -> 0
        size <= 1024 -> 1
        size <= 4096 -> 2
        size <= 16 * 1024 -> 3
        size <= 64 * 1024 -> 4
        size <= 256 * 1024 -> 5
        size <= 1024 * 1024 -> 6
        else -> 7
    }
}
