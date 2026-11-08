package com.tagasay.server.bot

import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Мастер-ключ токенов (ADR-005): из `TAGASAY_BOT_TOKEN_KEY` через HKDF
 * выводятся независимые ключи HMAC-pepper и AES-256-GCM.
 *
 * Production (TAGASAY_ENV=production) без ключа — fail-fast. Debug/локально
 * используется детерминированный ключ, чтобы dev-сервер и тесты переживали
 * рестарт. Сам ключ никогда не логируется.
 */
class BotSecrets(master: ByteArray) {

    private val salt = "taga-bot-token-salt-v1".toByteArray(StandardCharsets.UTF_8)
    private val hmacKey = hkdf(master, salt, "taga/bot/token/hmac")
    private val encryptionKey = hkdf(master, salt, "taga/bot/token/enc")

    fun hmac(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    fun encrypt(value: String): String {
        val iv = ByteArray(12)
        RANDOM.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv + ciphertext)
    }

    fun decrypt(value: String): String? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(value)
        if (bytes.size <= 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(encryptionKey, "AES"),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
        )
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info.toByteArray(StandardCharsets.UTF_8))
        mac.update(1)
        return mac.doFinal()
    }

    companion object {
        private val RANDOM = SecureRandom()
        private const val DEV_KEY = "taga-say-dev-bot-token-key-not-for-production-v1"

        fun fromEnv(): BotSecrets {
            val raw = System.getenv("TAGASAY_BOT_TOKEN_KEY")
            val production = System.getenv("TAGASAY_ENV").equals("production", ignoreCase = true) ||
                System.getenv("TAGASAY_PRODUCTION") == "1"
            if (!raw.isNullOrBlank()) {
                require(raw.length >= 32) { "TAGASAY_BOT_TOKEN_KEY must be at least 32 characters" }
                return BotSecrets(raw.toByteArray(StandardCharsets.UTF_8))
            }
            check(!production) {
                "TAGASAY_BOT_TOKEN_KEY is required in production (server must not start without it)"
            }
            LoggerFactory.getLogger(BotSecrets::class.java)
                .warn("TAGASAY_BOT_TOKEN_KEY is not set; using deterministic development key")
            return BotSecrets(DEV_KEY.toByteArray(StandardCharsets.UTF_8))
        }
    }
}

class BotAuth(
    private val store: BotStore,
    private val secrets: BotSecrets,
) {
    private val random = SecureRandom()

    sealed class Result {
        data class Ok(val bot: Bot, val tokenId: String) : Result()
        data class Denied(val code: Int, val description: String) : Result()
    }

    /** Выпускает новый токен `taga_bot_<numericId>.<secret>` и сохраняет HMAC+шифртекст. */
    fun issueToken(bot: Bot): String {
        val secretBytes = ByteArray(32)
        random.nextBytes(secretBytes)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
        val token = "taga_bot_${bot.numericId}.$secret"
        store.addToken(
            BotToken(
                id = "tok_" + randomHex(8),
                botId = bot.id,
                tokenHash = hash(token),
                tokenCiphertext = secrets.encrypt(token),
                createdAt = System.currentTimeMillis(),
            ),
        )
        return token
    }

    /** Отзывает все активные токены и выпускает новый. */
    fun rotateToken(bot: Bot): String {
        store.revokeTokens(bot.id, System.currentTimeMillis())
        return issueToken(bot)
    }

    /** Расшифровывает последний выпущенный токен для явного показа владельцу. */
    fun revealToken(bot: Bot): String? {
        val token = store.tokensFor(bot.id)
            .filter { it.tokenCiphertext.isNotBlank() }
            .maxByOrNull { it.createdAt }
            ?: return null
        return secrets.decrypt(token.tokenCiphertext)
    }

    /** Подпись callback_data BotFather: владелец|действие|botId (не доверяем кнопке). */
    fun callbackSignature(owner: String, action: String, botId: String): String {
        val hash = secrets.hmac("${owner.lowercase()}|$action|$botId")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash.copyOf(8))
    }

    fun verifyCallbackSignature(owner: String, action: String, botId: String, signature: String): Boolean {
        val expected = callbackSignature(owner, action, botId)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            signature.toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun authenticate(authorization: String?): Result {
        if (authorization.isNullOrBlank()) {
            return Result.Denied(401, "missing bot token")
        }
        if (!authorization.startsWith("Bearer ", ignoreCase = true)) {
            return Result.Denied(401, "invalid authorization header")
        }
        val raw = authorization.substringAfter(' ').trim()
        val match = TOKEN_PATTERN.matchEntire(raw)
            ?: return Result.Denied(401, "invalid bot token")
        val numericId = match.groupValues[1].toLongOrNull()
            ?: return Result.Denied(401, "invalid bot token")
        val bot = store.botByNumeric(numericId)
            ?: return Result.Denied(401, "invalid bot token")
        if (bot.status != "active") {
            return Result.Denied(401, "bot is deleted")
        }
        val hash = hash(raw)
        val token = store.tokensFor(bot.id).firstOrNull { constantTimeEquals(it.tokenHash, hash) }
        if (token == null) {
            return Result.Denied(401, "invalid bot token")
        }
        if (token.revokedAt > 0) {
            return Result.Denied(401, "bot token revoked")
        }
        store.markTokenUsed(bot.id, token.id, System.currentTimeMillis())
        return Result.Ok(bot, token.id)
    }

    private fun hash(token: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(secrets.hmac(token))

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val TOKEN_PATTERN = Regex("^taga_bot_(\\d{1,18})\\.([A-Za-z0-9_-]{20,})$")
    }
}
