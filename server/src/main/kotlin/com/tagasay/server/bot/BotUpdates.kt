package com.tagasay.server.bot

import kotlinx.coroutines.delay

/**
 * Выдача updates: offset/ack, long poll до 50 с, фильтр allowed_updates.
 * Дубли в пределах одного offset не выдаются; после подтверждения offset'ом
 * updates удаляются (семантика Telegram Bot API).
 */
class BotUpdates(private val store: BotStore) {

    suspend fun await(
        botId: String,
        offset: Long,
        limit: Int,
        timeoutSeconds: Int,
        allowed: Set<String>,
    ): List<BotUpdate> {
        val safeLimit = limit.coerceIn(1, 100)
        val deadline = System.currentTimeMillis() + timeoutSeconds.coerceIn(0, 50) * 1_000L
        while (true) {
            val batch = store.pendingUpdates(botId, offset, safeLimit, allowed)
            if (batch.isNotEmpty()) {
                store.acknowledge(botId, offset)
                return batch
            }
            store.acknowledge(botId, offset)
            if (timeoutSeconds <= 0 || System.currentTimeMillis() >= deadline) return emptyList()
            delay(200)
        }
    }

    suspend fun awaitInbox(
        user: String,
        since: Long,
        limit: Int,
        timeoutSeconds: Int,
        roomAccess: Map<String, Long> = emptyMap(),
    ): List<InboxEntry> {
        val safeLimit = limit.coerceIn(1, 100)
        val deadline = System.currentTimeMillis() + timeoutSeconds.coerceIn(0, 30) * 1_000L
        while (true) {
            val batch = store.inbox(user, since, safeLimit, roomAccess)
            if (batch.isNotEmpty()) return batch
            if (timeoutSeconds <= 0 || System.currentTimeMillis() >= deadline) return emptyList()
            delay(200)
        }
    }

    fun messageFor(botId: String, messageId: String): BotMessage? = store.messageById(botId, messageId)
}
