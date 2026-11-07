package com.tagasay.app.data.network

import android.util.Base64
import android.util.Log
import com.tagasay.app.data.AppRepository
import com.tagasay.app.data.model.ChatType
import java.util.UUID

/**
 * Синхронизация личных чатов с relay: отправка E2EE-конвертов и приём очереди.
 */
class ChatSync(
    private val api: TagaApi,
    private val messenger: CryptoMessenger,
    private val repository: AppRepository,
) {

    suspend fun send(chatId: String, text: String): Result<Unit> = runCatching {
        val chat = repository.chat(chatId) ?: error("chat not found")
        require(chat.type == ChatType.PRIVATE) { "only direct chats are supported" }
        require(chat.username.isNotBlank()) { "chat has no peer username" }
        val ciphertext = messenger.encryptFor(chat.username, text.toByteArray())
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
                    messenger.decryptFrom(envelope.sender, payload).decodeToString()
                }.onFailure {
                    Log.w("TAGaSync", "decrypt failed from ${envelope.sender}: ${it.message}", it)
                }.getOrNull()
                if (plaintext != null) {
                    repository.receiveMessage(envelope.sender, plaintext)
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
