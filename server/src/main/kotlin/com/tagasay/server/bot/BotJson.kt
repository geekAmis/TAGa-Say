package com.tagasay.server.bot

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Единое JSON-представление Bot API и пользовательского API. */
internal object BotJson {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun chatFor(chatId: String): ApiChat {
        val type = when {
            chatId.startsWith("g:") -> "group"
            chatId.startsWith("c:") -> "channel"
            else -> "private"
        }
        val name = chatId.substringAfter(':', "")
        return ApiChat(
            id = chatId,
            type = type,
            username = name.takeIf { type == "private" },
        )
    }

    fun apiMessage(bot: Bot, message: BotMessage): ApiMessage {
        val from = if (message.direction == "out") {
            ApiUser(
                id = "b:${bot.username}",
                username = bot.username,
                displayName = bot.displayName,
                isBot = true,
            )
        } else {
            ApiUser(
                id = "u:${message.author}",
                username = message.author,
                displayName = message.author,
                isBot = false,
            )
        }
        val file = if (message.fileId.isBlank()) {
            null
        } else {
            ApiFile(
                fileId = message.fileId,
                fileUniqueId = message.fileUniqueId,
                fileSize = message.fileSize,
                mimeType = message.mimeType,
                fileName = message.fileName,
            )
        }
        return ApiMessage(
            messageId = message.messageId,
            date = message.createdAt / 1000,
            chat = chatFor(message.chatId),
            from = from,
            text = message.text.takeIf { file == null },
            caption = message.text.takeIf { file != null && it.isNotEmpty() },
            entities = message.entities,
            replyToMessageId = message.replyToMessageId.takeIf { it.isNotBlank() },
            editDate = message.editedAt.takeIf { it > 0 }?.div(1000),
            replyMarkup = message.replyMarkup,
            photo = if (message.kind == "photo" && file != null) listOf(file) else null,
            document = if (message.kind == "document" && file != null) file else null,
            voice = if (message.kind == "voice" && file != null) file else null,
            video = if (message.kind == "video" && file != null) file else null,
            audio = if (message.kind == "audio" && file != null) file else null,
        )
    }

    fun encodeMessage(bot: Bot, message: BotMessage): JsonElement =
        json.encodeToJsonElement(ApiMessage.serializer(), apiMessage(bot, message))

    fun update(bot: Bot, update: BotUpdate, callbackMessage: BotMessage? = null): JsonElement =
        buildJsonObject {
            put("update_id", update.updateId)
            when (update.type) {
                "message" -> update.message?.let { put("message", encodeMessage(bot, it)) }
                "channel_post" -> update.message?.let { put("channel_post", encodeMessage(bot, it)) }
                "my_chat_member" -> update.chatMember?.let { member -> put("my_chat_member", chatMemberJson(member, bot)) }
                "callback_query" -> update.callbackQuery?.let { query ->
                    put(
                        "callback_query",
                        buildJsonObject {
                            put("id", query.id)
                            put(
                                "from",
                                json.encodeToJsonElement(
                                    ApiUser.serializer(),
                                    ApiUser(
                                        id = "u:${query.username}",
                                        username = query.username,
                                        displayName = query.username,
                                        isBot = false,
                                    ),
                                ),
                            )
                            put("chat_instance", query.chatId)
                            put("data", query.data)
                            callbackMessage?.let { put("message", encodeMessage(bot, it)) }
                        },
                    )
                }
            }
        }

    fun encodeCommands(commands: List<BotCommand>): JsonElement =
        json.encodeToJsonElement(ListSerializer(BotCommand.serializer()), commands)

    private fun chatMemberJson(member: BotChatMember, bot: Bot): JsonElement = buildJsonObject {
        put("chat", json.encodeToJsonElement(ApiChat.serializer(), chatFor(member.chatId)))
        put(
            "from",
            json.encodeToJsonElement(
                ApiUser.serializer(),
                ApiUser(
                    id = "u:${member.actor}",
                    username = member.actor,
                    displayName = member.actor,
                    isBot = false,
                ),
            ),
        )
        put("date", member.createdAt / 1000)
        put("old_chat_member", memberState(member.oldStatus, bot))
        put("new_chat_member", memberState(member.newStatus, bot))
    }

    private fun memberState(status: String, bot: Bot): JsonElement = buildJsonObject {
        put("status", status)
        put(
            "user",
            json.encodeToJsonElement(
                ApiUser.serializer(),
                ApiUser(
                    id = "b:${bot.username}",
                    username = bot.username,
                    displayName = bot.displayName,
                    isBot = true,
                ),
            ),
        )
    }

    fun card(bot: Bot, state: BotUserState?): JsonElement = buildJsonObject {
        put("username", bot.username)
        put("display_name", bot.displayName)
        put("about", bot.about)
        put("description", bot.description)
        put("verified", bot.verified)
        put("builtin", bot.builtin)
        put("status", bot.status)
        put("privacy_mode", bot.privacyMode)
        put("can_join_groups", bot.canJoinGroups)
        put("supports_inline_queries", bot.supportsInlineQueries)
        put("domain", bot.domain)
        put("created_at", bot.createdAt)
        put("commands", encodeCommands(bot.commands))
        put("started", (state?.startedAt ?: 0) > 0)
        put("blocked", (state?.blockedAt ?: 0) > 0)
    }

    fun botSummary(bot: Bot): JsonElement = buildJsonObject {
        put("username", bot.username)
        put("display_name", bot.displayName)
        put("about", bot.about)
        put("verified", bot.verified)
        put("builtin", bot.builtin)
        put("commands", encodeCommands(bot.commands))
    }

    fun stringArray(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
