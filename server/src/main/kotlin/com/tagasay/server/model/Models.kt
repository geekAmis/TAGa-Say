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
    val identityKey: String,
    val signedPrekey: String,
    val signedPrekeySignature: String,
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
    val signedPrekey: String,
    val signedPrekeySignature: String,
    val oneTimePrekeys: List<String>,
    val createdAt: Long,
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

data class AuthedDevice(val device: Device, val accessToken: String)
