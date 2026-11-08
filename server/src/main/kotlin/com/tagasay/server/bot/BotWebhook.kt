package com.tagasay.server.bot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook-доставка (ADR-007).
 *
 * - HTTPS-only, порт 443/8443, без userinfo;
 * - SSRF: DNS резолвится при установке и повторно перед каждой доставкой;
 *   loopback/private/link-local/multicast/metadata/ULA запрещены;
 * - redirect не выполняется (3xx = ошибка), таймаут 5 с;
 * - HMAC `sha256=HMAC(secret, "$timestamp.$body")` и secret-заголовок;
 * - 3 попытки с экспоненциальной задержкой, затем `last_error`;
 *   updates не теряются и остаются в очереди.
 */
class BotWebhookDelivery(
    private val store: BotStore,
    private val secrets: BotSecrets,
    private val allowPrivateHosts: Boolean = false,
    private val retryDelaysMs: List<Long> = listOf(0, 2_000, 8_000, 20_000),
    private val timeoutMs: Long = 5_000,
    private val autoStart: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(BotWebhook::class.java)
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var started = false

    data class DeliveryReport(val delivered: Int, val failed: Int, val lastError: String = "")

    fun start() {
        if (!autoStart || started) return
        started = true
        scope.launch {
            while (isActive) {
                runCatching { pump() }
                delay(WEBHOOK_PUMP_INTERVAL_MS)
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun pump() {
        for (botId in store.webhookBotIds()) {
            runCatching { deliverPending(botId) }
                .onFailure { log.warn("webhook delivery failed: {}", it.javaClass.simpleName) }
        }
    }

    /** Проверка URL при setWebhook и повторно при каждой доставке. */
    fun validateUrl(raw: String): URI {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw BotApiException(400, "invalid webhook url")
        val schemeOk = uri.scheme.equals("https", ignoreCase = true) ||
            (allowPrivateHosts && uri.scheme.equals("http", ignoreCase = true))
        if (!schemeOk) {
            throw BotApiException(400, "webhook url must use HTTPS")
        }
        if (uri.userInfo != null) throw BotApiException(400, "webhook url must not contain userinfo")
        if (uri.fragment != null) throw BotApiException(400, "webhook url must not contain a fragment")
        val port = uri.port
        if (!allowPrivateHosts && port != -1 && port != 443 && port != 8443) {
            throw BotApiException(400, "webhook port must be 443 or 8443")
        }
        val host = uri.host?.trim()?.lowercase()
        if (host.isNullOrBlank()) throw BotApiException(400, "webhook host is required")
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrElse {
            throw BotApiException(400, "webhook host cannot be resolved")
        }
        if (addresses.isEmpty()) throw BotApiException(400, "webhook host cannot be resolved")
        if (!allowPrivateHosts && addresses.any { isForbiddenAddress(it) }) {
            throw BotApiException(400, "webhook host resolves to a private or reserved address")
        }
        return uri
    }

    suspend fun deliverPending(botId: String): DeliveryReport {
        val hook = store.webhook(botId) ?: return DeliveryReport(0, 0)
        val now = System.currentTimeMillis()
        if (hook.nextAttemptAt > now) return DeliveryReport(0, 0)
        val bot = store.bot(botId) ?: run {
            store.deleteWebhook(botId)
            return DeliveryReport(0, 0)
        }
        val pending = store.webhookPending(botId, limit = 20, allowed = hook.allowedUpdates.toSet())
        if (pending.isEmpty()) {
            if (hook.lastError.isNotBlank() || hook.failureCount != 0) {
                store.updateWebhook(botId) { it.copy(lastError = "", failureCount = 0, nextAttemptAt = 0) }
            }
            return DeliveryReport(0, 0)
        }
        var delivered = 0
        var failed = 0
        var lastError = ""
        for (update in pending) {
            // Повторная проверка DNS перед каждой доставкой (DNS rebinding).
            val validation = runCatching { validateUrl(hook.url) }
            val uri = validation.getOrNull()
            if (uri == null) {
                lastError = validation.exceptionOrNull()?.message ?: "webhook url rejected"
                failed++
                break
            }
            val callbackMessage = update.callbackQuery?.let { store.messageById(botId, it.messageId) }
            val body = BotJson.update(bot, update, callbackMessage).toString()
            val attemptResult = attempt(uri, body, hook)
            if (attemptResult == null) {
                store.acknowledge(botId, update.updateId + 1)
                store.updateWebhook(botId) {
                    it.copy(
                        lastError = "",
                        failureCount = 0,
                        nextAttemptAt = 0,
                        lastSuccessAt = System.currentTimeMillis(),
                    )
                }
                delivered++
            } else {
                failed++
                lastError = attemptResult
                val backoff = (retryDelaysMs.lastOrNull() ?: 1_000L).coerceAtLeast(1_000L)
                store.updateWebhook(botId) {
                    it.copy(
                        lastError = attemptResult,
                        failureCount = it.failureCount + 1,
                        lastErrorAt = System.currentTimeMillis(),
                        nextAttemptAt = System.currentTimeMillis() + backoff,
                    )
                }
                break
            }
        }
        return DeliveryReport(delivered, failed, lastError)
    }

    /** Возвращает null при 2xx, иначе текст ошибки. */
    private suspend fun attempt(uri: URI, body: String, hook: BotWebhook): String? {
        val secret = hook.secretCiphertext.takeIf { it.isNotBlank() }?.let { secrets.decrypt(it) }
        for (index in retryDelaysMs.indices) {
            if (index > 0 && retryDelaysMs[index] > 0) delay(retryDelaysMs[index])
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("X-TAGa-Say-Timestamp", timestamp)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            if (!secret.isNullOrBlank()) {
                builder.header("X-TAGa-Say-Bot-Api-Secret-Token", secret)
                builder.header("X-TAGa-Say-Signature", "sha256=" + sign(secret, timestamp, body))
            }
            val error = runCatching {
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
                val code = response.statusCode()
                when {
                    code in 200..299 -> null
                    code in 300..399 -> "webhook responded $code: redirects are not allowed"
                    else -> "webhook responded $code"
                }
            }.getOrElse { e ->
                "webhook request failed: ${e.javaClass.simpleName}"
            }
            if (error == null) return null
            if (index == retryDelaysMs.lastIndex) return error
        }
        return "webhook delivery failed"
    }

    fun sign(secret: String, timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.$body".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isForbiddenAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if (first == 0) return true
            if (first == 100 && second in 64..127) return true // CGNAT 100.64/10
            if (first == 169 && second == 254) return true // link-local/metadata
            if (first == 198 && second in 18..19) return true // benchmarking
            if (first == 192 && second == 0 && (bytes[2].toInt() and 0xFF) == 0) return true
            if (first == 255 && second == 255) return true
        } else if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xFF
            if ((first and 0xFE) == 0xFC) return true // IPv6 ULA fc00::/7
            if (first == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80) return true // fe80::/10
        }
        return false
    }

}

private const val WEBHOOK_PUMP_INTERVAL_MS = 1_000L