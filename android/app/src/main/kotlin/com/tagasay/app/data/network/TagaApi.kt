package com.tagasay.app.data.network

import com.tagasay.app.data.model.BotCallbackRequest
import com.tagasay.app.data.model.BotInboxEvent
import com.tagasay.app.data.model.BotInfo
import com.tagasay.app.data.model.BotSendRequest
import com.tagasay.app.data.model.BotSendResponse
import com.tagasay.app.data.model.BotTokenResponse
import com.tagasay.app.data.model.BotUpdateRequest
import com.tagasay.app.data.model.CreateBotRequest
import com.tagasay.app.data.model.CreateBotResponse
import com.tagasay.app.data.model.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

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
    val sealedSender: String = "",
)

@Serializable
data class EnvelopeDto(
    val id: String,
    val sender: String = "",
    val ciphertext: String,
    val sizeClass: Int = 0,
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val ackToken: String = "",
    val sealedSender: String = "",
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
data class MediaUploadResponse(
    val id: String,
    val sha256: String = "",
    val size: Long = 0,
    val expiresAt: Long = 0,
)

@Serializable
data class StateDto(
    val version: Long = 0,
    val ciphertext: String = "",
    val updatedAt: Long = 0,
)

@Serializable
data class ProfileUpdateRequest(
    val displayName: String,
    val bio: String,
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
private data class ErrorDto(val error: String = "error", val code: String = "ERROR")

class ApiException(val status: Int, message: String) : IOException(message)

/**
 * HTTP-РєР»РёРµРЅС‚ relay. Р’СЃРµ С‚РµР»Р° вЂ” JSON, С‚СЂР°РЅСЃРїРѕСЂС‚ РІ release РґРѕР»Р¶РµРЅ Р±С‹С‚СЊ HTTPS.
 */
class TagaApi(
    baseUrl: String,
    private val token: String = "",
) {
    val baseUrl: String = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = RelayHttp.forBase(this.baseUrl)

    /**
     * РћС‚РґРµР»СЊРЅС‹Р№ РєР»РёРµРЅС‚ РґР»СЏ РІР»РѕР¶РµРЅРёР№: РїРµСЂРµРґР°С‡Р° С„Р°Р№Р»Р° РјРѕР¶РµС‚ РёРґС‚Рё РґРѕР»СЊС€Рµ
     * РѕР±С‹С‡РЅРѕРіРѕ JSON-Р·Р°РїСЂРѕСЃР°, РїРѕСЌС‚РѕРјСѓ С‚Р°Р№РјР°СѓС‚С‹ СѓРІРµР»РёС‡РµРЅС‹.
     */
    private val transferClient = RelayHttp.transferForBase(this.baseUrl)

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

    /** Р—Р°С€РёС„СЂРѕРІР°РЅРЅРѕРµ СЃРѕСЃС‚РѕСЏРЅРёРµ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ (РїСЂРѕС„РёР»СЊ, РЅР°СЃС‚СЂРѕР№РєРё, РїР°РїРєРё, РёСЃС‚РѕСЂРёРё). */
    suspend fun getState(): StateDto = get("/v1/state", StateDto.serializer())

    suspend fun putState(ciphertext: String, updatedAt: Long): StateDto =
        put(
            "/v1/state",
            json.encodeToString(
                StateDto.serializer(),
                StateDto(ciphertext = ciphertext, updatedAt = updatedAt),
            ),
            StateDto.serializer(),
        )

    /** РћР±РЅРѕРІР»СЏРµС‚ РїСѓР±Р»РёС‡РЅРѕРµ РёРјСЏ Рё В«Рћ СЃРµР±РµВ» РІ directory. */
    suspend fun updateProfile(displayName: String, bio: String) {
        postUnit(
            "/v1/profile",
            json.encodeToString(
                ProfileUpdateRequest.serializer(),
                ProfileUpdateRequest(displayName, bio),
            ),
        )
    }

    // ----------------------------------------------------------------- rooms

    suspend fun createRoom(request: CreateRoomRequest): com.tagasay.app.data.model.GroupRoom =
        post(
            "/v1/rooms",
            json.encodeToString(CreateRoomRequest.serializer(), request),
            com.tagasay.app.data.model.GroupRoom.serializer(),
        )

    suspend fun rooms(query: String = ""): List<com.tagasay.app.data.model.GroupRoom> =
        get(
            if (query.isBlank()) "/v1/rooms" else "/v1/rooms?query=${java.net.URLEncoder.encode(query, "UTF-8")}",
            ListSerializer(com.tagasay.app.data.model.GroupRoom.serializer()),
        )

    suspend fun room(id: String): com.tagasay.app.data.model.GroupRoom =
        get("/v1/rooms/$id", com.tagasay.app.data.model.GroupRoom.serializer())

    suspend fun joinRoom(id: String): com.tagasay.app.data.model.GroupRoom =
        post(
            "/v1/rooms/$id/join",
            "{}",
            com.tagasay.app.data.model.GroupRoom.serializer(),
        )

    suspend fun leaveRoom(id: String) {
        postUnit("/v1/rooms/$id/leave", "{}")
    }

    suspend fun changeRoomMember(
        id: String,
        username: String,
        remove: Boolean,
    ): com.tagasay.app.data.model.GroupRoom =
        post(
            "/v1/rooms/$id/members",
            json.encodeToString(
                RoomMemberRequest.serializer(),
                RoomMemberRequest(username, remove),
            ),
            com.tagasay.app.data.model.GroupRoom.serializer(),
        )

    suspend fun updateRoom(
        id: String,
        title: String? = null,
        description: String? = null,
    ): com.tagasay.app.data.model.GroupRoom =
        post(
            "/v1/rooms/$id/update",
            json.encodeToString(
                UpdateRoomRequest.serializer(),
                UpdateRoomRequest(title, description),
            ),
            com.tagasay.app.data.model.GroupRoom.serializer(),
        )

    // ------------------------------------------------------------------ bots

    /** РљР°С‚Р°Р»РѕРі/РїРѕРёСЃРє Р±РѕС‚РѕРІ. РЎРµСЂРІРµСЂ РјРѕР¶РµС‚ РІРµСЂРЅСѓС‚СЊ РјР°СЃСЃРёРІ РёР»Рё {bots:[вЂ¦]}. */
    suspend fun bots(query: String = ""): List<BotInfo> {
        val path = if (query.isBlank()) {
            "/v1/bots"
        } else {
            "/v1/bots?query=${java.net.URLEncoder.encode(query, "UTF-8")}"
        }
        return parseBotList(getRaw(path))
    }

    suspend fun myBots(): List<BotInfo> = parseBotList(getRaw("/v1/bots/mine"))

    suspend fun bot(username: String): BotInfo =
        json.decodeFromString(
            BotInfo.serializer(),
            getRaw("/v1/bots/${enc(username)}"),
        )

    suspend fun createBot(name: String, username: String): CreateBotResponse =
        post(
            "/v1/bots",
            json.encodeToString(CreateBotRequest.serializer(), CreateBotRequest(name, username)),
            CreateBotResponse.serializer(),
        )

    /** РЎРѕРѕР±С‰РµРЅРёРµ Р±РѕС‚Сѓ РѕС‚ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ. */
    suspend fun sendBotMessage(username: String, request: BotSendRequest): BotSendResponse =
        post(
            "/v1/bots/${enc(username)}/send",
            json.encodeToString(BotSendRequest.serializer(), request),
            BotSendResponse.serializer(),
        )

    /** Long-poll СЃРѕР±С‹С‚РёР№ Р±РѕС‚-С‡Р°С‚РѕРІ. */
    suspend fun botInbox(since: Long, timeout: Int = 0, limit: Int = 100): List<BotInboxEvent> =
        json.decodeFromString(
            ListSerializer(BotInboxEvent.serializer()),
            getRaw("/v1/bots/inbox?since=$since&timeout=$timeout&limit=$limit"),
        )

    suspend fun botCallback(
        username: String,
        messageId: String,
        data: String,
        callbackQueryId: String = "",
    ) {
        postUnit(
            "/v1/bots/${enc(username)}/callback",
            json.encodeToString(
                BotCallbackRequest.serializer(),
                BotCallbackRequest(messageId, data, callbackQueryId),
            ),
        )
    }

    suspend fun blockBot(username: String) {
        postUnit("/v1/bots/${enc(username)}/block", "{}")
    }

    suspend fun unblockBot(username: String) {
        deleteUnit("/v1/bots/${enc(username)}/block")
    }

    /** РўРѕРєРµРЅ РґРѕСЃС‚СѓРїРµРЅ С‚РѕР»СЊРєРѕ РІР»Р°РґРµР»СЊС†Сѓ (СЏРІРЅРѕРµ РґРµР№СЃС‚РІРёРµ). */
    suspend fun botToken(username: String): String =
        parseToken(getRaw("/v1/bots/${enc(username)}/token", method = "POST", payload = "{}"))

    suspend fun revokeBotToken(username: String): String =
        parseToken(getRaw("/v1/bots/${enc(username)}/revoke", method = "POST", payload = "{}"))

    suspend fun updateBot(username: String, request: BotUpdateRequest): BotInfo =
        post(
            "/v1/bots/${enc(username)}/update",
            json.encodeToString(BotUpdateRequest.serializer(), request),
            BotInfo.serializer(),
        )

    suspend fun deleteBot(username: String) {
        deleteUnit("/v1/bots/${enc(username)}")
    }

    /** `[~]` РІ 1.2.0: СЃРµСЂРІРµСЂ С‡РµСЃС‚РЅРѕ РѕС‚РІРµС‡Р°РµС‚ 400 unsupported. */
    suspend fun botStats(username: String): JsonElement =
        parseJson(getRaw("/v1/bots/${enc(username)}/stats"))

    /** `[~]` РІ 1.2.0: inline-СЂРµР¶РёРј РµС‰С‘ РЅРµ РІРєР»СЋС‡С‘РЅ. */
    suspend fun botInline(username: String, payload: String = "{}"): JsonElement =
        parseJson(getRaw("/v1/bots/${enc(username)}/inline", method = "POST", payload = payload))

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value.trimStart('@'), "UTF-8")

    private fun parseJson(text: String): JsonElement =
        runCatching { json.parseToJsonElement(text) }.getOrDefault(JsonObject(emptyMap()))

    private fun parseBotList(text: String): List<BotInfo> {
        val element = parseJson(text)
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> {
                when {
                    element["bots"] is JsonArray -> element["bots"] as JsonArray
                    element["result"] is JsonArray -> element["result"] as JsonArray
                    element["items"] is JsonArray -> element["items"] as JsonArray
                    else -> JsonArray(emptyList())
                }
            }
            else -> JsonArray(emptyList())
        }
        return json.decodeFromString(ListSerializer(BotInfo.serializer()), array.toString())
    }

    private fun parseToken(text: String): String {
        val element = parseJson(text)
        val token = when (element) {
            is JsonObject -> element["token"]?.jsonPrimitive?.content.orEmpty()
            else -> element.jsonPrimitive.contentOrNullSafe().orEmpty()
        }
        if (token.isBlank()) throw ApiException(500, "server returned no bot token")
        return token
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()

    /** РЎРѕСЃС‚РѕСЏРЅРёРµ relay Рё Р›Рљ AEZA (РїСЂРѕРєСЃРёСЂСѓРµС‚СЃСЏ СЃРµСЂРІРµСЂРѕРј). */
    suspend fun serverInfo(): ServerInfo =
        get("/v1/server/status", ServerInfo.serializer())

    /**
     * Р—Р°РіСЂСѓР¶Р°РµС‚ ciphertext РІР»РѕР¶РµРЅРёСЏ. РЎРµСЂРІРµСЂ РїРѕР»СѓС‡Р°РµС‚ С‚РѕР»СЊРєРѕ С€РёС„СЂРѕС‚РµРєСЃС‚.
     */
    suspend fun uploadMedia(ciphertext: ByteArray, sha256Hex: String): MediaUploadResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/v1/media")
                .post(ciphertext.toRequestBody(OCTET_MEDIA))
                .header("X-TAGa-Say-Sha256", sha256Hex)
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .build()
            transferClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(response.code, errorMessage(text))
                json.decodeFromString(MediaUploadResponse.serializer(), text)
            }
        }

    suspend fun downloadMedia(id: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/v1/media/$id")
            .get()
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        transferClient.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) throw ApiException(response.code, errorMessage(bytes.decodeToString()))
            bytes
        }
    }

    private fun errorMessage(text: String): String = runCatching {
        json.decodeFromString(ErrorDto.serializer(), text).error
    }.getOrDefault(text.take(200))

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

    private suspend fun <T> put(path: String, payload: String, serializer: KSerializer<T>): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .put(payload.toRequestBody(JSON_MEDIA))
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .build()
            execute(request, serializer)
        }

    /** РЎС‹СЂРѕР№ JSON-РѕС‚РІРµС‚: РЅСѓР¶РµРЅ С‚Р°Рј, РіРґРµ С„РѕСЂРјР° РЅРµ РіР°СЂР°РЅС‚РёСЂРѕРІР°РЅР° РєРѕРЅС‚СЂР°РєС‚РѕРј. */
    private suspend fun getRaw(
        path: String,
        method: String = "GET",
        payload: String = "",
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
        val request = when (method.uppercase()) {
            "POST" -> builder.post(payload.ifEmpty { "{}" }.toRequestBody(JSON_MEDIA)).build()
            "DELETE" -> builder.delete().build()
            else -> builder.get().build()
        }
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, errorMessage(text))
            text
        }
    }

    private suspend fun deleteUnit(path: String): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .delete()
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, errorMessage(text))
        }
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
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()
    }
}

