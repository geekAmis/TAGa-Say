package com.tagasay.server.bot

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Bot API v1.2.0: `/bot/v1/…`.
 *
 * Реализованы: getMe, getUpdates, sendMessage, edit/delete, команды, callback,
 * файлы (sendPhoto/Document/Voice/Video/Audio, getFile, подписанное скачивание)
 * и webhook (set/delete/getWebhookInfo). Остальное — `400 unsupported`.
 */
fun Route.botRoutes(
    auth: BotAuth,
    service: BotService,
    updates: BotUpdates,
    store: BotStore,
    webhook: BotWebhookDelivery,
    publicBaseUrl: String,
    idempotency: BotIdempotency = BotIdempotency(),
) {
    route("/bot/v1") {
        get("/getMe") { call.handleGetMe(auth, service) }
        post("/getMe") { call.handleGetMe(auth, service) }

        get("/getUpdates") { call.handleGetUpdates(auth, updates, service.rateLimits, store) }
        post("/getUpdates") { call.handleGetUpdates(auth, updates, service.rateLimits, store) }

        post("/sendMessage") { call.handleSendMessage(auth, service, idempotency) }
        post("/editMessageText") { call.handleEditMessageText(auth, service, idempotency) }
        post("/deleteMessage") { call.handleDeleteMessage(auth, service, idempotency) }

        post("/sendPhoto") { call.handleSendFile(auth, service, "photo") }
        post("/sendDocument") { call.handleSendFile(auth, service, "document") }
        post("/sendVoice") { call.handleSendFile(auth, service, "voice") }
        post("/sendVideo") { call.handleSendFile(auth, service, "video") }
        post("/sendAudio") { call.handleSendFile(auth, service, "audio") }

        get("/getFile") { call.handleGetFile(auth, service, publicBaseUrl) }
        post("/getFile") { call.handleGetFile(auth, service, publicBaseUrl) }
        get("/file/{fileId}") { call.handleFileDownload(service) }

        post("/setWebhook") { call.handleSetWebhook(auth, service, webhook, store) }
        post("/deleteWebhook") { call.handleDeleteWebhook(bot = call.requireBot(auth) ?: return@post, store = store) }
        get("/getWebhookInfo") { call.handleWebhookInfo(auth, store) }
        post("/getWebhookInfo") { call.handleWebhookInfo(auth, store) }

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
    store: BotStore,
) {
    val bot = requireBot(auth) ?: return
    if (!requireScope(bot, "getUpdates")) return
    if (store.webhook(bot.id) != null) {
        botFailure(409, "Conflict: webhook is active; deleteWebhook before getUpdates")
        return
    }
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

// ----------------------------------------------------------- files & webhook

private suspend fun ApplicationCall.handleSendFile(
    auth: BotAuth,
    service: BotService,
    kind: String,
) {
    val bot = requireBot(auth) ?: return
    if (!bot.scopes.contains(BotScopes.FILES_WRITE)) {
        botFailure(403, "not enough rights: ${BotScopes.FILES_WRITE}")
        return
    }
    if (!service.rateLimits.allowSend(bot.id)) {
        botFailure(429, "too many messages per second", retryAfter = 1)
        return
    }
    try {
        var bytes: ByteArray? = null
        var originalName = kind
        var chatId = ""
        var caption = ""
        var parseMode: String? = null
        var replyTo: String? = null
        var replyMarkup: JsonElement? = null
        var fileId: String? = null

        if (request.contentType().match(ContentType.MultiPart.FormData)) {
            val fields = HashMap<String, String>()
            val limit = ((service.mediaStore()?.maxFileBytes() ?: BotMediaStore.MAX_FILE_BYTES) + 1)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val multipart = receiveMultipart()
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> fields[part.name.orEmpty()] = part.value
                    is PartData.FileItem -> {
                        val data = part.provider().readRemaining(limit.toLong()).readByteArray()
                        val name = part.name.orEmpty()
                        if (bytes == null || name == kind || name == "file") {
                            bytes = data
                            originalName = part.originalFileName?.takeIf { it.isNotBlank() } ?: kind
                        }
                    }
                    else -> Unit
                }
                part.dispose()
            }
            chatId = fields["chat_id"].orEmpty()
            caption = fields["caption"].orEmpty()
            parseMode = fields["parse_mode"]
            replyTo = fields["reply_to_message_id"]
            fileId = fields["file_id"] ?: fields[kind]?.takeIf { it.startsWith("file_") }
            fields["reply_markup"]?.takeIf { it.isNotBlank() }?.let {
                replyMarkup = runCatching { BotJson.json.parseToJsonElement(it) }.getOrElse {
                    throw BotApiException(400, "invalid reply_markup")
                }
            }
        } else {
            val body = botBody<SendFileRequest>()
                ?: throw BotApiException(400, "invalid JSON body")
            chatId = body.chatId
            if (chatId.isBlank()) throw BotApiException(400, "chat_id is required")
            caption = body.caption
            parseMode = body.parseMode
            replyTo = body.replyToMessageId
            replyMarkup = body.replyMarkup
            fileId = body.fileId.ifBlank { null }
        }

        if (chainContains(bytes, fileId).not()) {
            throw BotApiException(400, "file or file_id is required")
        }
        val max = service.mediaStore()?.maxFileBytes() ?: BotMediaStore.MAX_FILE_BYTES
        if (bytes != null && bytes!!.size.toLong() > max) {
            throw BotApiException(413, "file is too large")
        }
        val message = service.sendFile(
            bot = bot,
            chatIdRaw = chatId,
            kind = kind,
            bytes = bytes,
            originalFileName = originalName,
            existingFileId = fileId,
            caption = caption,
            parseMode = parseMode,
            replyMarkup = replyMarkup,
            replyToMessageId = replyTo,
        )
        botOk(BotJson.encodeMessage(bot, message))
    } catch (e: BotApiException) {
        botFailure(e.errorCode, e.message, e.retryAfter)
    }
}

private fun chainContains(bytes: ByteArray?, fileId: String?): Boolean =
    bytes != null || !fileId.isNullOrBlank()

private suspend fun ApplicationCall.handleGetFile(
    auth: BotAuth,
    service: BotService,
    publicBaseUrl: String,
) {
    val bot = requireBot(auth) ?: return
    if (!requireScope(bot, "getFile")) return
    val fromQuery = request.queryParameters["file_id"]
    val fileId = fromQuery ?: runCatching {
        receive<Map<String, String>>()["file_id"]
    }.getOrNull()
    if (fileId.isNullOrBlank()) {
        botFailure(400, "file_id is required")
        return
    }
    try {
        val file = service.getFile(bot, fileId)
        botOk(
            buildJsonObject {
                put("file_id", file.fileId)
                put("file_unique_id", file.fileUniqueId)
                put("file_size", file.size)
                put("mime_type", file.mimeType)
                put("file_name", file.fileName)
                put("kind", file.kind)
                put("file_url", service.fileUrl(publicBaseUrl, file))
            },
        )
    } catch (e: BotApiException) {
        botFailure(e.errorCode, e.message, e.retryAfter)
    }
}

private suspend fun ApplicationCall.handleFileDownload(service: BotService) {
    val fileId = parameters["fileId"].orEmpty()
    val expires = request.queryParameters["expires"]?.toLongOrNull() ?: 0L
    val signature = request.queryParameters["sig"].orEmpty()
    val media = service.mediaStore()
    if (media == null) {
        botFailure(404, "files are not enabled")
        return
    }
    val (path, file) = media.openSigned(fileId, expires, signature)
        ?: run {
            botFailure(403, "invalid or expired file link")
            return
        }
    val contentType = runCatching { ContentType.parse(media.safeContentType(file.mimeType)) }
        .getOrElse { ContentType.Application.OctetStream }
    val name = file.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "file" }
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
    response.header("X-Content-Type-Options", "nosniff")
    response.header(HttpHeaders.CacheControl, "private, max-age=300")
    respondBytes(path.readBytes(), contentType)
}

private suspend fun ApplicationCall.handleSetWebhook(
    auth: BotAuth,
    service: BotService,
    webhook: BotWebhookDelivery,
    store: BotStore,
) {
    val bot = requireBot(auth) ?: return
    val requestBody = botBody<SetWebhookRequest>()
        ?: run { botFailure(400, "invalid JSON body"); return }
    try {
        val secret = requestBody.secretToken
        if (secret != null && secret.length !in 1..256) {
            throw BotApiException(400, "secret_token must be 1..256 characters")
        }
        if (requestBody.maxConnections !in 1..10) {
            throw BotApiException(400, "max_connections must be 1..10")
        }
        val allowed = requestBody.allowedUpdates.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val unknown = allowed.filterNot { it in WEBHOOK_UPDATE_TYPES }
        if (unknown.isNotEmpty()) {
            throw BotApiException(400, "unknown allowed_updates: ${unknown.joinToString(",")}")
        }
        val uri = webhook.validateUrl(requestBody.url)
        if (requestBody.dropPendingUpdates) store.dropPending(bot.id)
        store.setWebhook(
            BotWebhook(
                botId = bot.id,
                url = uri.toString(),
                secretCiphertext = secret?.let { auth.encryptSecret(it) }.orEmpty(),
                allowedUpdates = allowed,
                maxConnections = requestBody.maxConnections,
                createdAt = System.currentTimeMillis(),
            ),
        )
        webhook.start()
        botOk(kotlinx.serialization.json.JsonPrimitive(true))
    } catch (e: BotApiException) {
        botFailure(e.errorCode, e.message, e.retryAfter)
    }
}

private suspend fun ApplicationCall.handleDeleteWebhook(bot: Bot, store: BotStore) {
    val requestBody = runCatching { receive<DeleteWebhookRequest>() }.getOrNull()
        ?: DeleteWebhookRequest()
    if (requestBody.dropPendingUpdates) store.dropPending(bot.id)
    store.deleteWebhook(bot.id)
    botOk(kotlinx.serialization.json.JsonPrimitive(true))
}

private suspend fun ApplicationCall.handleWebhookInfo(auth: BotAuth, store: BotStore) {
    val bot = requireBot(auth) ?: return
    val hook = store.webhook(bot.id)
    botOk(
        buildJsonObject {
            put("url", hook?.url ?: "")
            put("has_custom_certificate", false)
            put("pending_update_count", store.pendingUpdateCount(bot.id))
            put("max_connections", hook?.maxConnections ?: 1)
            put("allowed_updates", BotJson.stringArray(hook?.allowedUpdates.orEmpty()))
            put("last_error_message", hook?.lastError ?: "")
            put("last_error_date", hook?.lastErrorAt ?: 0)
        },
    )
}

private val WEBHOOK_UPDATE_TYPES = setOf(
    "message", "edited_message", "channel_post", "edited_channel_post",
    "callback_query", "chat_member", "my_chat_member", "message_reaction",
)
