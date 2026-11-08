package com.tagasay.server.bot

import com.tagasay.server.data.InMemoryStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Бизнес-логика ботов: создание, токены, отправка/редактирование/удаление,
 * `/start`-gate, callbacks, команды. Все проверки прав — здесь, на сервере.
 */
class BotService(
    private val store: BotStore,
    private val users: InMemoryStore,
    private val auth: BotAuth,
    private val updates: BotUpdates,
    val rateLimits: BotRateLimits = BotRateLimits(),
) {
    private val log = LoggerFactory.getLogger(BotService::class.java)
    private val random = SecureRandom()

    private val father = BotFather(store, auth) { owner, name, username ->
        createBot(owner, name, username)
    }

    private data class PendingCallback(val query: BotCallbackQuery, val expiresAt: Long)

    private val pendingCallbacks = ConcurrentHashMap<String, PendingCallback>()

    init {
        ensureBuiltins()
    }

    /** Встроенный @BotFather — обычный бот платформы без внешнего токена. */
    fun ensureBuiltins() {
        val existing = store.botByUsername(BOTFATHER_USERNAME)
        if (existing == null) {
            store.createBot(
                owner = "system",
                username = BOTFATHER_USERNAME,
                displayName = "BotFather",
                builtin = true,
                numericId = 0,
                verified = true,
            )
        } else if (!existing.builtin) {
            // Не отбираем username у реального бота молча (ADR BotFather migration).
            log.warn("reserved username '{}' is occupied by a non-builtin bot", BOTFATHER_USERNAME)
        }
    }

    fun botFather(): Bot? = store.botByUsername(BOTFATHER_USERNAME)

    // ------------------------------------------------------------ management

    fun createBot(owner: String, displayName: String, username: String): BotCreateOutcome {
        val cleanOwner = owner.lowercase()
        if (store.countOwned(cleanOwner) >= MAX_BOTS_PER_OWNER) {
            return BotCreateOutcome(error = "bot limit reached (${MAX_BOTS_PER_OWNER})")
        }
        val name = displayName.trim()
        if (name.length !in 1..64) return BotCreateOutcome(error = "name must be 1..64 characters")
        val clean = username.trimStart('@').lowercase()
        if (clean in RESERVED_USERNAMES) return BotCreateOutcome(error = "username is reserved")
        if (!clean.matches(USERNAME_REGEX)) {
            return BotCreateOutcome(error = "username must match [A-Za-z0-9_]{5,32}")
        }
        if (!clean.endsWith("bot")) return BotCreateOutcome(error = "username must end with 'bot'")
        if (store.botByUsername(clean) != null ||
            users.deviceByUsername(clean) != null ||
            users.roomByUsername(clean) != null
        ) {
            return BotCreateOutcome(error = "username is busy")
        }
        val bot = store.createBot(cleanOwner, clean, name) ?: return BotCreateOutcome(error = "username is busy")
        val token = auth.issueToken(bot)
        store.audit(bot.id, "create")
        return BotCreateOutcome(bot = bot, token = token)
    }

    fun rotateToken(owner: String, bot: Bot): String {
        requireOwner(owner, bot)
        if (bot.status != "active") throw BotApiException(404, "bot not found")
        val token = auth.rotateToken(bot)
        store.audit(bot.id, "revoke")
        return token
    }

    fun deleteBot(owner: String, bot: Bot): Boolean {
        requireOwner(owner, bot)
        if (bot.builtin) throw BotApiException(400, "builtin bot can't be deleted")
        store.deleteBot(bot.id) ?: throw BotApiException(404, "bot not found")
        store.audit(bot.id, "delete")
        return true
    }

    fun updateBot(owner: String, bot: Bot, request: EditBotRequest): Bot {
        requireOwner(owner, bot)
        if (bot.status != "active") throw BotApiException(404, "bot not found")
        val name = request.name?.trim()?.also {
            if (it.length !in 1..64) throw BotApiException(400, "name must be 1..64 characters")
        } ?: bot.displayName
        val about = request.about?.also {
            if (it.length > 120) throw BotApiException(400, "about must be 0..120 characters")
        } ?: bot.about
        val description = request.description?.also {
            if (it.length > 512) throw BotApiException(400, "description must be 0..512 characters")
        } ?: bot.description
        val next = bot.copy(
            displayName = name,
            about = about,
            description = description,
            privacyMode = request.privacy_mode ?: bot.privacyMode,
            canJoinGroups = request.can_join_groups ?: bot.canJoinGroups,
            supportsInlineQueries = request.supports_inline_queries ?: bot.supportsInlineQueries,
        )
        store.updateBot(next)
        return next
    }

    private fun requireOwner(owner: String, bot: Bot) {
        if (!bot.owner.equals(owner.lowercase(), true)) {
            throw BotApiException(403, "bot belongs to another owner")
        }
    }

    // --------------------------------------------------------------- messages

    fun sendMessage(bot: Bot, request: SendMessageRequest, enforceRateLimit: Boolean = true): BotMessage {
        val chatId = normalizeChatId(request.chatId)
        BotPermissions.chatDenied(bot, chatId)?.let { throw BotApiException(403, it) }
        val parsed = parseText(request.text, request.parseMode, request.entities)
        val markup = BotMarkup.validate(request.replyMarkup, bot, privateChat = chatId.startsWith("u:"))
        if (chatId.startsWith("u:")) {
            val username = chatId.removePrefix("u:")
            if (users.deviceByUsername(username) == null) throw BotApiException(404, "chat not found")
            ensureCanWrite(bot, username)
        }
        if (enforceRateLimit && !rateLimits.allowChat(bot.id, chatId)) {
            throw BotApiException(429, "too many messages to this chat", retryAfter = 1)
        }
        val message = BotMessage(
            botId = bot.id,
            chatId = chatId,
            messageId = newMessageId(),
            direction = "out",
            author = bot.username,
            text = parsed.text,
            entities = parsed.entities,
            replyMarkup = markup,
            replyToMessageId = request.replyToMessageId.orEmpty(),
            createdAt = System.currentTimeMillis(),
        )
        return store.putMessage(message)
    }

    fun editMessageText(bot: Bot, request: EditMessageTextRequest): BotMessage {
        val chatId = normalizeChatId(request.chatId)
        val current = store.message(bot.id, chatId, request.messageId)
            ?: throw BotApiException(400, "message to edit not found")
        if (current.direction != "out") throw BotApiException(403, "can edit only bot messages")
        if (current.deletedAt > 0) throw BotApiException(400, "message is deleted")
        val parsed = parseText(request.text, request.parseMode, request.entities)
        val markup = if (request.replyMarkup == null) {
            current.replyMarkup
        } else {
            BotMarkup.validate(request.replyMarkup, bot, privateChat = chatId.startsWith("u:"))
        }
        val updated = current.copy(
            text = parsed.text,
            entities = parsed.entities,
            replyMarkup = markup,
        )
        return store.updateMessage(updated) ?: throw BotApiException(400, "message to edit not found")
    }

    fun deleteMessage(bot: Bot, request: DeleteMessageRequest): Boolean {
        val chatId = normalizeChatId(request.chatId)
        val current = store.message(bot.id, chatId, request.messageId)
            ?: throw BotApiException(400, "message to delete not found")
        if (current.direction != "out") throw BotApiException(403, "can delete only bot messages")
        store.markDeleted(bot.id, chatId, request.messageId)
            ?: throw BotApiException(400, "message to delete not found")
        return true
    }

    fun getMyCommands(bot: Bot): List<BotCommand> = bot.commands

    fun setMyCommands(bot: Bot, request: SetMyCommandsRequest): List<BotCommand> {
        if (request.commands.size > MAX_COMMANDS) {
            throw BotApiException(400, "too many commands (max $MAX_COMMANDS)")
        }
        val commands = request.commands.map { command ->
            val name = command.command.trim().lowercase()
            val description = command.description.trim()
            if (!name.matches(COMMAND_REGEX)) {
                throw BotApiException(400, "invalid command name: $name")
            }
            if (description.length !in 1..256) {
                throw BotApiException(400, "command description must be 1..256 characters")
            }
            BotCommand(name, description)
        }
        if (commands.map { it.command }.toSet().size != commands.size) {
            throw BotApiException(400, "duplicate commands")
        }
        store.updateBot(bot.copy(commands = commands))
        return commands
    }

    private fun ensureCanWrite(bot: Bot, username: String) {
        val state = store.userState(bot.id, username)
        if (state == null || state.startedAt == 0L) {
            throw BotApiException(403, "bot can't initiate conversation (user has not sent /start)")
        }
        if (state.blockedAt > 0) {
            throw BotApiException(403, "user blocked the bot")
        }
    }

    private fun parseText(text: String, mode: String?, entities: List<BotEntity>?): BotText.Parsed {
        val parsed = if (entities != null) {
            if (text.isEmpty()) throw BotApiException(400, "text is empty")
            BotText.Parsed(text, BotText.validateEntities(text, entities))
        } else {
            BotText.parse(text, mode)
        }
        if (parsed.text.isEmpty()) throw BotApiException(400, "text is empty")
        if (parsed.text.length > MAX_TEXT) throw BotApiException(400, "text is too long (max $MAX_TEXT)")
        return parsed
    }

    // ------------------------------------------------------- user ↔ bot

    fun ingestUserMessage(username: String, bot: Bot, request: UserSendToBotRequest): UserSendOutcome {
        if (!request.attachmentId.isNullOrBlank()) {
            throw BotApiException(400, "attachments are not supported in this release")
        }
        val text = request.text.trim()
        if (text.isEmpty()) throw BotApiException(400, "text is empty")
        if (text.length > MAX_TEXT) throw BotApiException(400, "text is too long (max $MAX_TEXT)")
        if (bot.status != "active") throw BotApiException(404, "bot not found")

        val clean = username.lowercase()
        val isStart = text.startsWith("/start", ignoreCase = true)
        val isStop = text.equals("/stop", ignoreCase = true)
        val blocked = store.userState(bot.id, clean)?.blockedAt ?: 0L
        if (blocked > 0 && !isStart) throw BotApiException(403, "bot is blocked by user")

        if (isStart) {
            store.markStarted(bot.id, clean)
        } else {
            store.touchUser(bot.id, clean)
        }
        val inbound = store.putMessage(
            BotMessage(
                botId = bot.id,
                chatId = "u:$clean",
                messageId = newMessageId(),
                direction = "in",
                author = clean,
                text = text,
                replyToMessageId = request.replyTo.orEmpty(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        if (isStop) {
            store.blockUser(bot.id, clean, true)
            return UserSendOutcome(inbound = inbound)
        }
        if (bot.builtin) {
            val replies = father.handle(clean, text)
            val sent = replies.map { reply ->
                sendMessage(
                    bot,
                    SendMessageRequest(
                        chatId = "u:$clean",
                        text = reply.text,
                        replyMarkup = reply.replyMarkup,
                    ),
                    enforceRateLimit = false,
                )
            }
            return UserSendOutcome(inbound = inbound, replies = sent)
        }
        store.appendUpdate(bot.id, "message", message = inbound)
        return UserSendOutcome(inbound = inbound)
    }

    fun userCallback(username: String, bot: Bot, request: UserCallbackRequest): List<BotMessage> {
        val clean = username.lowercase()
        val chatId = "u:$clean"
        val message = store.message(bot.id, chatId, request.messageId)
            ?: throw BotApiException(404, "message not found")
        if (message.direction != "out") throw BotApiException(400, "message has no buttons")
        val data = request.data.trim()
        if (data.isEmpty()) throw BotApiException(400, "callback data is empty")
        if (!callbackData(message.replyMarkup).contains(data)) {
            throw BotApiException(400, "invalid callback data for this message")
        }
        val queryId = request.callbackQueryId.ifBlank { "cq_" + randomHex(10) }
        val query = BotCallbackQuery(
            id = queryId,
            botId = bot.id,
            username = clean,
            chatId = chatId,
            messageId = message.messageId,
            data = data,
            createdAt = System.currentTimeMillis(),
        )
        if (bot.builtin) {
            val replies = father.handleCallback(clean, data)
            store.addCallbackAnswer(bot.id, clean, queryId, "", false)
            return replies.map { reply ->
                sendMessage(
                    bot,
                    SendMessageRequest(
                        chatId = "u:$clean",
                        text = reply.text,
                        replyMarkup = reply.replyMarkup,
                    ),
                    enforceRateLimit = false,
                )
            }
        }
        val now = System.currentTimeMillis()
        pendingCallbacks.entries.removeIf { it.value.expiresAt <= now }
        pendingCallbacks[queryId] = PendingCallback(query, now + CALLBACK_TTL_MS)
        store.appendUpdate(bot.id, "callback_query", callbackQuery = query)
        return emptyList()
    }

    fun answerCallbackQuery(bot: Bot, request: AnswerCallbackQueryRequest): Boolean {
        val id = request.callbackQueryId
        if (id.isBlank()) throw BotApiException(400, "callback_query_id is required")
        val text = request.text?.takeIf { it.isNotBlank() } ?: ""
        if (text.length > 200) throw BotApiException(400, "text is too long (max 200)")
        val pending = pendingCallbacks[id]
            ?: throw BotApiException(400, "query is too old or unknown")
        if (pending.query.botId != bot.id || pending.expiresAt <= System.currentTimeMillis()) {
            pendingCallbacks.remove(id)
            throw BotApiException(400, "query is too old or unknown")
        }
        pendingCallbacks.remove(id)
        store.addCallbackAnswer(bot.id, pending.query.username, id, text, request.showAlert == true)
        return true
    }

    /** Для клиента: карточка бота + состояние диалога. */
    fun userState(botId: String, username: String): BotUserState? = store.userState(botId, username)

    fun blockBot(username: String, bot: Bot, blocked: Boolean): BotUserState {
        val clean = username.lowercase()
        if (bot.builtin && blocked) {
            // BotFather можно скрыть, но не «сломать»: /start всегда разблокирует.
            return store.blockUser(bot.id, clean, true)
        }
        return store.blockUser(bot.id, clean, blocked)
    }

    // -------------------------------------------------------------- helpers

    fun normalizeChatId(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) throw BotApiException(400, "chat_id is required")
        if (value.startsWith("u:") || value.startsWith("g:") || value.startsWith("c:")) return value
        if (value.startsWith("b:")) throw BotApiException(400, "unsupported chat_id")
        if (value.startsWith("@")) return "u:" + value.removePrefix("@").lowercase()
        if (value.matches(Regex("[A-Za-z0-9_]{3,32}"))) return "u:" + value.lowercase()
        throw BotApiException(400, "unsupported chat_id")
    }

    private fun callbackData(markup: JsonElement?): Set<String> {
        val obj = markup as? JsonObject ?: return emptySet()
        val rows = obj["inline_keyboard"] as? JsonArray ?: return emptySet()
        val result = HashSet<String>()
        for (row in rows) {
            val buttons = row as? JsonArray ?: continue
            for (button in buttons) {
                val data = (button as? JsonObject)?.get("callback_data")
                if (data is JsonPrimitive && data.isString) result.add(data.content)
            }
        }
        return result
    }

    private fun newMessageId(): String = "m_" + randomHex(10)

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_BOTS_PER_OWNER = 20
        const val MAX_TEXT = 4096
        const val MAX_COMMANDS = 100
        const val BOTFATHER_USERNAME = "botfather"
        private const val CALLBACK_TTL_MS = 15 * 60 * 1000L

        val USERNAME_REGEX = Regex("[a-z0-9_]{5,32}")
        val COMMAND_REGEX = Regex("[a-z0-9_]{1,32}")
        val RESERVED_USERNAMES = setOf(
            "botfather", "taga", "support", "admin", "service", "system", "security",
        )
    }
}

/** Валидация reply_markup: inline-клавиатуры, web_app только в личке и на своём домене. */
internal object BotMarkup {

    private val buttonDataKeys = setOf("url", "callback_data", "web_app")

    fun validate(markup: JsonElement?, bot: Bot, privateChat: Boolean): JsonElement? {
        if (markup == null || markup is JsonNull) return null
        val obj = markup as? JsonObject ?: throw BotApiException(400, "reply_markup must be an object")
        if (obj.containsKey("inline_keyboard")) {
            val rows = obj["inline_keyboard"] as? JsonArray
                ?: throw BotApiException(400, "inline_keyboard must be an array")
            var total = 0
            for (row in rows) {
                val buttons = row as? JsonArray
                    ?: throw BotApiException(400, "inline_keyboard row must be an array")
                for (button in buttons) {
                    total++
                    validateButton(button, bot, privateChat)
                }
            }
            if (total == 0) throw BotApiException(400, "inline_keyboard is empty")
            if (total > 100) throw BotApiException(400, "too many buttons")
            return obj
        }
        if (obj.containsKey("remove_keyboard") || obj.containsKey("force_reply") || obj.containsKey("keyboard")) {
            // Reply-клавиатуры принимаем и храним; клиентский рендер — P1 ([~]).
            return obj
        }
        throw BotApiException(400, "unsupported reply_markup")
    }

    private fun validateButton(element: JsonElement, bot: Bot, privateChat: Boolean) {
        val button = element as? JsonObject ?: throw BotApiException(400, "button must be an object")
        val text = (button["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BotApiException(400, "button text is required")
        if (text.length !in 1..64) throw BotApiException(400, "button text must be 1..64 characters")
        val present = buttonDataKeys.filter { button.containsKey(it) }
        if (present.size != 1) throw BotApiException(400, "button must have exactly one action")
        when (present.first()) {
            "url" -> {
                val url = (button["url"] as? JsonPrimitive)?.content.orEmpty()
                if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
                    throw BotApiException(400, "button url must be http(s)")
                }
                if (url.length > 2048) throw BotApiException(400, "button url is too long")
            }
            "callback_data" -> {
                val data = (button["callback_data"] as? JsonPrimitive)?.content.orEmpty()
                val size = data.toByteArray(Charsets.UTF_8).size
                if (size !in 1..64) throw BotApiException(400, "callback_data must be 1..64 bytes")
            }
            "web_app" -> {
                if (!privateChat) throw BotApiException(400, "web_app is allowed only in private chats")
                val webApp = button["web_app"] as? JsonObject
                    ?: throw BotApiException(400, "web_app must be an object")
                val url = (webApp["url"] as? JsonPrimitive)?.content.orEmpty()
                validateWebAppUrl(url, bot)
            }
        }
    }

    fun validateWebAppUrl(url: String, bot: Bot) {
        if (!url.startsWith("https://", true)) throw BotApiException(400, "web_app url must be HTTPS")
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw BotApiException(400, "invalid web_app url")
        if (uri.userInfo != null) throw BotApiException(400, "web_app url must not contain userinfo")
        val host = uri.host?.lowercase() ?: throw BotApiException(400, "invalid web_app host")
        val domain = bot.domain.lowercase()
        if (domain.isEmpty()) throw BotApiException(400, "web_app domain is not configured in BotFather")
        if (host != domain && !host.endsWith(".$domain")) {
            throw BotApiException(400, "web_app domain is not registered for this bot")
        }
    }
}
