package com.tagasay.server.bot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Модели платформы ботов. Хранятся в отдельном BotStore (bots.json +
 * bot-updates.jsonl + bot-chats.jsonl), не в пользовательском снапшоте.
 *
 * Токены: см. ADR-005. В снапшоте лежат только HMAC и AES-GCM шифртекст,
 * открытый токен существует лишь в ответе BotFather/владельцу.
 */

object BotScopes {
    const val UPDATES_READ = "updates:read"
    const val MESSAGES_SEND = "messages:send"
    const val MESSAGES_EDIT = "messages:edit"
    const val MESSAGES_DELETE = "messages:delete"
    const val FILES_READ = "files:read"
    const val FILES_WRITE = "files:write"
    const val CHATS_READ = "chats:read"
    const val CHATS_MANAGE = "chats:manage"
    const val WEBAPP = "webapp"
    const val INLINE = "inline"

    val DEFAULT = listOf(
        UPDATES_READ,
        MESSAGES_SEND,
        MESSAGES_EDIT,
        MESSAGES_DELETE,
        FILES_READ,
        FILES_WRITE,
        CHATS_READ,
    )

    val ALL = DEFAULT + listOf(CHATS_MANAGE, WEBAPP, INLINE)
}

@Serializable
data class BotCommand(
    val command: String,
    val description: String,
)

@Serializable
data class Bot(
    val id: String,
    val numericId: Long,
    val username: String,
    val owner: String,
    val displayName: String,
    val about: String = "",
    val description: String = "",
    val verified: Boolean = false,
    val builtin: Boolean = false,
    val createdAt: Long = 0,
    /** active | deleted */
    val status: String = "active",
    val privacyMode: Boolean = true,
    val canJoinGroups: Boolean = true,
    val supportsInlineQueries: Boolean = false,
    val inlinePlaceholder: String = "",
    val domain: String = "",
    val scopes: List<String> = BotScopes.DEFAULT,
    val commands: List<BotCommand> = emptyList(),
    val deletedAt: Long = 0,
)

@Serializable
data class BotToken(
    val id: String,
    val botId: String,
    /** HMAC-SHA256(pepper, token), base64url. */
    val tokenHash: String,
    /** AES-256-GCM(encKey, token), base64url(iv||ciphertext). */
    val tokenCiphertext: String,
    val createdAt: Long,
    val revokedAt: Long = 0,
    val lastUsedAt: Long = 0,
)

@Serializable
data class BotUserState(
    val botId: String,
    val username: String,
    val startedAt: Long = 0,
    val blockedAt: Long = 0,
    val lastInteraction: Long = 0,
)

@Serializable
data class BotEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String = "",
)

@Serializable
data class BotMessage(
    val botId: String,
    val chatId: String,
    val messageId: String,
    /** in — пользователь боту, out — бот пользователю. */
    val direction: String,
    val author: String,
    val text: String = "",
    val entities: List<BotEntity> = emptyList(),
    val replyMarkup: JsonElement? = null,
    val replyToMessageId: String = "",
    val createdAt: Long = 0,
    val editedAt: Long = 0,
    val deletedAt: Long = 0,
    /** Монотонный курсор для /v1/bots/inbox. */
    val seq: Long = 0,
)

@Serializable
data class BotCallbackQuery(
    val id: String,
    val botId: String,
    val username: String,
    val chatId: String,
    val messageId: String,
    val data: String,
    val createdAt: Long,
    val answeredAt: Long = 0,
)

@Serializable
data class BotCallbackAnswer(
    val callbackQueryId: String,
    val botId: String,
    val user: String,
    val text: String = "",
    val showAlert: Boolean = false,
    val createdAt: Long,
    val seq: Long = 0,
)

@Serializable
data class BotUpdate(
    val botId: String,
    val updateId: Long,
    /** message | callback_query */
    val type: String,
    val message: BotMessage? = null,
    val callbackQuery: BotCallbackQuery? = null,
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
)

@Serializable
data class BotWebhook(
    val botId: String,
    val url: String,
    val secretCiphertext: String = "",
    val allowedUpdates: List<String> = emptyList(),
    val maxConnections: Int = 1,
    val lastError: String = "",
    val failureCount: Int = 0,
    val nextAttemptAt: Long = 0,
)

@Serializable
data class BotFatherSession(
    val owner: String,
    /** idle | await_new_name | await_new_username | await_set_name | ... */
    val step: String = "idle",
    val botId: String = "",
    val pendingAction: String = "",
    val draftName: String = "",
    val updatedAt: Long = 0,
)

@Serializable
data class BotAudit(
    val botId: String,
    val action: String,
    val at: Long = 0,
)

/** Снимок bots.json. Пользовательский снапшот не трогаем. */
@Serializable
data class BotSnapshot(
    val nextNumericId: Long = 1,
    val nextSeq: Long = 1,
    val counters: Map<String, Long> = emptyMap(),
    val bots: List<Bot> = emptyList(),
    val tokens: List<BotToken> = emptyList(),
    val userStates: List<BotUserState> = emptyList(),
    val sessions: List<BotFatherSession> = emptyList(),
    val offsets: Map<String, Long> = emptyMap(),
    val answers: List<BotCallbackAnswer> = emptyList(),
    val audit: List<BotAudit> = emptyList(),
)

/** Событие append-only bot-chats.jsonl. */
@Serializable
data class BotChatEvent(
    val type: String,
    val message: BotMessage? = null,
    val answer: BotCallbackAnswer? = null,
)

data class BotFatherReply(
    val text: String,
    val replyMarkup: JsonElement? = null,
)

data class BotCreateOutcome(
    val bot: Bot? = null,
    val token: String? = null,
    val error: String? = null,
)

class BotApiException(
    val errorCode: Int,
    override val message: String,
    val retryAfter: Int? = null,
) : Exception(message)

// ------------------------------------------------------------------ Bot API DTO

@Serializable
data class ApiChat(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("title") val title: String? = null,
    @SerialName("username") val username: String? = null,
)

@Serializable
data class ApiUser(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("is_bot") val isBot: Boolean = false,
)

@Serializable
data class ApiMessage(
    @SerialName("message_id") val messageId: String,
    @SerialName("date") val date: Long,
    @SerialName("chat") val chat: ApiChat,
    @SerialName("from") val from: ApiUser,
    @SerialName("text") val text: String? = null,
    @SerialName("entities") val entities: List<BotEntity> = emptyList(),
    @SerialName("reply_to_message_id") val replyToMessageId: String? = null,
    @SerialName("edit_date") val editDate: Long? = null,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
)

@Serializable
data class ApiCallbackQuery(
    @SerialName("id") val id: String,
    @SerialName("from") val from: ApiUser,
    @SerialName("chat_instance") val chatInstance: String,
    @SerialName("message") val message: ApiMessage? = null,
    @SerialName("data") val data: String = "",
)

/** Событие пользовательского long-poll /v1/bots/inbox. */
@Serializable
data class InboxEventDto(
    @SerialName("event_id") val eventId: Long,
    @SerialName("type") val type: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("message") val message: InboxMessageDto? = null,
    @SerialName("callback_query_id") val callbackQueryId: String? = null,
    @SerialName("text") val text: String? = null,
    @SerialName("show_alert") val showAlert: Boolean? = null,
)

@Serializable
data class InboxMessageDto(
    @SerialName("message_id") val messageId: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("bot_username") val botUsername: String,
    @SerialName("from") val from: String,
    @SerialName("is_bot") val isBot: Boolean,
    @SerialName("text") val text: String = "",
    @SerialName("entities") val entities: List<BotEntity> = emptyList(),
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
    @SerialName("reply_to") val replyTo: String = "",
    @SerialName("date") val date: Long = 0,
    @SerialName("edited_at") val editedAt: Long = 0,
    @SerialName("deleted_at") val deletedAt: Long = 0,
)

@Serializable
data class InboxEntry(
    val seq: Long,
    val event: InboxEventDto,
)
