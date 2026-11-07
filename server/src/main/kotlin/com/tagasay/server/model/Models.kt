package com.tagasay.server.model

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

data class AuthedDevice(val device: Device, val accessToken: String)
