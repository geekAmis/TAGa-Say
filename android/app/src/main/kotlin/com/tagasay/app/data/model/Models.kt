package com.tagasay.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChatType { PRIVATE, GROUP, CHANNEL, SAVED }

@Serializable
enum class MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }

@Serializable
enum class MessageKind { TEXT, IMAGE, VIDEO, FILE, AUDIO, STICKER, SYSTEM, CALL, POLL }

@Serializable
enum class ScanVerdict { SAFE, WARNING, DANGEROUS }

@Serializable
data class ScanReport(
    val verdict: ScanVerdict,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val detectedType: String,
    val reasons: List<String>,
    val fileName: String = "file",
) {
    val isBlocked: Boolean get() = verdict == ScanVerdict.DANGEROUS
}

@Serializable
data class Attachment(
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val sha256: String,
    val scanVerdict: ScanVerdict,
    val localUri: String? = null,
    val mediaId: String? = null,
    val keyBase64: String? = null,
)

@Serializable
data class PollOption(
    val id: String,
    val text: String,
    val votes: Int = 0,
)

@Serializable
data class Poll(
    val question: String,
    val options: List<PollOption>,
    val multiple: Boolean = false,
    val anonymous: Boolean = true,
    val closed: Boolean = false,
    val myVotes: List<String> = emptyList(),
) {
    val totalVotes: Int get() = options.sumOf { it.votes }
}

@Serializable
data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isMine: Boolean,
    val status: MessageStatus = MessageStatus.SENT,
    val kind: MessageKind = MessageKind.TEXT,
    val attachment: Attachment? = null,
    val edited: Boolean = false,
    val forwardedFrom: String? = null,
    val sticker: String? = null,
    val callSeconds: Int? = null,
    val replyToText: String? = null,
    val replyToId: String? = null,
    val poll: Poll? = null,
    val reactions: Map<String, Int> = emptyMap(),
    val myReaction: String? = null,
)

@Serializable
data class Chat(
    val id: String,
    val title: String,
    val username: String,
    val type: ChatType,
    val avatarColor: Long,
    val verified: Boolean = false,
    val online: Boolean = false,
    val unread: Int = 0,
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val lastMessage: String = "",
    val lastTimestamp: Long = 0,
    val description: String = "",
    val roomId: String? = null,
    val members: List<String> = emptyList(),
    val ownerUsername: String = "",
    val admins: List<String> = emptyList(),
)

/** Комната на relay: группа или канал. Содержимое сообщений сюда не входит. */
@Serializable
data class GroupRoom(
    val id: String,
    val username: String,
    val title: String,
    val description: String = "",
    val channel: Boolean = false,
    val owner: String,
    val admins: List<String> = emptyList(),
    val members: List<String> = emptyList(),
    val createdAt: Long = 0,
)

@Serializable
data class Contact(
    val id: String,
    val username: String,
    val displayName: String,
    val phone: String,
    val bio: String = "",
    val avatarColor: Long,
    val online: Boolean = false,
    val verified: Boolean = false,
    val isRegistered: Boolean = true,
)

@Serializable
data class Channel(
    val id: String,
    val username: String,
    val title: String,
    val subscribers: Int,
    val avatarColor: Long,
    val verified: Boolean = false,
    val description: String = "",
    val ownedByMe: Boolean = false,
)

@Serializable
data class StickerPack(
    val id: String,
    val title: String,
    val author: String,
    val stickers: List<String>,
    val installed: Boolean = false,
)

/** Папка чатов: фильтр по типу, непрочитанным и избранному. */
@Serializable
data class ChatFolder(
    val id: String,
    val title: String,
    val emoji: String = "",
    val types: List<ChatType> = emptyList(),
    val unreadOnly: Boolean = false,
    val includeSaved: Boolean = false,
)

/** Запись журнала звонков. */
@Serializable
data class CallEntry(
    val id: String,
    val chatId: String,
    val title: String,
    val avatarColor: Long,
    val outgoing: Boolean,
    val missed: Boolean = false,
    val timestamp: Long,
    val seconds: Int = 0,
    val video: Boolean = false,
)

/** Локальная история (сторис): фото/видео из галереи, живёт 24 часа. */
@Serializable
data class Story(
    val id: String,
    val authorUsername: String,
    val authorName: String,
    val avatarColor: Long,
    val localUri: String? = null,
    val caption: String = "",
    val createdAt: Long,
    val viewed: Boolean = false,
    val mine: Boolean = false,
    val archived: Boolean = false,
    val mediaId: String? = null,
    val keyBase64: String? = null,
    val sha256: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0,
) {
    val expired: Boolean
        get() = !mine && System.currentTimeMillis() - createdAt > STORY_TTL_MS

    companion object {
        const val STORY_TTL_MS = 24L * 60 * 60 * 1000
    }
}

@Serializable
data class UserProfile(
    val username: String = "",
    val displayName: String = "",
    val phone: String = "",
    val bio: String = "",
    val avatarColor: Long = 0xFF7B61FF,
)

@Serializable
data class VaultData(
    val profile: UserProfile = UserProfile(),
    val chats: List<Chat> = emptyList(),
    val messages: Map<String, List<Message>> = emptyMap(),
    val contacts: List<Contact> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val stickerPacks: List<StickerPack> = emptyList(),
    val stickerFavorites: List<String> = emptyList(),
    val chatFolders: List<ChatFolder> = emptyList(),
    val callLog: List<CallEntry> = emptyList(),
    val stories: List<Story> = emptyList(),
)

@Serializable
data class AppSettings(
    val onboarded: Boolean = false,
    val username: String = "",
    val displayName: String = "",
    val phone: String = "",
    val phoneDiscoverable: Boolean = true,
    val readReceipts: Boolean = true,
    val darkTheme: Boolean = true,
    val notifications: Boolean = true,
    val serverUrl: String = "",
    val showTyping: Boolean = true,
    val screenSecurity: Boolean = true,
    val messageTextSize: Int = 16,
    val bubbleRadius: Int = 18,
    val chatTheme: String = "violet",
    val chatWallpaper: String = "default",
)

data class ServerStatus(
    val configured: Boolean = false,
    val online: Boolean = false,
    val latencyMs: Long? = null,
    val checkedAt: Long = 0,
    val version: String? = null,
)

/** Состояние сервера и личного кабинета AEZA (приходит с relay). */
@Serializable
data class ServerInfo(
    val relayIp: String = "",
    val paidUntil: String? = null,
    val autoProlong: Boolean? = null,
    val serviceStatus: String? = null,
    val balance: String? = null,
    val price: String? = null,
    val shortfall: String? = null,
    val paymentTerm: String? = null,
    val currency: String? = null,
    val donateUrl: String? = null,
    val deletionAt: String? = null,
    val deletionEstimated: Boolean = true,
    val projectServers: Int = 0,
    val aezaAvailable: Boolean = false,
    val checkedAt: Long = 0,
    val error: String? = null,
)

data class UiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val vault: VaultData = VaultData(),
    val server: ServerStatus = ServerStatus(),
    val serverInfo: ServerInfo? = null,
    val update: UpdateStatus = UpdateStatus(),
    val fingerprint: String = "",
)

/** Ответ `/v1/version`: политика обновления. */
@Serializable
data class UpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val mandatory: Boolean = false,
    val minSupportedVersionCode: Int = 0,
    val sha256: String = "",
    val downloadUrl: String? = null,
    val mirrorUrl: String? = null,
    val notes: String = "",
    val available: Boolean = false,
)

/** Состояние апдейтера для UI. */
data class UpdateStatus(
    val available: Boolean = false,
    val mandatory: Boolean = false,
    val versionName: String = "",
    val versionCode: Int = 0,
    val notes: String = "",
    val dismissed: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val readyToInstall: Boolean = false,
    val error: String? = null,
)
