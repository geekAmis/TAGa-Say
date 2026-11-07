package com.tagasay.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class ChallengeRequest(val deviceKey: String)

@Serializable
data class ChallengeResponse(val challenge: String, val expiresAt: Long = 0)

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
    val signingKey: String,
    val signedPrekey: String,
    val signedPrekeySignature: String,
    val signedPrekeyId: Int,
    val registrationId: Int,
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
    val serverSalt: String = "",
    val version: String = "",
)

@Serializable
data class KeyBundleDto(
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
data class SendEnvelopeRequest(
    val id: String,
    val recipient: String,
    val ciphertext: String,
    val sizeClass: Int = 0,
    val ttlSeconds: Long = 30L * 24 * 60 * 60,
    val ackToken: String = "",
)

@Serializable
data class EnvelopeDto(
    val id: String,
    val sender: String,
    val ciphertext: String,
    val sizeClass: Int = 0,
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val ackToken: String = "",
)

@Serializable
data class AckRequest(val ids: List<String>)

@Serializable
data class TwoFactorSetupResponse(val secret: String, val otpauthUri: String)

@Serializable
data class TwoFactorCodeRequest(val code: String)

@Serializable
data class TwoFactorStatusResponse(val enabled: Boolean)

@Serializable
private data class ErrorDto(val error: String = "error", val code: String = "ERROR")

class ApiException(val status: Int, message: String) : IOException(message)

/**
 * HTTP-клиент relay. Все тела — JSON, транспорт в release должен быть HTTPS.
 */
class TagaApi(
    baseUrl: String,
    private val token: String = "",
) {
    val baseUrl: String = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun challenge(signingKeyBase64: String): ChallengeResponse =
        post(
            "/v1/auth/challenge",
            json.encodeToString(ChallengeRequest.serializer(), ChallengeRequest(signingKeyBase64)),
            ChallengeResponse.serializer(),
        )

    suspend fun register(request: RegisterRequest): RegisterResponse =
        post(
            "/v1/auth/register",
            json.encodeToString(RegisterRequest.serializer(), request),
            RegisterResponse.serializer(),
        )

    suspend fun keyBundle(username: String): KeyBundleDto =
        get("/v1/keys/${username.trimStart('@')}", KeyBundleDto.serializer())

    suspend fun sendEnvelope(request: SendEnvelopeRequest): Unit {
        postUnit(
            "/v1/envelope",
            json.encodeToString(SendEnvelopeRequest.serializer(), request),
        )
    }

    suspend fun fetchEnvelopes(limit: Int = 100): List<EnvelopeDto> =
        get("/v1/envelope?limit=$limit", ListSerializer(EnvelopeDto.serializer()))

    suspend fun ack(ids: List<String>) {
        if (ids.isEmpty()) return
        postUnit("/v1/envelope/ack", json.encodeToString(AckRequest.serializer(), AckRequest(ids)))
    }

    suspend fun setup2fa(): TwoFactorSetupResponse =
        post("/v1/auth/2fa/setup", "{}", TwoFactorSetupResponse.serializer())

    suspend fun enable2fa(code: String): TwoFactorStatusResponse =
        post(
            "/v1/auth/2fa/enable",
            json.encodeToString(TwoFactorCodeRequest.serializer(), TwoFactorCodeRequest(code)),
            TwoFactorStatusResponse.serializer(),
        )

    suspend fun disable2fa(code: String): TwoFactorStatusResponse =
        post(
            "/v1/auth/2fa/disable",
            json.encodeToString(TwoFactorCodeRequest.serializer(), TwoFactorCodeRequest(code)),
            TwoFactorStatusResponse.serializer(),
        )

    suspend fun twoFactorStatus(): TwoFactorStatusResponse =
        get("/v1/auth/2fa/status", TwoFactorStatusResponse.serializer())

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .get()
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .build()
            execute(request, serializer)
        }

    private suspend fun <T> post(path: String, payload: String, serializer: KSerializer<T>): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .post(payload.toRequestBody(JSON_MEDIA))
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .build()
            execute(request, serializer)
        }

    private suspend fun postUnit(path: String, payload: String): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(payload.toRequestBody(JSON_MEDIA))
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.decodeFromString(ErrorDto.serializer(), text).error
                }.getOrDefault(text.take(200))
                throw ApiException(response.code, message)
            }
        }
    }

    private fun <T> execute(request: Request, serializer: KSerializer<T>): T =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.decodeFromString(ErrorDto.serializer(), text).error
                }.getOrDefault(text.take(200))
                throw ApiException(response.code, message)
            }
            json.decodeFromString(serializer, text)
        }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
