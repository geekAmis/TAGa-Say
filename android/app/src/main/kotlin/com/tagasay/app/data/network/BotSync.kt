package com.tagasay.app.data.network

import android.content.Context
import android.util.Log
import com.tagasay.app.data.AppRepository
import com.tagasay.app.data.model.BotInfo
import com.tagasay.app.data.model.BotInboxEvent
import com.tagasay.app.data.model.BotSendRequest
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageStatus
import com.tagasay.app.data.model.shouldAcceptBotMessage
import com.tagasay.app.data.model.validBotEntities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * РљР»РёРµРЅС‚СЃРєР°СЏ С‡Р°СЃС‚СЊ Р±РѕС‚-С‡Р°С‚РѕРІ (СЃРµСЂРІРµСЂ 1.2.0).
 *
 * РџСЂР°РІРёР»Р°:
 * - Р‘РѕС‚-С‡Р°С‚С‹ РќР• E2EE: С‚РµРєСЃС‚ СѓС…РѕРґРёС‚ РЅР° relay РѕС‚РєСЂС‹С‚Рѕ, Р±РѕС‚ РІРёРґРёС‚ С‚РѕР»СЊРєРѕ
 *   Р°РґСЂРµСЃРѕРІР°РЅРЅС‹Рµ РµРјСѓ СЃРѕРѕР±С‰РµРЅРёСЏ.
 * - РћС‚РїСЂР°РІРєР° РѕРїС‚РёРјРёСЃС‚РёС‡РЅР°СЏ: СЃРѕРѕР±С‰РµРЅРёРµ РїРѕСЏРІР»СЏРµС‚СЃСЏ СЃСЂР°Р·Сѓ, `client_msg_id`
 *   СЃРѕРІРїР°РґР°РµС‚ СЃ Р»РѕРєР°Р»СЊРЅС‹Рј id (СЃРµСЂРІРµСЂ РґРµРґСѓРїР»РёС†РёСЂСѓРµС‚ РїРѕРІС‚РѕСЂС‹).
 * - Inbox С‚СЏРЅРµС‚СЃСЏ long-poll'РѕРј; РєСѓСЂСЃРѕСЂ `since` СЃРѕС…СЂР°РЅСЏРµС‚СЃСЏ РЅР° СѓСЃС‚СЂРѕР№СЃС‚РІРµ,
 *   РїРѕРІС‚РѕСЂРЅС‹Рµ `message_id` РѕС‚Р±СЂР°СЃС‹РІР°СЋС‚СЃСЏ.
 */
class BotSync(
    context: Context,
    private val api: TagaApi,
    private val repository: AppRepository,
    private val localUsername: String,
    private val noticeSink: MutableSharedFlow<String>? = null,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("taga_bot_inbox", Context.MODE_PRIVATE)

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private fun emitNotice(text: String) {
        if (noticeSink != null) noticeSink.tryEmit(text) else _notices.tryEmit(text)
    }

    private val cursorKey = "since:${localUsername.trimStart('@').lowercase()}"

    // ------------------------------------------------------------------- open

    /**
     * РћС‚РєСЂС‹РІР°РµС‚ Р±РѕС‚-С‡Р°С‚: РїРѕРґС‚СЏРіРёРІР°РµС‚ РєР°СЂС‚РѕС‡РєСѓ, СЃРѕР·РґР°С‘С‚ Р»РѕРєР°Р»СЊРЅС‹Р№ С‡Р°С‚ Рё
     * РѕС‚РїСЂР°РІР»СЏРµС‚ `/start`, РµСЃР»Рё РїРѕР»СЊР·РѕРІР°С‚РµР»СЊ РµС‰С‘ РЅРµ РЅР°С‡РёРЅР°Р» РґРёР°Р»РѕРі.
     */
    suspend fun openBot(username: String): Result<String> {
        val clean = normalize(username)
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("empty bot username"))
        val card = runCatching { api.bot(clean) }.getOrElse { error ->
            val cached = repository.botInfo(clean)
                ?: return Result.failure(error)
            return Result.success(repository.ensureBotChat(clean, cached))
        }
        val chatId = repository.ensureBotChat(clean, card)
        if (card.deleted) {
            repository.markBotDeleted(clean)
            return Result.success(chatId)
        }
        val neverStarted = repository.messages(chatId).isEmpty()
        val blocked = repository.botInfo(clean)?.blocked == true || card.blocked
        if ((!card.started || neverStarted) && !blocked) {
            sendText(chatId, "/start")
        }
        return Result.success(chatId)
    }

    /** РћР±РЅРѕРІР»СЏРµС‚ РєР°СЂС‚РѕС‡РєСѓ Р±РѕС‚Р° (СЃС‚Р°С‚СѓСЃ, РёРјСЏ, Р±Р»РѕРєРёСЂРѕРІРєР°) Р±РµР· СЃРѕР·РґР°РЅРёСЏ С‡Р°С‚Р°. */
    suspend fun refreshCard(username: String): Result<BotInfo> {
        val clean = normalize(username)
        return runCatching { api.bot(clean) }
            .onSuccess { card ->
                repository.saveBotInfo(card)
                if (card.deleted) repository.markBotDeleted(clean)
            }
            .onFailure { Log.w(TAG, "bot card failed: ${it.message}") }
    }

    // ------------------------------------------------------------------- send

    /**
     * РћРїС‚РёРјРёСЃС‚РёС‡РЅР°СЏ РѕС‚РїСЂР°РІРєР° С‚РµРєСЃС‚Р° Р±РѕС‚Сѓ. РќРµРґРѕСЃС‚СѓРїРЅРѕСЃС‚СЊ Р±РѕС‚Р° РїРѕРјРµС‡Р°РµС‚ С‚РѕР»СЊРєРѕ
     * СЌС‚Рѕ СЃРѕРѕР±С‰РµРЅРёРµ FAILED, С‡Р°С‚ Рё РѕСЃС‚Р°Р»СЊРЅС‹Рµ СЃРѕРѕР±С‰РµРЅРёСЏ РЅРµ Р»РѕРјР°СЋС‚СЃСЏ.
     */
    suspend fun sendText(
        chatId: String,
        text: String,
        replyToId: String? = null,
    ): Result<Unit> {
        val chat = repository.chat(chatId)
            ?: return Result.failure(IllegalStateException("chat not found"))
        if (chat.type != ChatType.BOT) {
            return Result.failure(IllegalStateException("not a bot chat"))
        }
        val clean = normalize(chat.username)
        val message = repository.sendMessage(
            chatId = chatId,
            text = text,
            replyToId = replyToId,
        )
        repository.setMessageStatus(chatId, message.id, MessageStatus.SENDING)
        return runCatching {
            val response = api.sendBotMessage(
                clean,
                BotSendRequest(
                    text = text,
                    clientMsgId = message.id,
                    chatId = "u:${localUsername.trimStart('@').lowercase()}",
                    replyTo = replyToId,
                ),
            )
            check(response.accepted) { "bot did not accept the message" }
            repository.setMessageStatus(chatId, message.id, MessageStatus.SENT)
            if (text.trim().equals("/start", ignoreCase = true)) {
                repository.botInfo(clean)?.let { repository.saveBotInfo(it.copy(started = true)) }
            }
        }.onFailure { error ->
            repository.setMessageStatus(chatId, message.id, MessageStatus.FAILED)
            Log.w(TAG, "bot send failed: ${error.message}")
            emitNotice(noticeFor(error))
        }
    }

    suspend fun clickCallback(
        chatId: String,
        messageId: String,
        data: String,
    ): Result<Unit> {
        val chat = repository.chat(chatId)
            ?: return Result.failure(IllegalStateException("chat not found"))
        if (data.isBlank()) return Result.success(Unit)
        return runCatching {
            api.botCallback(chat.username.trimStart('@'), messageId, data)
        }.onFailure { error ->
            Log.w(TAG, "bot callback failed: ${error.message}")
            emitNotice(noticeFor(error))
        }
    }

    suspend fun setBlocked(chatId: String, blocked: Boolean): Result<Unit> {
        val chat = repository.chat(chatId)
            ?: return Result.failure(IllegalStateException("chat not found"))
        return runCatching {
            if (blocked) api.blockBot(chat.username) else api.unblockBot(chat.username)
            repository.setBotBlocked(chat.username, blocked)
            emitNotice(if (blocked) "Р‘РѕС‚ Р·Р°Р±Р»РѕРєРёСЂРѕРІР°РЅ" else "Р‘РѕС‚ СЂР°Р·Р±Р»РѕРєРёСЂРѕРІР°РЅ")
        }.onFailure { error ->
            Log.w(TAG, "bot block toggle failed: ${error.message}")
            emitNotice(noticeFor(error))
        }
    }

    // ------------------------------------------------------------------ inbox

    /** Р—Р°Р±РёСЂР°РµС‚ СЃРѕР±С‹С‚РёСЏ Р±РѕС‚-С‡Р°С‚РѕРІ. Р’РѕР·РІСЂР°С‰Р°РµС‚ С‡РёСЃР»Рѕ РїСЂРёРјРµРЅС‘РЅРЅС‹С… СЃРѕРѕР±С‰РµРЅРёР№. */
    suspend fun poll(): Int {
        val since = prefs.getLong(cursorKey, 0)
        val events = api.botInbox(since = since)
        if (events.isEmpty()) return 0
        var applied = 0
        var cursor = since
        for (event in events) {
            when (event.type) {
                BotInboxEvent.TYPE_MESSAGE -> {
                    val message = event.message
                    if (message != null && applyMessage(message, event)) applied++
                }
                BotInboxEvent.TYPE_EDITED -> applyEdited(event)
                BotInboxEvent.TYPE_DELETED -> applyDeleted(event)
                BotInboxEvent.TYPE_CALLBACK_ANSWER -> {
                    if (event.text.isNotBlank()) emitNotice(event.text)
                }
                else -> Log.w(TAG, "unknown bot inbox event: ${event.type}")
            }
            if (event.eventId > cursor) cursor = event.eventId
        }
        prefs.edit().putLong(cursorKey, cursor).apply()
        return applied
    }

    private fun applyMessage(
        message: com.tagasay.app.data.model.BotMessage,
        event: BotInboxEvent,
    ): Boolean {
        val bot = normalize(message.botUsername.ifBlank { event.botUsername })
        if (bot.isBlank()) {
            Log.w(TAG, "bot message without bot_username: ${message.messageId}")
            return false
        }
        val info = repository.botInfo(bot)
        val chatId = repository.ensureBotChat(bot, info)
        val existing = repository.messages(chatId).map { it.id }.toSet()
        if (!shouldAcceptBotMessage(message.messageId, message.deletedAt, existing)) return false
        val text = message.text
        val entity = Message(
            id = message.messageId,
            chatId = chatId,
            senderId = bot,
            senderName = info?.displayName?.ifBlank { "@$bot" } ?: "@$bot",
            text = text,
            timestamp = message.timestampMs,
            isMine = false,
            status = MessageStatus.DELIVERED,
            entities = validBotEntities(message.entities, text.length),
            keyboard = message.keyboard,
        )
        return repository.addBotIncomingMessage(chatId, entity)
    }

    private fun applyEdited(event: BotInboxEvent) {
        val message = event.message ?: return
        val chatId = resolveChatId(event, message.botUsername) ?: return
        repository.updateBotMessage(
            chatId = chatId,
            messageId = message.messageId,
            text = message.text,
            entities = validBotEntities(message.entities, message.text.length),
            keyboard = message.keyboard,
        )
    }

    private fun applyDeleted(event: BotInboxEvent) {
        val ids = buildSet {
            addAll(event.messageIds.filter { it.isNotBlank() })
            event.message?.messageId?.takeIf { it.isNotBlank() }?.let(::add)
        }
        if (ids.isEmpty()) return
        val chatId = resolveChatId(event, event.message?.botUsername.orEmpty()) ?: return
        repository.deleteBotMessages(chatId, ids)
    }

    private fun resolveChatId(event: BotInboxEvent, botUsername: String): String? {
        val bot = normalize(botUsername.ifBlank { event.botUsername })
        if (bot.isNotBlank()) {
            val id = repository.botChatId(bot)
            if (repository.chat(id) != null) return id
        }
        // Р¤РѕР»Р±СЌРє: РёС‰РµРј Р±РѕС‚-С‡Р°С‚, РІ РєРѕС‚РѕСЂРѕРј РµСЃС‚СЊ СЌС‚Рѕ СЃРѕРѕР±С‰РµРЅРёРµ.
        val messageId = event.message?.messageId.orEmpty()
        if (messageId.isBlank()) return null
        return repository.vault.value.chats
            .firstOrNull { it.type == ChatType.BOT && repository.messages(it.id).any { m -> m.id == messageId } }
            ?.id
    }

    // ---------------------------------------------------------------- helpers

    private fun normalize(username: String): String =
        username.trim().trimStart('@').lowercase()

    private fun noticeFor(error: Throwable): String = when {
        error is ApiException && error.status == 403 ->
            "Р‘РѕС‚ РЅРµРґРѕСЃС‚СѓРїРµРЅ: СЃРЅР°С‡Р°Р»Р° РѕС‚РїСЂР°РІСЊС‚Рµ /start РёР»Рё СЂР°Р·Р±Р»РѕРєРёСЂСѓР№С‚Рµ РµРіРѕ"
        error is ApiException && error.status == 404 -> "Р‘РѕС‚ РЅРµ РЅР°Р№РґРµРЅ"
        error is ApiException && error.status == 429 -> "РЎР»РёС€РєРѕРј С‡Р°СЃС‚Рѕ, РїРѕРїСЂРѕР±СѓР№С‚Рµ РїРѕР·Р¶Рµ"
        error is ApiException -> error.message ?: "РћС€РёР±РєР° Р±РѕС‚Р°"
        else -> "РќРµС‚ СЃРІСЏР·Рё СЃ СЃРµСЂРІРµСЂРѕРј"
    }

    /** Р‘РѕС‚-С‡Р°С‚С‹ РґР»СЏ UI (С‚РѕР»СЊРєРѕ СЃ С‚РёРїРѕРј BOT). */
    fun botChats(): List<Chat> = repository.vault.value.chats.filter { it.type == ChatType.BOT }

    fun info(username: String): BotInfo? = repository.botInfo(username)

    private companion object {
        const val TAG = "TAGaBots"
    }
}

