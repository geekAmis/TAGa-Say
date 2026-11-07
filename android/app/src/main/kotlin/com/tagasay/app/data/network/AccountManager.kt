package com.tagasay.app.data.network

import android.content.Context
import android.util.Base64
import com.tagasay.app.data.crypto.SignalStore
import com.tagasay.app.data.local.DeviceKeyStore
import com.tagasay.core.crypto.CryptoEngine
import com.tagasay.core.crypto.toHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class Session(
    val serverUrl: String,
    val username: String,
    val accessToken: String,
    val deliveryToken: String,
    val passwordVerifier: String = "",
)

/**
 * Регистрация устройства и хранение сессии.
 *
 * Аккаунт: username + password (Argon2id-verifier) + скрытый device hash.
 * Challenge Ed25519 подтверждает владение устройством, но не заменяет пароль.
 */
class AccountManager(
    context: Context,
    private val deviceKeyStore: DeviceKeyStore,
    private val signalStore: SignalStore,
    private val crypto: CryptoEngine = CryptoEngine(),
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun currentSession(): Session? {
        val payload = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            json.decodeFromString<Session>(
                deviceKeyStore.decryptLocal(payload, AAD).decodeToString(),
            )
        }.getOrNull()
    }

    fun save(session: Session) {
        val encrypted = deviceKeyStore.encryptLocal(
            json.encodeToString(Session.serializer(), session).encodeToByteArray(),
            AAD,
        )
        prefs.edit().putString(KEY_SESSION, encrypted).apply()
    }

    fun clear() {
        // Сохраняем pending verifier: он нужен для повторной регистрации после
        // перезапуска сервера/инвалидации токена.
        prefs.edit().remove(KEY_SESSION).apply()
    }

    fun passwordVerifier(username: String, password: String): String {
        val salt = crypto.sha256("taga/password/v1:${username.trimStart('@').lowercase()}".toByteArray())
        return Base64.encodeToString(
            crypto.argon2id(
                passphrase = password.toByteArray(),
                salt = salt.copyOf(16),
                memoryBytes = 64L * 1024 * 1024,
                iterations = 3,
            ).hash,
            Base64.NO_WRAP,
        )
    }

    fun deviceHash(serverUrl: String, username: String): String =
        crypto.sha256Hex(
            deviceKeyStore.deviceSecret() +
                "taga-device/v1".toByteArray() +
                serverUrl.trimEnd('/').toByteArray() +
                username.trimStart('@').lowercase().toByteArray(),
        )

    private fun pendingVerifierKey(serverUrl: String, username: String) =
        "pending:${serverUrl.trimEnd('/')}:${username.trimStart('@').lowercase()}"

    private fun rememberPending(serverUrl: String, username: String, verifier: String) {
        val encrypted = deviceKeyStore.encryptLocal(verifier.toByteArray(), PENDING_AAD)
        prefs.edit().putString(pendingVerifierKey(serverUrl, username), encrypted).apply()
    }

    private fun loadPending(serverUrl: String, username: String): String? {
        val payload = prefs.getString(pendingVerifierKey(serverUrl, username), null) ?: return null
        return runCatching {
            deviceKeyStore.decryptLocal(payload, PENDING_AAD).decodeToString()
        }.getOrNull()
    }

    suspend fun ensureRegistered(
        serverUrl: String,
        username: String,
        displayName: String,
        password: String = "",
    ): Session {
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        require(normalizedUrl.isNotBlank()) { "Server URL is empty" }
        val cleanUsername = username.trimStart('@').lowercase()
        currentSession()?.let { existing ->
            if (existing.serverUrl == normalizedUrl && existing.username == cleanUsername) {
                return existing
            }
        }

        val verifier = if (password.isNotBlank()) {
            passwordVerifier(cleanUsername, password)
        } else {
            loadPending(normalizedUrl, cleanUsername)
                ?: error("Password is required for this account")
        }
        rememberPending(normalizedUrl, cleanUsername, verifier)
        val deviceHash = deviceHash(normalizedUrl, cleanUsername)

        val api = TagaApi(normalizedUrl)
        val auth = deviceKeyStore.ensureIdentity()
        val signingKey = Base64.encodeToString(auth.publicKey, Base64.NO_WRAP)
        val challenge = api.challenge(signingKey)
        val signature = crypto.signDetached(challenge.challenge.toByteArray(), auth.secretKey)

        val response = api.register(
            RegisterRequest(
                deviceId = UUID.randomUUID().toString(),
                username = cleanUsername,
                displayName = displayName.take(64).ifBlank { cleanUsername },
                phoneHash = null,
                passwordVerifier = verifier,
                deviceHash = deviceHash,
                identityKey = signalStore.identityPublicBase64(),
                signingKey = signingKey,
                signedPrekey = signalStore.signedPreKeyPublicBase64(),
                signedPrekeySignature = signalStore.signedPreKeySignatureBase64(),
                signedPrekeyId = signalStore.signedPreKeyId(),
                registrationId = signalStore.registrationIdValue(),
                signalDeviceId = 1,
                kyberPrekey = signalStore.kyberPreKeyPublicBase64(),
                kyberPrekeySignature = signalStore.kyberPreKeySignatureBase64(),
                kyberPrekeyId = signalStore.kyberPreKeyId(),
                challenge = challenge.challenge,
                challengeSignature = Base64.encodeToString(signature, Base64.NO_WRAP),
            ),
        )

        val session = Session(
            serverUrl = normalizedUrl,
            username = cleanUsername,
            accessToken = response.accessToken,
            deliveryToken = response.deliveryToken,
            passwordVerifier = verifier,
        )
        save(session)
        return session
    }

    companion object {
        private const val PREFS = "taga_session"
        private const val KEY_SESSION = "session_v1"
        private const val AAD = "taga/session/v1"
        private const val PENDING_AAD = "taga/pending/v1"
    }
}
