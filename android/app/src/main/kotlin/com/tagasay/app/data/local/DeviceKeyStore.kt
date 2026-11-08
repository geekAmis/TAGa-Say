package com.tagasay.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tagasay.core.crypto.CryptoEngine
import com.tagasay.core.crypto.hexToBytes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Аппаратная привязка ключей.
 *
 * Keystore генерирует неизвлекаемый AES-256-GCM ключ, которым оборачивается
 * случайный device secret. Сам device secret никогда не хранится в открытом виде.
 *
 * Мастер-ключ выводится как HKDF(device_secret, server_salt, "taga/master/v1").
 */
class DeviceKeyStore(
    context: Context,
    private val crypto: CryptoEngine = CryptoEngine(),
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceSecret(): ByteArray {
        val wrapped = prefs.getString(KEY_DEVICE_SECRET, null)
        if (wrapped != null) {
            return unwrap(Base64.decode(wrapped, Base64.NO_WRAP))
        }
        val secret = crypto.randomBytes(32)
        prefs.edit()
            .putString(KEY_DEVICE_SECRET, Base64.encodeToString(wrap(secret), Base64.NO_WRAP))
            .apply()
        return secret
    }

    fun masterKey(): ByteArray {
        val salt = SERVER_SALT.hexToBytes()
        return crypto.deriveMasterKey(deviceSecret(), salt)
    }

    fun encryptLocal(plaintext: ByteArray, aad: String): String {
        val sealed = crypto.aesGcmEncrypt(masterKey(), plaintext, aad.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun decryptLocal(payload: String, aad: String): ByteArray {
        val sealed = Base64.decode(payload, Base64.NO_WRAP)
        return crypto.aesGcmDecrypt(masterKey(), sealed, aad.toByteArray())
    }

    /**
     * Случайный 256-битный AccountMasterKey. Хранится только в обёрнутом виде
     * на аппаратном ключе Keystore и никогда не уходит на сервер.
     */
    fun accountMasterKey(accountId: String): ByteArray {
        val key = "account_master:$accountId"
        prefs.getString(key, null)?.let { stored ->
            return decryptLocal(stored, ACCOUNT_MASTER_AAD)
        }
        val next = crypto.randomBytes(32)
        prefs.edit()
            .putString(key, encryptLocal(next, ACCOUNT_MASTER_AAD))
            .apply()
        return next
    }

    fun ensureIdentity(): Identity {
        prefs.getString(KEY_IDENTITY_PUBLIC, null)?.let { pub ->
            val secret = prefs.getString(KEY_IDENTITY_SECRET, null) ?: return createIdentity()
            return Identity(
                publicKey = Base64.decode(pub, Base64.NO_WRAP),
                secretKey = unwrap(Base64.decode(secret, Base64.NO_WRAP)),
            )
        }
        return createIdentity()
    }

    fun fingerprint(): String = crypto.fingerprint(ensureIdentity().publicKey)

    /**
     * Уничтожает локальный ключевой материал: device secret, identity key и
     * аппаратный wrapping-ключ. После этого старый vault не расшифровать.
     */
    fun wipe() {
        prefs.edit().clear().apply()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun createIdentity(): Identity {
        val pair = crypto.generateSigningKeyPair()
        prefs.edit()
            .putString(KEY_IDENTITY_PUBLIC, Base64.encodeToString(pair.publicKey, Base64.NO_WRAP))
            .putString(
                KEY_IDENTITY_SECRET,
                Base64.encodeToString(wrap(pair.secretKey), Base64.NO_WRAP),
            )
            .apply()
        return Identity(pair.publicKey, pair.secretKey)
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun unwrap(payload: ByteArray): ByteArray {
        require(payload.size > 12) { "invalid wrapped key" }
        val iv = payload.copyOfRange(0, 12)
        val body = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    data class Identity(val publicKey: ByteArray, val secretKey: ByteArray)

    companion object {
        private const val PREFS = "taga_key_material"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "taga_master_wrap_v1"
        private const val KEY_DEVICE_SECRET = "device_secret_wrapped"
        private const val KEY_IDENTITY_PUBLIC = "identity_public"
        private const val KEY_IDENTITY_SECRET = "identity_secret_wrapped"
        private const val ACCOUNT_MASTER_AAD = "taga/account/master/v1"

        /**
         * Постоянная соль сервера: domain separation, не секрет.
         * SHA-256 от идентификатора инфраструктуры.
         */
        private const val SERVER_SALT =
            "9f2c1d7a5b3e8c40d1a6f9b2e4c7d0a3f5b8e1c4d7a0f3b6e9c2d5a8f1b4e7c0"
    }
}
