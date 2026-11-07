package com.tagasay.app.data

import com.tagasay.app.data.local.SampleData
import com.tagasay.app.data.local.VaultStore
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.Channel
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.MessageStatus
import com.tagasay.app.data.model.StickerPack
import com.tagasay.app.data.model.UserProfile
import com.tagasay.app.data.model.VaultData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class AppRepository(private val store: VaultStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _vault = MutableStateFlow(store.load() ?: SampleData.vault())
    val vault: StateFlow<VaultData> = _vault.asStateFlow()

    init {
        if (!store.exists()) persist(_vault.value)
    }

    // ------------------------------------------------------------- messages

    fun messages(chatId: String): List<Message> = _vault.value.messages[chatId].orEmpty()

    fun chat(chatId: String): Chat? = _vault.value.chats.firstOrNull { it.id == chatId }

    fun chatByUsername(username: String): Chat? =
        _vault.value.chats.firstOrNull { it.username.equals(username.trimStart('@'), ignoreCase = true) }

    fun contactByUsername(username: String): com.tagasay.app.data.model.Contact? =
        _vault.value.contacts.firstOrNull {
            it.username.equals(username.trimStart('@'), ignoreCase = true)
        }

    fun sendMessage(
        chatId: String,
        text: String,
        kind: MessageKind = MessageKind.TEXT,
        attachment: Attachment? = null,
        sticker: String? = null,
        replyToText: String? = null,
        replyToId: String? = null,
        forwardedFrom: String? = null,
        senderName: String = "Вы",
        isMine: Boolean = true,
    ): Message {
        val now = System.currentTimeMillis()
        val message = Message(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            senderId = if (isMine) "me" else "system",
            senderName = senderName,
            text = text,
            timestamp = now,
            isMine = isMine,
            status = MessageStatus.SENT,
            kind = kind,
            attachment = attachment,
            sticker = sticker,
            replyToText = replyToText,
            replyToId = replyToId,
            forwardedFrom = forwardedFrom,
        )
        updateVault { data ->
            val list = data.messages[chatId].orEmpty() + message
            data.copy(
                messages = data.messages + (chatId to list),
                chats = data.chats.map { chat ->
                    if (chat.id == chatId) {
                        chat.copy(
                            lastMessage = preview(message),
                            lastTimestamp = now,
                            unread = 0,
                        )
                    } else {
                        chat
                    }
                },
            )
        }
        return message
    }

    fun editMessage(chatId: String, messageId: String, newText: String) {
        updateVault { data ->
            val list = data.messages[chatId].orEmpty().map { message ->
                if (message.id == messageId && message.isMine) {
                    message.copy(text = newText, edited = true)
                } else {
                    message
                }
            }
            val last = list.lastOrNull()
            data.copy(
                messages = data.messages + (chatId to list),
                chats = data.chats.map { chat ->
                    if (chat.id == chatId && last?.id == messageId) {
                        chat.copy(lastMessage = preview(last), lastTimestamp = last.timestamp)
                    } else {
                        chat
                    }
                },
            )
        }
    }

    fun deleteMessage(chatId: String, messageId: String) {
        updateVault { data ->
            val list = data.messages[chatId].orEmpty().filterNot { it.id == messageId }
            val last = list.lastOrNull()
            data.copy(
                messages = data.messages + (chatId to list),
                chats = data.chats.map { chat ->
                    if (chat.id == chatId) {
                        chat.copy(
                            lastMessage = last?.let(::preview).orEmpty(),
                            lastTimestamp = last?.timestamp ?: chat.lastTimestamp,
                        )
                    } else {
                        chat
                    }
                },
            )
        }
    }

    fun forwardMessage(sourceChatId: String, messageId: String, targetChatId: String): Message? {
        val source = messages(sourceChatId).firstOrNull { it.id == messageId } ?: return null
        val sourceTitle = chat(sourceChatId)?.title ?: "чат"
        return sendMessage(
            chatId = targetChatId,
            text = source.text,
            kind = source.kind,
            attachment = source.attachment,
            sticker = source.sticker,
            forwardedFrom = sourceTitle,
        )
    }

    /**
     * Приём E2EE-сообщения: создаёт личный чат, если его ещё нет, и увеличивает unread.
     * Повторный конверт с тем же [messageId] игнорируется (дедупликация redelivery).
     */
    fun receiveMessage(
        senderUsername: String,
        text: String,
        messageId: String? = null,
        timestamp: Long = 0,
        kind: MessageKind = MessageKind.TEXT,
        attachment: Attachment? = null,
        sticker: String? = null,
        replyToId: String? = null,
        replyToText: String? = null,
        forwardedFrom: String? = null,
    ) {
        val clean = senderUsername.trimStart('@')
        if (clean.isBlank()) return
        val now = if (timestamp > 0) timestamp else System.currentTimeMillis()
        val id = messageId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val existing = _vault.value.chats.firstOrNull {
            it.type == ChatType.PRIVATE && it.username.equals(clean, ignoreCase = true)
        }
        val chatId = existing?.id ?: "dm_${clean.lowercase()}"
        if (_vault.value.messages[chatId].orEmpty().any { it.id == id }) return
        val chat = existing ?: Chat(
            id = chatId,
            title = "@$clean",
            username = clean,
            type = ChatType.PRIVATE,
            avatarColor = AVATAR_COLORS.random(),
            lastMessage = "",
            lastTimestamp = now,
        )
        val senderName = _vault.value.contacts
            .firstOrNull { it.username.equals(clean, true) }
            ?.displayName ?: "@$clean"
        val message = Message(
            id = id,
            chatId = chatId,
            senderId = clean,
            senderName = senderName,
            text = text,
            timestamp = now,
            isMine = false,
            status = MessageStatus.DELIVERED,
            kind = kind,
            attachment = attachment,
            sticker = sticker,
            replyToId = replyToId,
            replyToText = replyToText,
            forwardedFrom = forwardedFrom,
        )
        updateVault { data ->
            data.copy(
                chats = if (existing == null) {
                    listOf(chat.copy(lastMessage = preview(message), lastTimestamp = now, unread = 1)) + data.chats
                } else {
                    data.chats.map {
                        if (it.id == chatId) {
                            it.copy(
                                lastMessage = preview(message),
                                lastTimestamp = now,
                                unread = it.unread + 1,
                            )
                        } else {
                            it
                        }
                    }
                },
                messages = data.messages + (chatId to (data.messages[chatId].orEmpty() + message)),
            )
        }
    }

    fun markRead(chatId: String) {
        updateVault { data ->
            data.copy(chats = data.chats.map { if (it.id == chatId) it.copy(unread = 0) else it })
        }
    }

    /** Редактирование сообщения собеседником. Свои сообщения менять нельзя. */
    fun applyRemoteEdit(peerUsername: String, messageId: String, text: String) {
        val chat = chatByUsername(peerUsername) ?: return
        if (messageId.isBlank() || text.isBlank()) return
        updateVault { data ->
            val list = data.messages[chat.id].orEmpty().map { message ->
                if (message.id == messageId && !message.isMine) {
                    message.copy(text = text, edited = true)
                } else {
                    message
                }
            }
            val last = list.lastOrNull()
            data.copy(
                messages = data.messages + (chat.id to list),
                chats = data.chats.map { item ->
                    if (item.id == chat.id && last?.id == messageId) {
                        item.copy(lastMessage = preview(last), lastTimestamp = last.timestamp)
                    } else {
                        item
                    }
                },
            )
        }
    }

    /** Удаление сообщения собеседником. */
    fun applyRemoteDelete(peerUsername: String, messageId: String) {
        val chat = chatByUsername(peerUsername) ?: return
        val exists = messages(chat.id).any { it.id == messageId && !it.isMine }
        if (exists) deleteMessage(chat.id, messageId)
    }

    /**
     * Квитанция доставки/прочтения: обновляет статусы только своих сообщений.
     * READ нельзя понизить до DELIVERED, если квитанции пришли не по порядку.
     */
    fun applyReceipt(peerUsername: String, ids: Set<String>, status: MessageStatus) {
        val chat = chatByUsername(peerUsername) ?: return
        if (ids.isEmpty()) return
        updateVault { data ->
            val list = data.messages[chat.id].orEmpty().map { message ->
                if (!message.isMine || message.id !in ids) return@map message
                val next = when {
                    status == MessageStatus.READ -> MessageStatus.READ
                    message.status == MessageStatus.READ -> MessageStatus.READ
                    else -> status
                }
                message.copy(status = next)
            }
            data.copy(messages = data.messages + (chat.id to list))
        }
    }

    fun setTyping(chatId: String) = Unit

    /**
     * Обновляет вложение (например, после скачивания и расшифровки).
     */
    fun updateAttachment(chatId: String, messageId: String, attachment: Attachment) {
        updateVault { data ->
            data.copy(
                messages = data.messages.mapValues { (id, list) ->
                    if (id != chatId) {
                        list
                    } else {
                        list.map { message ->
                            if (message.id == messageId) message.copy(attachment = attachment) else message
                        }
                    }
                },
            )
        }
    }

    fun setMessageStatus(chatId: String, messageId: String, status: MessageStatus) {
        updateVault { data ->
            data.copy(
                messages = data.messages.mapValues { (id, list) ->
                    if (id != chatId) {
                        list
                    } else {
                        list.map { message ->
                            if (message.id == messageId) message.copy(status = status) else message
                        }
                    }
                },
            )
        }
    }

    // --------------------------------------------------------------- chats

    fun ensureDirectChat(username: String, displayName: String, avatarColor: Long): String {
        val clean = username.trimStart('@')
        val existing = _vault.value.chats.firstOrNull {
            it.type == ChatType.PRIVATE && it.username.equals(clean, ignoreCase = true)
        }
        if (existing != null) return existing.id

        val id = "dm_${clean.lowercase()}"
        val chat = Chat(
            id = id,
            title = displayName,
            username = clean,
            type = ChatType.PRIVATE,
            avatarColor = avatarColor,
            online = true,
            lastMessage = "Начните защищённую переписку",
            lastTimestamp = System.currentTimeMillis(),
        )
        updateVault { it.copy(chats = listOf(chat) + it.chats) }
        return id
    }

    fun createChannel(title: String, username: String, description: String): Channel {
        val clean = username.trimStart('@').ifBlank { "channel_${System.currentTimeMillis() % 10_000}" }
        val id = "ch_${clean.lowercase()}"
        val channel = Channel(
            id = id,
            username = clean,
            title = title,
            subscribers = 1,
            avatarColor = 0xFF7B61FF,
            description = description,
            ownedByMe = true,
        )
        val chat = Chat(
            id = id,
            title = title,
            username = clean,
            type = ChatType.CHANNEL,
            avatarColor = channel.avatarColor,
            verified = false,
            lastMessage = "Канал создан",
            lastTimestamp = System.currentTimeMillis(),
            description = description,
        )
        updateVault { data ->
            data.copy(
                channels = listOf(channel) + data.channels,
                chats = listOf(chat) + data.chats,
                messages = data.messages + (
                    id to listOf(
                        Message(
                            id = UUID.randomUUID().toString(),
                            chatId = id,
                            senderId = "system",
                            senderName = "TAGa-Say",
                            text = "Канал «$title» создан. Подписчики увидят ваши сообщения, но не смогут писать в него.",
                            timestamp = System.currentTimeMillis(),
                            isMine = false,
                            kind = MessageKind.SYSTEM,
                        ),
                    )
                ),
            )
        }
        return channel
    }

    fun installStickerPack(packId: String, installed: Boolean) {
        updateVault { data ->
            data.copy(
                stickerPacks = data.stickerPacks.map {
                    if (it.id == packId) it.copy(installed = installed) else it
                },
            )
        }
    }

    fun addStickerPack(title: String, stickers: List<String>) {
        val pack = StickerPack(
            id = "custom_${System.currentTimeMillis()}",
            title = title,
            author = "Вы",
            stickers = stickers,
            installed = true,
        )
        updateVault { it.copy(stickerPacks = it.stickerPacks + pack) }
    }

    // ------------------------------------------------------------- profile

    fun updateProfile(displayName: String, username: String, phone: String, bio: String) {
        updateVault {
            it.copy(
                profile = UserProfile(
                    displayName = displayName,
                    username = username.trimStart('@'),
                    phone = phone,
                    bio = bio,
                    avatarColor = it.profile.avatarColor,
                ),
            )
        }
    }

    fun resetToDemo() {
        val demo = SampleData.vault()
        _vault.value = demo
        persist(demo)
    }

    fun wipeAll() {
        store.wipe()
        _vault.value = VaultData()
        persist(_vault.value)
    }

    // ------------------------------------------------------------- internals

    private fun updateVault(transform: (VaultData) -> VaultData) {
        val next = transform(_vault.value)
        _vault.value = next
        persist(next)
    }

    private fun persist(data: VaultData) {
        scope.launch { runCatching { store.save(data) } }
    }

    private fun preview(message: Message): String = when (message.kind) {
        MessageKind.TEXT -> message.text
        MessageKind.STICKER -> "Стикер ${message.sticker.orEmpty()}"
        MessageKind.IMAGE -> "📷 Фото"
        MessageKind.VIDEO -> "🎬 Видео"
        MessageKind.AUDIO -> "🎤 Голосовое сообщение"
        MessageKind.FILE -> "📎 ${message.attachment?.name ?: "Файл"}"
        MessageKind.SYSTEM -> message.text
        MessageKind.CALL -> "📞 Звонок"
    }

    fun applySettingsToProfile(settings: AppSettings) {
        if (settings.displayName.isBlank()) return
        val current = _vault.value.profile
        if (current.displayName == settings.displayName && current.username == settings.username) return
        updateProfile(settings.displayName, settings.username, settings.phone, current.bio)
    }

    private companion object {
        val AVATAR_COLORS = listOf(
            0xFF7B61FF,
            0xFFE06C9F,
            0xFF4CC38A,
            0xFFF2A65A,
            0xFF4C8DFF,
            0xFF9B8AFB,
            0xFFE5484D,
            0xFF2FB6B0,
        )
    }
}
