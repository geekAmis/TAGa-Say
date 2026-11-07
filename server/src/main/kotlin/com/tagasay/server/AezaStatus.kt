package com.tagasay.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

@Serializable
data class ServerInfoResponse(
    val relayIp: String = "",
    val paidUntil: String? = null,
    val autoProlong: Boolean? = null,
    val serviceStatus: String? = null,
    val balance: String? = null,
    val currency: String? = null,
    val deletionAt: String? = null,
    val deletionEstimated: Boolean = true,
    val projectServers: Int = 0,
    val aezaAvailable: Boolean = false,
    val checkedAt: Long = 0,
    val error: String? = null,
)

/**
 * Прокси к AEZA API. Ключ хранится только в окружении relay
 * (`AEZA_API_KEY`) и никогда не попадает в APK или Git.
 *
 * Сервер находит свой VPS по публичному IP (`TAGASAY_PUBLIC_IP`),
 * берёт дату оплаты и баланс ЛК. Точную дату удаления провайдер не
 * отдаёт, поэтому она оценивается как expiresAt + 7 дней и помечается
 * `deletionEstimated`.
 */
class AezaStatus(
    private val apiKey: String? = System.getenv("AEZA_API_KEY"),
    private val relayIp: String? = System.getenv("TAGASAY_PUBLIC_IP"),
    private val cacheTtlMs: Long = 10 * 60 * 1000L,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile
    private var cached: ServerInfoResponse? = null

    @Volatile
    private var cachedAt = 0L

    suspend fun status(): ServerInfoResponse {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < cacheTtlMs) return it }
        if (apiKey.isNullOrBlank()) return fallback("AEZA_API_KEY не задан").also {
            cached = it
            cachedAt = now
        }
        val response = runCatching { fetch() }
            .getOrElse { fallback(it.message ?: "AEZA API error") }
        cached = response
        cachedAt = now
        return response
    }

    private suspend fun fetch(): ServerInfoResponse = withContext(Dispatchers.IO) {
        val services = get("/v2/services")
        val items = services["data"]?.jsonObject?.get("items")?.jsonArray
            ?: services["items"]?.jsonArray
            ?: error("unexpected services response")
        val ip = relayIp?.takeIf { it.isNotBlank() }
        val projectItems = items.map { it.jsonObject }.filter { item ->
            ip == null || item["ip"]?.jsonPrimitive?.contentOrNull == ip
        }
        val service = projectItems.firstOrNull()

        val account = get("/v2/accounts/me")
        val balance = account["balance"]?.jsonPrimitive?.contentOrNull
        val currency = account["currency"]?.jsonPrimitive?.contentOrNull
            ?: account["interface"]?.jsonObject?.get("currency")?.jsonPrimitive?.contentOrNull
        val expiresAt = service?.get("expiresAt")?.jsonPrimitive?.contentOrNull
        val deletionAt = expiresAt?.let { plusDays(it, GRACE_DAYS) }

        ServerInfoResponse(
            relayIp = ip ?: service?.get("ip")?.jsonPrimitive?.contentOrNull.orEmpty(),
            paidUntil = expiresAt,
            autoProlong = service?.get("autoProlong")?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull(),
            serviceStatus = service?.get("status")?.jsonPrimitive?.contentOrNull,
            balance = balance,
            currency = currency,
            deletionAt = deletionAt,
            deletionEstimated = true,
            projectServers = projectItems.size,
            aezaAvailable = true,
            checkedAt = System.currentTimeMillis(),
        )
    }

    private fun get(path: String): JsonObject {
        val key = apiKey ?: error("AEZA_API_KEY не задан")
        val request = HttpRequest.newBuilder(URI.create("https://my.aeza.net/api$path"))
            .timeout(Duration.ofSeconds(15))
            .header("X-API-Key", key)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("AEZA ${response.statusCode()}")
        }
        return json.parseToJsonElement(response.body()).jsonObject
    }

    private fun fallback(error: String): ServerInfoResponse = ServerInfoResponse(
        relayIp = relayIp.orEmpty(),
        aezaAvailable = false,
        checkedAt = System.currentTimeMillis(),
        error = error,
    )

    private fun plusDays(iso: String, days: Long): String =
        Instant.parse(iso).plus(Duration.ofDays(days)).toString()

    companion object {
        /** Оценка: провайдер не публикует точный grace-период. */
        const val GRACE_DAYS = 7L
    }
}
