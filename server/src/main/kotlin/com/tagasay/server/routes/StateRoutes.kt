package com.tagasay.server.routes

import com.tagasay.server.RateLimits
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.ErrorResponse
import com.tagasay.server.model.UserState
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
private data class StateUpdateRequest(
    val ciphertext: String,
    val updatedAt: Long = 0,
)

/**
 * Зашифрованное состояние пользователя: профиль, настройки, папки, стикеры,
 * журнал звонков, контакты, каналы и истории. Сервер видит только ciphertext.
 *
 * Ключ выводится на клиенте из Argon2id-verifier пароля, поэтому состояние
 * можно расшифровать на другом устройстве того же аккаунта.
 */
fun Route.stateRoutes(store: InMemoryStore) {
    get("/v1/state") {
        val authed = call.requireDevice(store) ?: return@get
        if (!RateLimits.allow(call, "state-get", limit = 120)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
            return@get
        }
        call.respond(store.state(authed.device.username) ?: UserState())
    }

    put("/v1/state") {
        val authed = call.requireDevice(store) ?: return@put
        if (!RateLimits.allow(call, "state-put", limit = 60)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
            return@put
        }
        val request = call.receive<StateUpdateRequest>()
        if (request.ciphertext.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("empty state", "BAD_REQUEST"))
            return@put
        }
        val bytes = runCatching { Base64.getDecoder().decode(request.ciphertext) }.getOrNull()
        if (bytes == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid base64", "BAD_REQUEST"))
            return@put
        }
        if (bytes.size > MAX_STATE_BYTES) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse("state too large", "PAYLOAD_TOO_LARGE"),
            )
            return@put
        }
        val saved = store.putState(authed.device.username, request.ciphertext, request.updatedAt)
        call.respond(saved)
    }
}

private const val MAX_STATE_BYTES = 2 * 1024 * 1024
