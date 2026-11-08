package com.tagasay.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChallengeRequest(val deviceKey: String)

@Serializable
data class ChallengeResponse(val challenge: String, val expiresAt: Long)

@Serializable
data class RegisterRequest(
    val deviceId: String,
    val username: String,
    val displayName: String,
    val phoneHash: String? = null,
    val passwordVerifier: String = "",
    val deviceHash: String = "",
    val totpCode: String = "",
    val identityKey: String,
    val signingKey: String = "",
    val signedPrekey: String,
    val signedPrekeySignature: String,
    val signedPrekeyId: Int = 1,
    val registrationId: Int = 1,
    val signalDeviceId: Int = 1,
    val kyberPrekey: String = "",
    val kyberPrekeySignature: String = "",
    val kyberPrekeyId: Int = 1,
    val oneTimePrekeys: List<String> = emptyList(),
    val challenge: String,
    val challengeSignature: String,
    val deviceInfo: String = "",
)

@Serializable
data class RegisterResponse(
    val accessToken: String,
    val deliveryToken: String,
    val serverSalt: String,
    val version: String,
)

@Serializable
data class Device(
    val deviceId: String,
    val username: String,
    val identityKey: String,
    val signingKey: String,
    val passwordVerifier: String = "",
    val deviceHash: String = "",
    val totpSecret: String = "",
    val totpEnabled: Boolean = false,
    val signedPrekey: String,
    val signedPrekeySignature: String,
    val signedPrekeyId: Int = 1,
    val registrationId: Int = 1,
    val signalDeviceId: Int = 1,
    val kyberPrekey: String = "",
    val kyberPrekeySignature: String = "",
    val kyberPrekeyId: Int = 1,
    val oneTimePrekeys: List<String>,
    val createdAt: Long,
)

@Serializable
data class KeyBundleResponse(
    val username: String,
    val registrationId: Int,
    val deviceId: Int,
    val identityKey: String,
    val signedPrekeyId: Int,
    val signedPrekey: String,
    val signedPrekeySignature: String,
    val kyberPrekeyId: Int = 1,
    val kyberPrekey: String = "",
    val kyberPrekeySignature: String = "",
)

@Serializable
data class UserProfile(
    val username: String,
    val displayName: String,
    val identityKey: String,
    val bio: String = "",
    val verified: Boolean = false,
)

@Serializable
data class SendEnvelopeRequest(
    val id: String,
    val recipient: String,
    val ciphertext: String,
    val sizeClass: Int,
    val ttlSeconds: Long = 30L * 24 * 60 * 60,
    val ackToken: String = "",
    val sealedSender: String = "",
)

@Serializable
data class Envelope(
    val id: String,
    val sender: String,
    val ciphertext: String,
    val sizeClass: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val ackToken: String = "",
    val sealedSender: String = "",
)

@Serializable
data class AckRequest(val ids: List<String>)

@Serializable
data class DiscoveryRequest(val hashes: List<String>)

@Serializable
data class DiscoveryResponse(val usernames: List<String>)

@Serializable
data class ErrorResponse(val error: String, val code: String = "ERROR")

@Serializable
data class TwoFactorSetupResponse(
    val secret: String,
    val otpauthUri: String,
)

@Serializable
data class TwoFactorCodeRequest(val code: String)

@Serializable
data class TwoFactorStatusResponse(val enabled: Boolean)

@Serializable
data class UserState(
    val version: Long = 0,
    val ciphertext: String = "",
    val updatedAt: Long = 0,
)

@Serializable
data class RoomBotRights(
    @SerialName("change_info") val changeInfo: Boolean = false,
    @SerialName("delete_messages") val deleteMessages: Boolean = false,
    @SerialName("ban_users") val banUsers: Boolean = false,
    @SerialName("invite_users") val inviteUsers: Boolean = false,
    @SerialName("pin_messages") val pinMessages: Boolean = false,
    @SerialName("promote_members") val promoteMembers: Boolean = false,
    @SerialName("manage_chat") val manageChat: Boolean = false,
    @SerialName("post_messages") val postMessages: Boolean = false,
)

@Serializable
data class RoomBot(
    val username: String,
    val addedBy: String = "",
    val addedAt: Long = 0,
    val rights: RoomBotRights = RoomBotRights(),
)

@Serializable
data class Room(
    val id: String,
    val username: String,
    val title: String,
    val description: String = "",
    val channel: Boolean = false,
    val owner: String,
    val admins: List<String> = emptyList(),
    val members: List<String> = emptyList(),
    val createdAt: Long = 0,
    /** default emptyList — обратная совместимость со старым JSON. */
    val bots: List<RoomBot> = emptyList(),
    /** username -> timestamp вступления (для inbox новых участников). */
    val memberSince: Map<String, Long> = emptyMap(),
)

@Serializable
data class CreateRoomRequest(
    val title: String,
    val username: String = "",
    val description: String = "",
    val channel: Boolean = false,
    val members: List<String> = emptyList(),
)

@Serializable
data class RoomMemberRequest(
    val username: String,
    val remove: Boolean = false,
)

@Serializable
data class UpdateRoomRequest(
    val title: String? = null,
    val description: String? = null,
)

@Serializable
data class AuthedDevice(val device: Device, val accessToken: String)
