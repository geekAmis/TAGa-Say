package com.tagasay.server.bot

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bot API v1.2.0: `/bot/v1/…`.
 *
 * Реализованы: getMe, getUpdates, sendMessage, editMessageText, deleteMessage,
 * getMyCommands/setMyCommands, answerCallbackQuery. Остальные методы честно
 * возвращают `400 unsupported` (см. docs/bot-api.md, помечены `[~]`).
 */
fun Route.botRoutes(
    auth: BotAuth,
    service: BotService,
    updates: BotUpdates,
    idempotency: BotIdempotency = BotIdempotency(),
) {
    route("/bot/v1") {
        get("/getMe") { call.handleGetMe(auth, service) }
        post("/getMe") { call.handleGetMe(auth, service) }

        get("/getUpdates") { call.handleGetUpdates(auth, updates, service.rateLimits) }
        post("/getUpdates") { call.handleGetUpdates(auth, updates, service.rateLimits) }

        post("/sendMessage") { call.handleSendMessage(auth, service, idempotency) }
        post("/editMessageText") { call.handleEditMessageText(auth, service, idempotency) }
        post("/deleteMessage") { call.handleDeleteMessage(auth, service, idempotency) }

        get("/getMyCommands") { call.handleGetCommands(auth, service) }
        post("/getMyCommands") { call.handleGetCommands(auth, service) }
        post("/setMyCommands") { call.handleSetCommands(auth, service) }
        post("/deleteMyCommands") { call.handleDeleteCommands(auth, service) }

        post("/answerCallbackQuery") { call.handleAnswerCallback(auth, service) }

        // Честная заглушка вместо имитации методов, которых нет в релизе.
        post("/{method}") {
            val bot = call.requireBot(auth) ?: return@post
            val method = call.parameters["method"].orEmpty()
            call.botFailure(
                400,
                "method '$method' is not supported in this server release (see docs/bot-api.md)",
            )
        }
        get("/{method}") {
            val bot = call.requireBot(auth) ?: return@get
            val method = call.parameters["method"].orEmpty()
            call.botFailure(
                400,
                "method '$method' is not supported in this server release (see docs/bot-api.md)",
            )
        }
    }
}

// ------------------------------------------------------------------ helpers

internal suspend fun ApplicationCall.requireBot(auth: BotAuth): Bot? {
    return when (val result = auth.authenticate(request.headers[HttpHeaders.Authorization])) {
        is BotAuth.Result.Ok -> result.bot
        is BotAuth.Result.Denied -> {
            botFailure(result.code, result.description)
            null
        }
    }
}

internal suspend fun ApplicationCall.botOk(result: JsonElement) {
    respond(
        buildJsonObject {
            put("ok", true)
            put("result", result)
        },
    )
}

internal suspend fun ApplicationCall.botFailure(
    code: Int,
    description: String,
    retryAfter: Int? = null,
) {
    val body = buildJsonObject {
        put("ok", false)
        put("error_code", code)
        put("description", description)
        if (retryAfter != null) {
            put(
                "parameters",
                buildJsonObject { put("retry_after", retryAfter) },
            )
        }
    }
    respond(HttpStatusCode.fromValue(code), body)
}

private suspend fun ApplicationCall.requireScope(bot: Bot, method: String): Boolean {
    if (BotPermissions.allowed(bot, method)) return true
    botFailure(403, "not enough rights: ${BotPermissions.requiredScope(method)}")
    return false
}

private suspend inline fun <reified T : Any> ApplicationCall.botBody(): T? =
    runCatching { receive<T>() }.getOrNull()

private fun ApplicationCall.idempotencyKey(): String? =
    request.headers["X-Idempotency-Key"]?.trim()?.takeIf { it.length in 1..128 }

// ------------------------------------------------------------------ methods

private suspend fun ApplicationCall.handleGetMe(auth: BotAuth, service: BotService) {
    val bot = requireBot(auth) ?: return
    botOk(
        buildJsonObject {
            put("id", bot.numericId)
            put("is_bot", true)
            put("username", bot.username)
            put("display_name", bot.displayName)
            put("about", bot.about)
            put("description", bot.description)
            put("verified", bot.verified)
            put(
                "capabilities",
                buildJsonObject {
                    put("can_join_groups", bot.canJoinGroups)
                    put("privacy_mode", bot.privacyMode)
                    put("supports_inline_queries", bot.supportsInlineQueries)
                    put("web_app", bot.domain.isNotBlank())
                },
            )
            put("scopes", BotJson.stringArray(bot.scopes))
            put("commands", BotJson.encodeCommands(bot.commands))
        },
    )
}

private suspend fun ApplicationCall.handleGetUpdates(
    auth: BotAuth,
    updates: BotUpdates,
    rateLimits: BotRateLimits,
) {
    val bot = requireBot(auth) ?: return
    if (!requireScope(bot, "getUpdates")) return
    val offset = request.queryParameters["offset"]?.toLongOrNull() ?: 0L
    val limit = request.queryParameters["limit"]?.toIntOrNull() ?: 100
    val timeout = request.queryParameters["timeout"]?.toIntOrNull() ?: 0
    val allowed = request.queryParameters["allowed_updates"]
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()
    if (!rateLimits.allowUpdates(bot.id)) {
        botFailure(429, "too many getUpdates requests", retryAfter = 60)
        return
    }
    val batch = updates.await(bot.id, offset, limit, timeout, allowed)
    botOk(
        buildJsonArray {
            batch.forEach { update ->
                val callbackMessage = update.callbackQuery
                    ?.let { query -> updates.messageFor(bot.id, query.messageId) }
                add(BotJson.update(bot, update, callbackMessage))
            }
        },
    )
}

private suspend fun ApplicationCall.handleSendMessage(
    auth: BotAuth,
    service: BotService,
    idempotency: BotIdempotency,
) {
    val bot = requireBot(auth) ?: return
    if (!requireScope(bot, "sendMessage")) return
    val key = idempotencyKey()
    key?.let { cached -> idempotency.get(bot.id, cached)?.let { botOk(it); return } }
    if (!service.rateLimits.allowSend(bot.id)) {
        botFailure(429, "too many messages per second", retryAfter = 1)
        return
    }
    val request = botBody<SendMessageRequest>() ?: run { botFailure(400, "invalid JSON body"); return }
    try {
        val message = service.sendMessage(bot, request)
        val result = BotJson.encodeMessage(bot, message)
        key?.let { idempotency.put(bot.id, it, result) }
        botOk(result)
    } catch (e: BotApiException) {
        botFailure(e.errorCode, e.message, e.retryAfter)
    }
}

private suspend fun ApplicationCall.handleEditMessageText(
    auth: BotAuth,
    service: BotService,
    idempotency: BotIdempotency,
) {
    val bot = requireBot(auth) ?: return
    if (!requireScope(bot, "editMessageText")) return
    val key = idempotencyKey()
    key?.let { cached -> idempotency.get(bot.id, cached)?.let { botOk(it); return } }
    val request = botBody<EditMessageTextRequest>()
        ?: run { botFailure(400, "invalid JSON body"); return }
    try {
        val message = service.editMessageText(bot, request)
        val result = BotJson.encodeMessage(bot, message)
        key?.let { idempotency.put(bot.id, it, result) }
        botOk(result)
    } catch (e: BotApiException) {
        botFailure(e.errorCode, e.message, e.retryAfter)
    }
}

private suspend fun ApplicationCall.handleDeleteMessage(
    auth: BotAuth,
    service: BotService,
    idempotency: BotIdempotency,
) {
    val bot = requireBot(auth) ?: return
    if (!requireScope(bot, "deleteMessage")) return
    val key = idempotencyKey()
    key?.let { cached -> idempotency.get(bot.id, cached)?.let { botOk(it); return } }
    val request = botBody<DeleteMessageRequest>()
        ?: run { botFailure(400, "invalid JSON body"); return }
    try {
        service.deleteMessage(bot, request)
        val result = kotlinx.serialization.json.JsonPrimitive(true)
        key?.let { idempotency.put(bot.id, it, result) }
        botOk(result)
    } catch (e: BotApiException) {
        botFailure(e.errorCode, e.message, e.retryAfter)
    }
}

private suspend fun ApplicationCall.handleGetCommands(auth: BotAuth, service: BotService) {
    val bot = requireBot(auth) ?: return
    botOk(BotJson.encodeCommands(service.getMyCommands(bot)))
}

private suspend fun ApplicationCall.handleSetCommands(auth: BotAuth, service: BotService) {
    val bot = requireBot(auth) ?: return
    val request = botBody<SetMyCommandsRequest>()
        ?: run { botFailure(400, "invalid JSON body"); return }
    try {
        val commands = service.setMyCommands(bot, request)
        botOk(BotJson.encodeCommands(commands))
    } catch (e: BotApiException) {
        botFailure(e.errorCode, e.message, e.retryAfter)
    }
}

private suspend fun ApplicationCall.handleDeleteCommands(auth: BotAuth, service: BotService) {
    val bot = requireBot(auth) ?: return
    try {
        service.setMyCommands(bot, SetMyCommandsRequest(commands = emptyList()))
        botOk(kotlinx.serialization.json.JsonPrimitive(true))
    } catch (e: BotApiException) {
        botFailure(e.errorCode, e.message, e.retryAfter)
    }
}

private suspend fun ApplicationCall.handleAnswerCallback(auth: BotAuth, service: BotService) {
    val bot = requireBot(auth) ?: return
    val request = botBody<AnswerCallbackQueryRequest>()
        ?: run { botFailure(400, "invalid JSON body"); return }
    try {
        service.answerCallbackQuery(bot, request)
        botOk(kotlinx.serialization.json.JsonPrimitive(true))
    } catch (e: BotApiException) {
        botFailure(e.errorCode, e.message, e.retryAfter)
    }
}
