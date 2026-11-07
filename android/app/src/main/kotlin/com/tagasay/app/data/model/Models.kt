package com.tagasay.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChatType { PRIVATE, GROUP, CHANNEL, SAVED }

@Serializable
enum class MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }

@Serializable
enum class MessageKind { TEXT, IMAGE, VIDEO, FILE, AUDIO, STICKER, SYSTEM, CALL }

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
)

data class ServerStatus(
    val configured: Boolean = false,
    val online: Boolean = false,
    val latencyMs: Long? = null,
    val checkedAt: Long = 0,
    val version: String? = null,
)

data class UiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val vault: VaultData = VaultData(),
    val server: ServerStatus = ServerStatus(),
    val fingerprint: String = "",
)
