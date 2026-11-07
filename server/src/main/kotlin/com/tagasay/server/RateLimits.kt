package com.tagasay.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.util.concurrent.ConcurrentHashMap

/**
 * Простой потокобезопасный rate limiter (fixed window).
 *
 * Ключ — bucket + IP из X-Forwarded-For (Caddy) или прямое соединение.
 * Для закрытого развёртывания этого достаточно, чтобы остановить перебор
 * паролей, спам конвертами и разрастание очередей.
 */
class RateLimiter {
    private data class Window(val start: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    fun allow(key: String, limit: Int, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val updated = windows.compute(key) { _, previous ->
            if (previous == null || now - previous.start >= windowMs) {
                Window(now, 1)
            } else {
                Window(previous.start, previous.count + 1)
            }
        } ?: return true

        if (windows.size > MAX_KEYS) {
            windows.entries.removeIf { now - it.value.start >= windowMs }
        }
        return updated.count <= limit
    }

    private companion object {
        const val MAX_KEYS = 20_000
    }
}

object RateLimits {
    private val limiter = RateLimiter()

    fun clientKey(call: ApplicationCall): String =
        call.request.headers["X-Forwarded-For"]
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: call.request.origin.remoteHost

    fun allow(
        call: ApplicationCall,
        bucket: String,
        limit: Int,
        windowMs: Long = 60_000,
    ): Boolean = limiter.allow("$bucket:${clientKey(call)}", limit, windowMs)
}
