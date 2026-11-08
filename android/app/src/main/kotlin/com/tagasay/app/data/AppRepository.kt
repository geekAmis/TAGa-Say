package com.tagasay.app.data

import com.tagasay.app.BuildConfig
import com.tagasay.app.data.local.SampleData
import com.tagasay.app.data.local.VaultStore
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.CallEntry
import com.tagasay.app.data.model.Channel
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.ChatFolder
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.Contact
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.MessageStatus
import com.tagasay.app.data.model.Poll
import com.tagasay.app.data.model.StickerPack
import com.tagasay.app.data.model.Story
import com.tagasay.app.data.model.UserProfile
import com.tagasay.app.data.model.VaultData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class AppRepository(private val store: VaultStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initialVault = store.load()
        ?: if (BuildConfig.DEBUG) SampleData.vault() else VaultData()
    private val _vault = MutableStateFlow(initialVault)
    val vault: StateFlow<VaultData> = _vault.asStateFlow()

    /** Новые входящие сообщения — для уведомлений фонового сервиса. */
    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incoming: SharedFlow<Message> = _incoming.asSharedFlow()

    init {
        if (!store.exists()) {
            persist(_vault.value)
        } else {
            migrateDefaults()
        }
    }

    /** Добавляет новые разделы в vault, созданный предыдущими версиями. */
    private fun migrateDefaults() {
        val data = _vault.value
        var next = data
        if (next.chatFolders.isEmpty()) {
            next = next.copy(
                chatFolders = listOf(
                    ChatFolder(
                        id = "personal",
                        title = "Личные",
                        emoji = "👤",
                        types = listOf(ChatType.PRIVATE, ChatType.SAVED),
                        includeSaved = true,
                    ),
                    ChatFolder(
                        id = "work",
                        title = "Работа",
                        emoji = "💼",
                        types = listOf(ChatType.GROUP, ChatType.CHANNEL),
                    ),
                    ChatFolder(
                        id = "unread",
                        title = "Непрочитанные",
                        emoji = "🔔",
                        unreadOnly = true,
                    ),
                ),
            )
        }
        if (next.stickerPacks.isEmpty()) {
            next = next.copy(
                stickerPacks = listOf(
                    StickerPack(
                        id = "taga-classic",
                        title = "TAGa Classic",
                        author = "TAGa-Say",
                        stickers = listOf("🛡️", "🔐", "💬", "✨", "👋", "👍", "🔥", "💜", "🤝", "🚀"),
                        installed = true,
                    ),
                    StickerPack(
                        id = "cats",
                        title = "Коты-шифровальщики",
                        author = "TAGa-Say",
                        stickers = listOf("😺", "😸", "😹", "😻", "😼", "🙀", "😿", "😾"),
                        installed = true,
                    ),
                ),
            )
        }
        val known = next.contacts.map { it.username.lowercase() }.toSet()
        val derived = next.chats
            .filter { it.type == ChatType.PRIVATE && it.username.isNotBlank() }
            .filter { it.username.lowercase() !in known }
            .map {
                com.tagasay.app.data.model.Contact(
                    id = it.id,
                    username = it.username,
                    displayName = it.title,
                    phone = "",
                    bio = "",
                    avatarColor = it.avatarColor,
                    online = it.online,
                    verified = it.verified,
                )
            }
        if (derived.isNotEmpty()) {
            next = next.copy(contacts = next.contacts + derived)
        }
        if (next != data) {
            _vault.value = next
            persist(next)
        }
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
        poll: Poll? = null,
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
            poll = poll,
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
        poll: Poll? = null,
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
            poll = poll,
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
        _incoming.tryEmit(message)
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

    fun toggleStickerFavorite(sticker: String) {
        updateVault { data ->
            val favorites = data.stickerFavorites.toMutableList()
            if (!favorites.remove(sticker)) favorites.add(sticker)
            data.copy(stickerFavorites = favorites)
        }
    }

    // ------------------------------------------------------------- folders

    fun saveFolder(folder: ChatFolder) {
        updateVault { data ->
            val exists = data.chatFolders.any { it.id == folder.id }
            data.copy(
                chatFolders = if (exists) {
                    data.chatFolders.map { if (it.id == folder.id) folder else it }
                } else {
                    data.chatFolders + folder
                },
            )
        }
    }

    fun deleteFolder(folderId: String) {
        updateVault { data ->
            data.copy(chatFolders = data.chatFolders.filterNot { it.id == folderId })
        }
    }

    // ---------------------------------------------------------- call log

    fun addCall(
        chatId: String,
        seconds: Int,
        video: Boolean = false,
        outgoing: Boolean = true,
        missed: Boolean = false,
    ) {
        val chat = chat(chatId) ?: return
        val entry = CallEntry(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            title = chat.title,
            avatarColor = chat.avatarColor,
            outgoing = outgoing,
            missed = missed,
            timestamp = System.currentTimeMillis(),
            seconds = seconds,
            video = video,
        )
        updateVault { data ->
            data.copy(callLog = (listOf(entry) + data.callLog).take(300))
        }
    }

    fun clearCallLog() {
        updateVault { it.copy(callLog = emptyList()) }
    }

    // ------------------------------------------------------------ stories

    fun addStory(localUri: String, caption: String): Story? {
        val mine = _vault.value.profile
        val username = mine.username.ifBlank { "me" }
        val story = Story(
            id = UUID.randomUUID().toString(),
            authorUsername = username,
            authorName = mine.displayName.ifBlank { "Вы" },
            avatarColor = mine.avatarColor,
            localUri = localUri,
            caption = caption,
            createdAt = System.currentTimeMillis(),
            viewed = true,
            mine = true,
        )
        updateVault { it.copy(stories = listOf(story) + it.stories) }
        return story
    }

    fun markStoryViewed(storyId: String) {
        updateVault { data ->
            data.copy(
                stories = data.stories.map {
                    if (it.id == storyId) it.copy(viewed = true) else it
                },
            )
        }
    }

    fun attachStoryMedia(
        storyId: String,
        mediaId: String,
        keyBase64: String,
        sha256: String,
        mimeType: String,
        sizeBytes: Long,
        localUri: String?,
    ) {
        updateVault { data ->
            data.copy(
                stories = data.stories.map { story ->
                    if (story.id != storyId) {
                        story
                    } else {
                        story.copy(
                            mediaId = mediaId,
                            keyBase64 = keyBase64,
                            sha256 = sha256,
                            mimeType = mimeType,
                            sizeBytes = sizeBytes,
                            localUri = localUri ?: story.localUri,
                        )
                    }
                },
            )
        }
    }

    fun deleteStory(storyId: String) {
        updateVault { data ->
            val target = data.stories.firstOrNull { it.id == storyId }
            val uri = target?.localUri
            if (uri != null && uri.startsWith("file:")) {
                runCatching { java.io.File(java.net.URI(uri)).delete() }
            }
            data.copy(stories = data.stories.filterNot { it.id == storyId })
        }
    }

    fun archiveStory(storyId: String) {
        updateVault { data ->
            data.copy(
                stories = data.stories.map {
                    if (it.id == storyId) it.copy(archived = true) else it
                },
            )
        }
    }

    // ----------------------------------------------------------- reactions

    fun toggleReaction(chatId: String, messageId: String, emoji: String) {
        updateVault { data ->
            val list = data.messages[chatId].orEmpty().map { message ->
                if (message.id != messageId) return@map message
                val old = message.myReaction
                val reactions = message.reactions.toMutableMap()
                if (old != null) {
                    reactions[old] = (reactions[old] ?: 1) - 1
                    if (reactions[old]!! <= 0) reactions.remove(old)
                }
                if (old != emoji) {
                    reactions[emoji] = (reactions[emoji] ?: 0) + 1
                }
                message.copy(
                    reactions = reactions,
                    myReaction = if (old == emoji) null else emoji,
                )
            }
            data.copy(messages = data.messages + (chatId to list))
        }
    }

    fun applyRemoteReaction(peerUsername: String, messageId: String, emoji: String) {
        val chat = chatByUsername(peerUsername) ?: return
        updateVault { data ->
            val list = data.messages[chat.id].orEmpty().map { message ->
                if (message.id != messageId) return@map message
                val reactions = message.reactions.toMutableMap()
                reactions[emoji] = (reactions[emoji] ?: 0) + 1
                message.copy(reactions = reactions)
            }
            data.copy(messages = data.messages + (chat.id to list))
        }
    }

    // -------------------------------------------------------------- polls

    fun votePoll(chatId: String, messageId: String, optionId: String) {
        updateVault { data ->
            val list = data.messages[chatId].orEmpty().map { message ->
                val poll = message.poll
                if (message.id != messageId || poll == null || poll.closed) return@map message

                val oldVotes = poll.myVotes
                val newVotes = when {
                    optionId in oldVotes -> oldVotes - optionId
                    poll.multiple -> oldVotes + optionId
                    else -> listOf(optionId)
                }
                val options = poll.options.map { option ->
                    val delta = (if (option.id in newVotes) 1 else 0) -
                        (if (option.id in oldVotes) 1 else 0)
                    if (delta == 0) option else option.copy(votes = (option.votes + delta).coerceAtLeast(0))
                }
                message.copy(poll = poll.copy(options = options, myVotes = newVotes))
            }
            data.copy(
                messages = data.messages + (chatId to list),
                chats = data.chats.map { chat ->
                    if (chat.id == chatId) {
                        val last = list.lastOrNull()
                        if (last?.id == messageId) {
                            chat.copy(lastMessage = preview(last), lastTimestamp = last.timestamp)
                        } else chat
                    } else chat
                },
            )
        }
    }

    // -------------------------------------------------------------- rooms

    fun createRoomChat(room: com.tagasay.app.data.model.GroupRoom): Chat {
        val existing = _vault.value.chats.firstOrNull {
            it.roomId == room.id || it.id == room.id ||
                (it.type != ChatType.PRIVATE &&
                    it.username.equals(room.username, ignoreCase = true))
        }
        if (existing != null) return existing
        val chat = Chat(
            id = room.id,
            title = room.title,
            username = room.username,
            type = if (room.channel) ChatType.CHANNEL else ChatType.GROUP,
            avatarColor = AVATAR_COLORS.random(),
            lastMessage = if (room.channel) "Канал" else "Группа создана",
            lastTimestamp = System.currentTimeMillis(),
            description = room.description,
            roomId = room.id,
            members = room.members,
            ownerUsername = room.owner,
            admins = room.admins,
        )
        updateVault { it.copy(chats = listOf(chat) + it.chats) }
        return chat
    }

    fun applyRoom(room: com.tagasay.app.data.model.GroupRoom) {
        updateVault { data ->
            val exists = data.chats.any {
                it.roomId == room.id || it.id == room.id ||
                    (it.type != ChatType.PRIVATE &&
                        it.username.equals(room.username, ignoreCase = true))
            }
            if (!exists) {
                val chat = Chat(
                    id = room.id,
                    title = room.title,
                    username = room.username,
                    type = if (room.channel) ChatType.CHANNEL else ChatType.GROUP,
                    avatarColor = AVATAR_COLORS.random(),
                    lastMessage = room.description.ifBlank {
                        if (room.channel) "Канал" else "Группа"
                    },
                    lastTimestamp = System.currentTimeMillis(),
                    description = room.description,
                    roomId = room.id,
                    members = room.members,
                    ownerUsername = room.owner,
                    admins = room.admins,
                )
                data.copy(chats = listOf(chat) + data.chats)
            } else {
                data.copy(
                    chats = data.chats.map { chat ->
                        if (chat.roomId == room.id || chat.id == room.id ||
                            (chat.type != ChatType.PRIVATE &&
                                chat.username.equals(room.username, ignoreCase = true))
                        ) {
                            chat.copy(
                                title = room.title,
                                username = room.username,
                                description = room.description,
                                members = room.members,
                                ownerUsername = room.owner,
                                admins = room.admins,
                                roomId = room.id,
                            )
                        } else {
                            chat
                        }
                    },
                )
            }
        }
    }

    fun removeRoomChat(roomId: String) {
        updateVault { data ->
            val chat = data.chats.firstOrNull { it.roomId == roomId || it.id == roomId }
            val ids = listOfNotNull(roomId, chat?.id).toSet()
            data.copy(
                chats = data.chats.filterNot { it.roomId == roomId || it.id == roomId },
                messages = data.messages.filterKeys { it !in ids },
            )
        }
    }

    fun createLocalGroup(title: String, members: List<String>, owner: String): Chat {
        val id = "group_${System.currentTimeMillis()}"
        val chat = Chat(
            id = id,
            title = title,
            username = id,
            type = ChatType.GROUP,
            avatarColor = AVATAR_COLORS.random(),
            lastMessage = "Группа создана",
            lastTimestamp = System.currentTimeMillis(),
            members = (members + owner).distinct(),
            ownerUsername = owner,
        )
        updateVault { it.copy(chats = listOf(chat) + it.chats) }
        return chat
    }

    fun receiveGroupMessage(
        roomId: String,
        roomTitle: String,
        channel: Boolean,
        senderUsername: String,
        senderName: String,
        text: String,
        messageId: String,
        timestamp: Long,
        kind: MessageKind = MessageKind.TEXT,
        attachment: Attachment? = null,
        sticker: String? = null,
        replyToId: String? = null,
        replyToText: String? = null,
    ): Boolean {
        val existingChat = _vault.value.chats.firstOrNull {
            it.roomId == roomId || it.id == roomId
        }
        val chatId = existingChat?.id ?: roomId
        if (_vault.value.messages[chatId].orEmpty().any { it.id == messageId }) return false
        val now = if (timestamp > 0) timestamp else System.currentTimeMillis()
        val message = Message(
            id = messageId,
            chatId = chatId,
            senderId = senderUsername,
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
        )
        updateVault { data ->
            val chat = existingChat ?: Chat(
                id = chatId,
                title = roomTitle.ifBlank { "@$senderUsername" },
                username = roomId,
                type = if (channel) ChatType.CHANNEL else ChatType.GROUP,
                avatarColor = AVATAR_COLORS.random(),
                lastMessage = preview(message),
                lastTimestamp = now,
                unread = 1,
                roomId = roomId,
                members = emptyList(),
            )
            data.copy(
                chats = if (existingChat == null) {
                    listOf(chat) + data.chats
                } else {
                    data.chats.map {
                        if (it.id == chatId) {
                            it.copy(
                                lastMessage = preview(message),
                                lastTimestamp = now,
                                unread = it.unread + 1,
                            )
                        } else it
                    }
                },
                messages = data.messages + (chatId to (data.messages[chatId].orEmpty() + message)),
            )
        }
        _incoming.tryEmit(message)
        return true
    }
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

    /**
     * Применяет состояние, полученное с relay. Личные сообщения остаются
     * локальными; синхронизируются профиль, оформление, папки, стикеры,
     * звонки, контакты, каналы и истории.
     */
    fun applySyncedState(
        profile: UserProfile?,
        folders: List<ChatFolder>,
        stickerPacks: List<StickerPack>,
        stickerFavorites: List<String>,
        callLog: List<CallEntry>,
        contacts: List<Contact>,
        channels: List<Channel>,
        stories: List<Story>,
    ) {
        updateVault { data ->
            val mergedStories = stories.map { remote ->
                val local = data.stories.firstOrNull { it.id == remote.id }
                // content:// URI с другого устройства недоступен; оставляем свой.
                remote.copy(localUri = local?.localUri)
            }
            val knownChats = data.chats.map { it.id }.toSet()
            val channelChats = channels
                .filterNot { it.id in knownChats }
                .map { channel ->
                    Chat(
                        id = channel.id,
                        title = channel.title,
                        username = channel.username,
                        type = ChatType.CHANNEL,
                        avatarColor = channel.avatarColor,
                        verified = channel.verified,
                        lastMessage = channel.description.ifBlank { "Канал" },
                        lastTimestamp = System.currentTimeMillis(),
                        description = channel.description,
                    )
                }
            data.copy(
                profile = profile ?: data.profile,
                chatFolders = folders,
                stickerPacks = stickerPacks,
                stickerFavorites = stickerFavorites,
                callLog = callLog,
                contacts = contacts,
                channels = channels,
                stories = mergedStories,
                chats = data.chats + channelChats,
            )
        }
    }

    fun resetToDemo() {
        if (!BuildConfig.DEBUG) return
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
        MessageKind.POLL -> "📊 ${message.poll?.question ?: "Опрос"}"
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
