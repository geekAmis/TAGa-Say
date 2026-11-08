package com.tagasay.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Модели платформы ботов (сервер 1.2.0, клиент 1.4.0).
 *
 * Бот-чаты принципиально **не E2EE**: сервер видит текст, бот — только
 * адресованные ему сообщения. Поля Bot API используют snake_case, поэтому
 * здесь явные @SerialName. Клиентские поля (warningShown) хранятся только
 * в локальном vault и на сервер не уходят.
 */

@Serializable
data class BotCommandInfo(
    val command: String = "",
    val description: String = "",
)

@Serializable
data class BotInfo(
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    val about: String = "",
    val description: String = "",
    val verified: Boolean = false,
    val builtin: Boolean = false,
    val status: String = "active",
    @SerialName("privacy_mode") val privacyMode: Boolean = true,
    @SerialName("can_join_groups") val canJoinGroups: Boolean = true,
    @SerialName("supports_inline_queries") val supportsInlineQueries: Boolean = false,
    val domain: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val commands: List<BotCommandInfo> = emptyList(),
    val started: Boolean = false,
    val blocked: Boolean = false,
    // Локальные поля клиента (в ответах сервера их нет).
    val warningShown: Boolean = false,
) {
    val deleted: Boolean get() = status.equals("deleted", ignoreCase = true)

    val title: String
        get() = displayName.ifBlank { "@$username" }
}

/** Сущность форматирования Bot API. Смещения — в UTF-16. */
@Serializable
data class BotEntity(
    val type: String = "",
    val offset: Int = 0,
    val length: Int = 0,
    val url: String = "",
) {
    val end: Int get() = offset + length
}

@Serializable
data class BotWebApp(val url: String = "")

@Serializable
data class InlineKeyboardButton(
    val text: String = "",
    val url: String = "",
    @SerialName("callback_data") val callbackData: String = "",
    @SerialName("web_app") val webApp: BotWebApp? = null,
) {
    val isCallback: Boolean get() = callbackData.isNotBlank()
    val isUrl: Boolean get() = url.isNotBlank()
    val isWebApp: Boolean get() = webApp != null
}

@Serializable
data class BotReplyMarkup(
    @SerialName("inline_keyboard")
    val inlineKeyboard: List<List<InlineKeyboardButton>> = emptyList(),
)

/** Сообщение бота из `/v1/bots/inbox`. */
@Serializable
data class BotMessage(
    @SerialName("message_id") val messageId: String = "",
    @SerialName("chat_id") val chatId: String = "",
    @SerialName("bot_username") val botUsername: String = "",
    val from: String = "",
    @SerialName("is_bot") val isBot: Boolean = true,
    val text: String = "",
    val entities: List<BotEntity> = emptyList(),
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
    @SerialName("reply_to") val replyTo: String = "",
    val date: Long = 0,
    @SerialName("edited_at") val editedAt: Long = 0,
    @SerialName("deleted_at") val deletedAt: Long = 0,
) {
    val keyboard: List<List<InlineKeyboardButton>> get() = parseInlineKeyboard(replyMarkup)
    val timestampMs: Long get() = BotTime.toMillis(date)
}

/** Событие inbox: bot_message / bot_message_edited / bot_messages_deleted / callback_answer. */
@Serializable
data class BotInboxEvent(
    @SerialName("event_id") val eventId: Long = 0,
    val type: String = "",
    @SerialName("chat_id") val chatId: String = "",
    val message: BotMessage? = null,
    val text: String = "",
    @SerialName("show_alert") val showAlert: Boolean = false,
    @SerialName("message_ids") val messageIds: List<String> = emptyList(),
    @SerialName("bot_username") val botUsername: String = "",
) {
    companion object {
        const val TYPE_MESSAGE = "bot_message"
        const val TYPE_EDITED = "bot_message_edited"
        const val TYPE_DELETED = "bot_messages_deleted"
        const val TYPE_CALLBACK_ANSWER = "callback_answer"
    }
}

// ---------------------------------------------------------------- Bot API DTO

@Serializable
data class BotUser(
    val id: String = "",
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("is_bot") val isBot: Boolean = false,
)

@Serializable
data class BotApiChat(
    val id: String = "",
    val type: String = "private",
    val title: String? = null,
    val username: String? = null,
)

@Serializable
data class BotApiMessage(
    @SerialName("message_id") val messageId: String = "",
    val date: Long = 0,
    val chat: BotApiChat = BotApiChat(),
    val from: BotUser = BotUser(),
    val text: String? = null,
    val entities: List<BotEntity> = emptyList(),
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
    @SerialName("reply_to_message_id") val replyToMessageId: String? = null,
    @SerialName("edit_date") val editDate: Long? = null,
)

@Serializable
data class BotCallbackQuery(
    val id: String = "",
    val from: BotUser = BotUser(),
    @SerialName("chat_instance") val chatInstance: String = "",
    val data: String = "",
    val message: BotApiMessage? = null,
)

/** Update из Bot API (getUpdates). Клиентски не используется, но контракт держим. */
@Serializable
data class BotUpdate(
    @SerialName("update_id") val updateId: Long = 0,
    val message: BotApiMessage? = null,
    @SerialName("callback_query") val callbackQuery: BotCallbackQuery? = null,
)

// ------------------------------------------------------------- requests / rsp

@Serializable
data class BotSendRequest(
    val text: String,
    @SerialName("client_msg_id") val clientMsgId: String = "",
    @SerialName("chat_id") val chatId: String = "",
    @SerialName("reply_to") val replyTo: String? = null,
)

@Serializable
data class BotSendResponse(
    val accepted: Boolean = false,
    @SerialName("message_id") val messageId: String = "",
    @SerialName("client_msg_id") val clientMsgId: String = "",
    @SerialName("reply_message_ids") val replyMessageIds: List<String> = emptyList(),
)

@Serializable
data class CreateBotRequest(val name: String, val username: String)

@Serializable
data class CreateBotResponse(val bot: BotInfo = BotInfo(), val token: String = "")

@Serializable
data class BotTokenResponse(val token: String = "")

@Serializable
data class BotUpdateRequest(
    @SerialName("display_name") val displayName: String? = null,
    val about: String? = null,
    val description: String? = null,
    @SerialName("privacy_mode") val privacyMode: Boolean? = null,
    @SerialName("can_join_groups") val canJoinGroups: Boolean? = null,
    @SerialName("supports_inline_queries") val supportsInlineQueries: Boolean? = null,
    val domain: String? = null,
)

@Serializable
data class BotCallbackRequest(
    @SerialName("message_id") val messageId: String,
    val data: String,
    @SerialName("callback_query_id") val callbackQueryId: String = "",
)

// ------------------------------------------------------------------ helpers

object BotTime {
    /** Bot API отдаёт секунды; inbox — тоже секунды, но встречаются и мс. */
    fun toMillis(value: Long): Long = when {
        value <= 0 -> System.currentTimeMillis()
        value < 100_000_000_000L -> value * 1_000L
        else -> value
    }
}

/** Inline-клавиатура может прийти как {inline_keyboard:[[…]]} или сразу как [[…]]. */
fun parseInlineKeyboard(raw: JsonElement?): List<List<InlineKeyboardButton>> {
    if (raw == null) return emptyList()
    return runCatching {
        when (raw) {
            is JsonArray -> decodeKeyboardRows(raw)
            is JsonObject -> {
                val rows = raw["inline_keyboard"] as? JsonArray ?: return emptyList()
                decodeKeyboardRows(rows)
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}

private fun decodeKeyboardRows(rows: JsonArray): List<List<InlineKeyboardButton>> =
    rows.map { row ->
        row.jsonArray.mapNotNull { cell -> cell.toKeyboardButton() }
    }.filter { it.isNotEmpty() }

private fun JsonElement.toKeyboardButton(): InlineKeyboardButton? = runCatching {
    val obj = jsonObject
    val text = obj["text"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
    if (text.isBlank()) return@runCatching null
    val webApp = (obj["web_app"] as? JsonObject)?.let { wa ->
        BotWebApp(url = wa["url"]?.jsonPrimitive?.contentOrNullSafe().orEmpty())
    }
    InlineKeyboardButton(
        text = text,
        url = obj["url"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
        callbackData = obj["callback_data"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
        webApp = webApp,
    )
}.getOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()

/**
 * Проверка, нужно ли принимать входящее сообщение бота: пустые id и
 * tombstone-сообщения (deleted_at > 0) отбрасываются, повторный message_id
 * (redelivery inbox) — дубликат.
 */
fun shouldAcceptBotMessage(
    messageId: String,
    deletedAt: Long,
    existingIds: Set<String>,
): Boolean = messageId.isNotBlank() && deletedAt <= 0 && messageId !in existingIds

/** Оставляет только сущности, целиком попадающие в текст (UTF-16). */
fun validBotEntities(entities: List<BotEntity>, textLength: Int): List<BotEntity> =
    entities.filter { it.offset >= 0 && it.length > 0 && it.end <= textLength && it.end > it.offset }
