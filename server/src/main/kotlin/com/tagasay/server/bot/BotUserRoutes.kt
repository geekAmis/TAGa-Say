package com.tagasay.server.bot

import com.tagasay.server.RateLimits
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.ErrorResponse
import com.tagasay.server.routes.requireDevice
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Пользовательский API для приложения `/v1/bots/…` (не Bot API).
 * Авторизация — access token пользователя; сервер повторно проверяет
 * владельца, членство и лимиты.
 */
fun Route.botUserRoutes(
    store: InMemoryStore,
    bots: BotStore,
    service: BotService,
    updates: BotUpdates,
    auth: BotAuth,
) {
    route("/v1/bots") {
        get {
            val authed = call.requireDevice(store) ?: return@get
            if (!RateLimits.allow(call, "bots-search", limit = 120)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
                return@get
            }
            val query = call.request.queryParameters["query"].orEmpty()
            val found = bots.search(query, limit = 50)
            call.respond(
                BotJson.json.encodeToJsonElement(
                    ListSerializer(JsonElement.serializer()),
                    found.map { BotJson.botSummary(it) },
                ),
            )
        }

        post {
            val authed = call.requireDevice(store) ?: return@post
            if (!RateLimits.allow(call, "bots-create", limit = 10)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
                return@post
            }
            if (!service.rateLimits.allowOwner(authed.device.username)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
                return@post
            }
            val request = call.botBody<CreateBotRequest>()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid JSON", "BAD_REQUEST"))
            val outcome = service.createBot(authed.device.username, request.name, request.username)
            if (outcome.bot == null || outcome.token == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(outcome.error ?: "cannot create bot", "BAD_REQUEST"),
                )
                return@post
            }
            val body = buildJsonObject {
                put("bot", BotJson.card(outcome.bot, service.userState(outcome.bot.id, authed.device.username)))
                put("token", outcome.token)
            }
            call.respond(HttpStatusCode.Created, body)
        }

        get("/mine") {
            val authed = call.requireDevice(store) ?: return@get
            if (!RateLimits.allow(call, "bots-mine", limit = 60)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
                return@get
            }
            val owned = bots.botsOwned(authed.device.username)
            call.respond(
                BotJson.json.encodeToJsonElement(
                    ListSerializer(JsonElement.serializer()),
                    owned.map { BotJson.card(it, service.userState(it.id, authed.device.username)) },
                ),
            )
        }

        get("/inbox") {
            val authed = call.requireDevice(store) ?: return@get
            if (!RateLimits.allow(call, "bots-inbox", limit = 240)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
                return@get
            }
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val timeout = call.request.queryParameters["timeout"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val batch = updates.awaitInbox(authed.device.username, since, limit, timeout)
            call.respond(
                BotJson.json.encodeToJsonElement(
                    ListSerializer(InboxEventDto.serializer()),
                    batch.map { it.event },
                ),
            )
        }

        get("/{username}") {
            val authed = call.requireDevice(store) ?: return@get
            val bot = bots.botByUsername(call.parameters["username"].orEmpty())
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
            call.respond(BotJson.card(bot, service.userState(bot.id, authed.device.username)))
        }

        post("/{username}/send") {
            val authed = call.requireDevice(store) ?: return@post
            val bot = bots.botByUsername(call.parameters["username"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
            val request = call.botBody<UserSendToBotRequest>()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid JSON", "BAD_REQUEST"))
            val requested = request.chatId.trim()
            val self = "u:${authed.device.username.lowercase()}"
            if (requested.isNotEmpty() &&
                !requested.equals(self, ignoreCase = true) &&
                !requested.equals(authed.device.username, ignoreCase = true)
            ) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("bot rooms are not enabled in this release", "UNSUPPORTED"),
                )
                return@post
            }
            if (!service.rateLimits.allowUserSend(authed.device.username)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
                return@post
            }
            if (bot.builtin && !service.rateLimits.allowBotFather(authed.device.username)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
                return@post
            }
            try {
                val outcome = service.ingestUserMessage(authed.device.username, bot, request)
                val body = buildJsonObject {
                    put("accepted", true)
                    put("message_id", outcome.inbound.messageId)
                    put("client_msg_id", request.clientMsgId)
                    put(
                        "reply_message_ids",
                        BotJson.stringArray(outcome.replies.map { it.messageId }),
                    )
                }
                call.respond(body)
            } catch (e: BotApiException) {
                call.respond(
                    HttpStatusCode.fromValue(e.errorCode),
                    ErrorResponse(e.message, errorCodeName(e.errorCode)),
                )
            }
        }

        post("/{username}/callback") {
            val authed = call.requireDevice(store) ?: return@post
            val bot = bots.botByUsername(call.parameters["username"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
            val request = call.botBody<UserCallbackRequest>()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid JSON", "BAD_REQUEST"))
            if (bot.builtin && !service.rateLimits.allowBotFather(authed.device.username)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
                return@post
            }
            try {
                val replies = service.userCallback(authed.device.username, bot, request)
                call.respond(
                    buildJsonObject {
                        put("ok", true)
                        put("reply_message_ids", BotJson.stringArray(replies.map { it.messageId }))
                    },
                )
            } catch (e: BotApiException) {
                call.respond(
                    HttpStatusCode.fromValue(e.errorCode),
                    ErrorResponse(e.message, errorCodeName(e.errorCode)),
                )
            }
        }

        post("/{username}/block") {
            val authed = call.requireDevice(store) ?: return@post
            val bot = bots.botByUsername(call.parameters["username"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
            service.blockBot(authed.device.username, bot, blocked = true)
            call.respond(buildJsonObject { put("blocked", true) })
        }

        delete("/{username}/block") {
            val authed = call.requireDevice(store) ?: return@delete
            val bot = bots.botByUsername(call.parameters["username"].orEmpty())
                ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
            service.blockBot(authed.device.username, bot, blocked = false)
            call.respond(buildJsonObject { put("blocked", false) })
        }

        post("/{username}/token") {
            val authed = call.requireDevice(store) ?: return@post
            val bot = bots.botByUsername(call.parameters["username"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
            if (!bot.owner.equals(authed.device.username, ignoreCase = true)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("not the owner", "FORBIDDEN"))
            }
            if (!service.rateLimits.allowOwner(authed.device.username)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
                return@post
            }
            val token = auth.revealToken(bot)
            if (token == null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("token key unavailable; rotate the token", "TOKEN_UNAVAILABLE"),
                )
                return@post
            }
            call.respond(buildJsonObject { put("token", token) })
        }

        post("/{username}/revoke") {
            val authed = call.requireDevice(store) ?: return@post
            val bot = bots.botByUsername(call.parameters["username"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
            if (!service.rateLimits.allowOwner(authed.device.username)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
                return@post
            }
            try {
                val token = service.rotateToken(authed.device.username, bot)
                call.respond(buildJsonObject { put("token", token) })
            } catch (e: BotApiException) {
                call.respond(
                    HttpStatusCode.fromValue(e.errorCode),
                    ErrorResponse(e.message, errorCodeName(e.errorCode)),
                )
            }
        }

        post("/{username}/update") {
            val authed = call.requireDevice(store) ?: return@post
            val bot = bots.botByUsername(call.parameters["username"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
            val request = call.botBody<EditBotRequest>()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid JSON", "BAD_REQUEST"))
            try {
                val updated = service.updateBot(authed.device.username, bot, request)
                call.respond(BotJson.card(updated, service.userState(updated.id, authed.device.username)))
            } catch (e: BotApiException) {
                call.respond(
                    HttpStatusCode.fromValue(e.errorCode),
                    ErrorResponse(e.message, errorCodeName(e.errorCode)),
                )
            }
        }

        delete("/{username}") {
            val authed = call.requireDevice(store) ?: return@delete
            val bot = bots.botByUsername(call.parameters["username"].orEmpty())
                ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
            try {
                service.deleteBot(authed.device.username, bot)
                call.respond(buildJsonObject { put("deleted", true) })
            } catch (e: BotApiException) {
                call.respond(
                    HttpStatusCode.fromValue(e.errorCode),
                    ErrorResponse(e.message, errorCodeName(e.errorCode)),
                )
            }
        }

        get("/{username}/stats") {
            call.requireDevice(store) ?: return@get
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("stats is not supported in this release", "UNSUPPORTED"),
            )
        }

        post("/{username}/webapp/session") {
            call.requireDevice(store) ?: return@post
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("web app sessions are not supported in this release", "UNSUPPORTED"),
            )
        }

        post("/{username}/inline") {
            call.requireDevice(store) ?: return@post
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("inline mode is not supported in this release", "UNSUPPORTED"),
            )
        }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.botBody(): T? =
    runCatching { receive<T>() }.getOrNull()

private fun errorCodeName(code: Int): String = when (code) {
    400 -> "BAD_REQUEST"
    401 -> "UNAUTHORIZED"
    403 -> "FORBIDDEN"
    404 -> "NOT_FOUND"
    409 -> "CONFLICT"
    413 -> "PAYLOAD_TOO_LARGE"
    415 -> "UNSUPPORTED_MEDIA_TYPE"
    429 -> "RATE_LIMITED"
    else -> "ERROR"
}
