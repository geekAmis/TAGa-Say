package com.tagasay.server.bot

import com.tagasay.server.RateLimiter
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Лимиты Bot API (ADR-009). Fixed window, ключи — bot/chat/owner, без IP,
 * чтобы не зависеть от прокси и не логировать адреса.
 */
class BotRateLimits {
    private val limiter = RateLimiter()

    fun allowSend(botId: String): Boolean =
        limiter.allow("bot-send:$botId", limit = 30, windowMs = 1_000)

    fun allowChat(botId: String, chatId: String): Boolean =
        limiter.allow("bot-chat:$botId:$chatId", limit = 1, windowMs = 1_000)

    fun allowUpdates(botId: String): Boolean =
        limiter.allow("bot-updates:$botId", limit = 20, windowMs = 60_000)

    fun allowBotFather(owner: String): Boolean =
        limiter.allow("botfather:${owner.lowercase()}", limit = 20, windowMs = 60_000)

    fun allowOwner(owner: String): Boolean =
        limiter.allow("bot-owner:${owner.lowercase()}", limit = 60, windowMs = 60_000)

    fun allowUserSend(username: String): Boolean =
        limiter.allow("bot-user-send:${username.lowercase()}", limit = 60, windowMs = 60_000)
}

/**
 * Идемпотентность изменяющих методов: X-Idempotency-Key, дедуп 5 минут.
 * Хранится в памяти: повтор после рестарта может создать второе сообщение,
 * это задокументировано.
 */
class BotIdempotency {
    private data class Entry(val expiresAt: Long, val result: JsonElement)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(botId: String, key: String): JsonElement? {
        val entry = entries["$botId:$key"] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            entries.remove("$botId:$key")
            return null
        }
        return entry.result
    }

    fun put(botId: String, key: String, result: JsonElement) {
        val now = System.currentTimeMillis()
        if (entries.size > 10_000) {
            entries.entries.removeIf { it.value.expiresAt <= now }
        }
        entries["$botId:$key"] = Entry(now + 5 * 60_000, result)
    }
}
