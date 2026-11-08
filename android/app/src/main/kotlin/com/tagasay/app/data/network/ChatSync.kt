package com.tagasay.app.data.network

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.tagasay.app.data.AppRepository
import com.tagasay.app.data.crypto.SealedSender
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.MessageStatus
import com.tagasay.core.crypto.CryptoEngine
import com.tagasay.core.crypto.MessageFraming
import java.util.UUID

/**
 * Синхронизация личных чатов с relay.
 *
 * Наружу уходит только шифрованный [WireMessage]. Один формат покрывает текст,
 * вложения, редактирование, удаление и квитанции доставки/прочтения. Получатель
 * использует тот же id сообщения, что и отправитель, поэтому квитанции ссылаются
 * на стабильные идентификаторы.
 */
class ChatSync(
    private val api: TagaApi,
    private val messenger: CryptoMessenger,
    private val repository: AppRepository,
    private val mediaTransfer: MediaTransfer,
    private val localUsername: String,
    private val crypto: CryptoEngine,
) {

    // --------------------------------------------------------------- outgoing

    suspend fun send(
        chatId: String,
        messageId: String,
        text: String,
        kind: MessageKind = MessageKind.TEXT,
        sticker: String? = null,
        replyToId: String? = null,
        replyToText: String? = null,
        forwardedFrom: String? = null,
        poll: com.tagasay.app.data.model.Poll? = null,
    ): Result<Unit> = runCatching {
        val chat = directChat(chatId)
        sendWire(
            chat,
            WireMessage(
                type = WireMessage.TYPE_TEXT,
                id = messageId,
                timestamp = System.currentTimeMillis(),
                text = text,
                kind = kind.name,
                sticker = sticker,
                replyToId = replyToId,
                replyToText = replyToText,
                forwardedFrom = forwardedFrom,
                poll = poll?.toWire(),
            ),
        )
    }

    /**
     * Загружает шифрованный файл на relay и отправляет E2EE-конверт
     * с ключом и метаданными. Сервер не видит ни файл, ни ключ.
     */
    suspend fun sendAttachment(
        chatId: String,
        messageId: String,
        kind: MessageKind,
        attachment: Attachment,
        source: Uri,
        caption: String,
        replyToId: String?,
        replyToText: String?,
    ): Result<MediaTransfer.Prepared> = runCatching {
        val chat = directChat(chatId)
        val prepared = mediaTransfer.prepare(attachment, source)
        sendWire(
            chat,
            WireMessage(
                type = WireMessage.TYPE_MEDIA,
                id = messageId,
                timestamp = System.currentTimeMillis(),
                text = caption,
                kind = kind.name,
                replyToId = replyToId,
                replyToText = replyToText,
                attachment = WireAttachment(
                    kind = kind.name,
                    name = attachment.name,
                    mimeType = attachment.mimeType,
                    sizeBytes = attachment.sizeBytes,
                    sha256 = attachment.sha256,
                    scanVerdict = attachment.scanVerdict.name,
                    mediaId = prepared.mediaId,
                    keyBase64 = prepared.keyBase64,
                ),
            ),
        )
        prepared
    }

    /** Пересылает уже существующее сообщение, не загружая блоб повторно. */
    suspend fun forward(chatId: String, message: Message, forwardedFrom: String): Result<Unit> =
        runCatching {
            val chat = directChat(chatId)
            val attachment = message.attachment
            val wire = if (attachment?.mediaId != null && attachment.keyBase64 != null) {
                WireMessage(
                    type = WireMessage.TYPE_MEDIA,
                    id = message.id,
                    timestamp = System.currentTimeMillis(),
                    text = message.text,
                    kind = message.kind.name,
                    sticker = message.sticker,
                    forwardedFrom = forwardedFrom,
                    attachment = WireAttachment(
                        kind = message.kind.name,
                        name = attachment.name,
                        mimeType = attachment.mimeType,
                        sizeBytes = attachment.sizeBytes,
                        sha256 = attachment.sha256,
                        scanVerdict = attachment.scanVerdict.name,
                        mediaId = attachment.mediaId,
                        keyBase64 = attachment.keyBase64,
                    ),
                )
            } else {
                WireMessage(
                    type = WireMessage.TYPE_TEXT,
                    id = message.id,
                    timestamp = System.currentTimeMillis(),
                    text = message.text,
                    kind = message.kind.name,
                    sticker = message.sticker,
                    forwardedFrom = forwardedFrom,
                )
            }
            sendWire(chat, wire)
        }

    suspend fun edit(chatId: String, messageId: String, text: String): Result<Unit> = runCatching {
        val chat = directChat(chatId)
        sendWire(
            chat,
            WireMessage(
                type = WireMessage.TYPE_EDIT,
                id = messageId,
                timestamp = System.currentTimeMillis(),
                text = text,
            ),
        )
    }

    suspend fun react(chatId: String, messageId: String, emoji: String): Result<Unit> = runCatching {
        val chat = directChat(chatId)
        sendWire(
            chat,
            WireMessage(
                type = WireMessage.TYPE_REACTION,
                id = messageId,
                timestamp = System.currentTimeMillis(),
                text = emoji,
            ),
        )
    }

    suspend fun delete(chatId: String, messageId: String): Result<Unit> = runCatching {
        val chat = directChat(chatId)
        sendWire(
            chat,
            WireMessage(
                type = WireMessage.TYPE_DELETE,
                id = messageId,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    /** Отправляет квитанцию о прочтении всех входящих сообщений чата. */
    suspend fun sendReadReceipt(chatId: String): Result<Unit> = runCatching {
        val chat = directChat(chatId)
        val ids = repository.messages(chatId)
            .filter { !it.isMine && it.status != MessageStatus.FAILED }
            .map { it.id }
            .filter { it.isNotBlank() }
            .takeLast(MAX_RECEIPT_IDS)
        if (ids.isEmpty()) return@runCatching
        sendWire(
            chat,
            WireMessage(
                type = WireMessage.TYPE_READ,
                id = UUID.randomUUID().toString(),
                ids = ids,
            ),
        )
    }

    /**
     * Скачивает и расшифровывает вложение. Возвращает локальный content:// URI.
     */
    suspend fun downloadAttachment(attachment: Attachment): Uri =
        mediaTransfer.download(attachment)

    // ---------------------------------------------------------------- polling

    /**
     * Забирает и расшифровывает очередь. Возвращает количество принятых сообщений.
     * Повреждённый конверт подтверждается (ack), чтобы не блокировать очередь.
     */
    suspend fun poll(): Int {
        val envelopes = api.fetchEnvelopes()
        if (envelopes.isEmpty()) return 0
        var received = 0
        val ackIds = mutableListOf<String>()
        val delivered = linkedMapOf<String, MutableList<String>>()

        for (envelope in envelopes) {
            val sender = resolveSender(envelope)
            if (sender == null) {
                Log.w("TAGaSync", "skip envelope ${envelope.id}: unreadable sealed sender")
                ackIds += envelope.id
                continue
            }
            val payload = runCatching {
                Base64.decode(envelope.ciphertext, Base64.NO_WRAP)
            }.getOrNull()
            if (payload != null) {
                val plaintext = runCatching {
                    messenger.decryptFrom(sender, payload)
                }.onFailure {
                    Log.w("TAGaSync", "decrypt failed from $sender: ${it.message}", it)
                }.getOrNull()
                if (plaintext != null) {
                    val wire = WireCodec.decode(plaintext)
                    if (wire != null) {
                        val created = handleWire(sender, wire)
                        if (created) {
                            received++
                            if (wire.type == WireMessage.TYPE_TEXT || wire.type == WireMessage.TYPE_MEDIA) {
                                delivered.getOrPut(sender) { mutableListOf() } += wire.id
                            }
                        }
                    } else {
                        receiveLegacy(sender, plaintext)
                        received++
                    }
                }
            }
            ackIds += envelope.id
        }
        api.ack(ackIds)

        for ((peer, ids) in delivered) {
            if (ids.isEmpty()) continue
            runCatching {
                sendWireByPeer(
                    peer,
                    WireMessage(
                        type = WireMessage.TYPE_DELIVERED,
                        id = UUID.randomUUID().toString(),
                        ids = ids,
                    ),
                )
            }.onFailure {
                Log.w("TAGaSync", "delivery receipt failed: ${it.message}", it)
            }
        }
        return received
    }

    /** Применяет структурированное сообщение. true — если появилось новое сообщение. */
    private fun handleWire(sender: String, wire: WireMessage): Boolean = when (wire.type) {
        WireMessage.TYPE_TEXT -> {
            repository.receiveMessage(
                senderUsername = sender,
                text = wire.text,
                messageId = wire.id,
                timestamp = wire.timestamp,
                kind = parseKind(wire.kind),
                sticker = wire.sticker,
                replyToId = wire.replyToId,
                replyToText = wire.replyToText,
                forwardedFrom = wire.forwardedFrom,
                poll = wire.poll?.toPoll(),
            )
            true
        }

        WireMessage.TYPE_MEDIA -> {
            val attachment = wire.attachment ?: return false
            repository.receiveMessage(
                senderUsername = sender,
                text = wire.text,
                messageId = wire.id,
                timestamp = wire.timestamp,
                kind = parseKind(wire.kind),
                attachment = attachment.toAttachment(),
                replyToId = wire.replyToId,
                replyToText = wire.replyToText,
                forwardedFrom = wire.forwardedFrom,
            )
            true
        }

        WireMessage.TYPE_EDIT -> {
            repository.applyRemoteEdit(sender, wire.id, wire.text)
            false
        }

        WireMessage.TYPE_DELETE -> {
            repository.applyRemoteDelete(sender, wire.id)
            false
        }

        WireMessage.TYPE_REACTION -> {
            if (wire.text.isNotBlank()) {
                repository.applyRemoteReaction(sender, wire.id, wire.text)
            }
            false
        }

        WireMessage.TYPE_DELIVERED -> {
            repository.applyReceipt(sender, wire.ids.toSet(), MessageStatus.DELIVERED)
            false
        }

        WireMessage.TYPE_READ -> {
            repository.applyReceipt(sender, wire.ids.toSet(), MessageStatus.READ)
            false
        }

        else -> {
            Log.w("TAGaSync", "unknown wire type ${wire.type} from $sender")
            false
        }
    }

    /** Сообщения, отправленные до появления WireMessage (сырой текст / TAGA-MEDIA). */
    private fun receiveLegacy(sender: String, plaintext: ByteArray) {
        val legacy = mediaTransfer.decode(plaintext)
        if (legacy != null) {
            repository.receiveMessage(
                senderUsername = sender,
                text = legacy.caption,
                kind = runCatching { MessageKind.valueOf(legacy.kind) }
                    .getOrDefault(MessageKind.FILE),
                attachment = MediaTransfer.wireToAttachment(legacy),
                replyToText = legacy.replyToText,
            )
        } else {
            repository.receiveMessage(sender, plaintext.decodeToString())
        }
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun directChat(chatId: String): Chat {
        val chat = repository.chat(chatId) ?: error("chat not found")
        require(chat.type == ChatType.PRIVATE) { "only direct chats are supported" }
        require(chat.username.isNotBlank()) { "chat has no peer username" }
        return chat
    }

    private suspend fun sendWire(chat: Chat, wire: WireMessage) =
        sendWireByPeer(chat.username, wire)

    private suspend fun sendWireByPeer(peer: String, wire: WireMessage) {
        val ciphertext = messenger.encryptFor(peer, WireCodec.encode(wire))
        // Sealed sender: relay не должен узнать, кто отправил конверт.
        // Если запечатать не удалось — отправка падает, а не утекает username.
        val sealed = SealedSender.seal(
            recipientUsername = peer,
            recipientIdentityKey = messenger.recipientIdentity(peer),
            senderUsername = localUsername,
            senderIdentityKey = messenger.identityPublicKey(),
            crypto = crypto,
        )
        api.sendEnvelope(
            SendEnvelopeRequest(
                id = UUID.randomUUID().toString(),
                recipient = peer,
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                sizeClass = MessageFraming.sizeClassFor(ciphertext.size),
                sealedSender = sealed,
            ),
        )
    }

    /**
     * Username отправителя: из sealed sender (предпочтительно) или из
     * legacy-поля sender. null — конверт нельзя безопасно обработать.
     */
    private fun resolveSender(envelope: EnvelopeDto): String? {
        if (envelope.sealedSender.isBlank()) {
            return envelope.sender.takeIf { it.isNotBlank() }
        }
        val data = SealedSender.unseal(
            recipientIdentityPrivateKey = messenger.identityPrivateKey(),
            recipientUsername = localUsername,
            sealedBase64 = envelope.sealedSender,
            crypto = crypto,
        ) ?: return null
        val known = messenger.knownIdentity(data.username)
        if (known != null && !known.contentEquals(data.identityKey)) {
            Log.w("TAGaSync", "sealed sender identity changed for ${data.username}")
            return null
        }
        return data.username
    }

    private fun parseKind(value: String): MessageKind =
        runCatching { MessageKind.valueOf(value) }.getOrDefault(MessageKind.TEXT)

    private companion object {
        const val MAX_RECEIPT_IDS = 200
    }
}
