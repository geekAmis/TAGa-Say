package com.tagasay.server.bot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SendMessageRequest(
    @SerialName("chat_id") val chatId: String = "",
    @SerialName("text") val text: String = "",
    @SerialName("parse_mode") val parseMode: String? = null,
    @SerialName("disable_web_page_preview") val disableWebPagePreview: Boolean? = null,
    @SerialName("reply_to_message_id") val replyToMessageId: String? = null,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
    @SerialName("entities") val entities: List<BotEntity>? = null,
    @SerialName("disable_notification") val disableNotification: Boolean? = null,
)

@Serializable
data class EditMessageTextRequest(
    @SerialName("chat_id") val chatId: String = "",
    @SerialName("message_id") val messageId: String = "",
    @SerialName("text") val text: String = "",
    @SerialName("parse_mode") val parseMode: String? = null,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
    @SerialName("entities") val entities: List<BotEntity>? = null,
)

@Serializable
data class DeleteMessageRequest(
    @SerialName("chat_id") val chatId: String = "",
    @SerialName("message_id") val messageId: String = "",
)

@Serializable
data class SetMyCommandsRequest(
    val commands: List<BotCommand> = emptyList(),
    val scope: JsonElement? = null,
    @SerialName("language_code") val languageCode: String? = null,
)

@Serializable
data class AnswerCallbackQueryRequest(
    @SerialName("callback_query_id") val callbackQueryId: String = "",
    val text: String? = null,
    @SerialName("show_alert") val showAlert: Boolean? = null,
    val url: String? = null,
    @SerialName("cache_time") val cacheTime: Int? = null,
)

@Serializable
data class UserSendToBotRequest(
    val text: String = "",
    @SerialName("client_msg_id") val clientMsgId: String = "",
    @SerialName("chat_id") val chatId: String = "",
    @SerialName("attachment_id") val attachmentId: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
)

@Serializable
data class UserCallbackRequest(
    @SerialName("callback_query_id") val callbackQueryId: String = "",
    @SerialName("message_id") val messageId: String = "",
    val data: String = "",
)

@Serializable
data class CreateBotRequest(
    val name: String = "",
    val username: String = "",
)

@Serializable
data class EditBotRequest(
    val name: String? = null,
    val about: String? = null,
    val description: String? = null,
    val privacy_mode: Boolean? = null,
    val can_join_groups: Boolean? = null,
    val supports_inline_queries: Boolean? = null,
)

@Serializable
data class UserSendOutcome(
    val inbound: BotMessage,
    val replies: List<BotMessage> = emptyList(),
)
