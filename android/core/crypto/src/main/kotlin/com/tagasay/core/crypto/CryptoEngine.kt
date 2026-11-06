package com.tagasay.core.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.utils.Key
import com.sun.jna.NativeLong
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Низкоуровневый криптографический движок TAGa-Say.
 *
 * Все примитивы берутся из libsodium (Lazysodium) и JCA. Собственных
 * криптографических примитивов здесь нет.
 */
class CryptoEngine {

    private val sodium: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())
    private val random = SecureRandom()

    // ---------------------------------------------------------------- random

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    // ---------------------------------------------------------------- hashes

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String = sha256(data).toHex()

    // ------------------------------------------------------------------ HKDF

    /**
     * HKDF-SHA256 (RFC 5869).
     */
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32,
    ): ByteArray {
        require(length in 1..(255 * 32)) { "invalid HKDF length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val okm = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            previous.copyInto(okm, offset, 0, take)
            offset += take
            counter++
        }
        return okm
    }

    /**
     * Вывод мастер-ключа. Основная энтропия — [deviceSecret] (случайные байты,
     * сгенерированные в Keystore). [serverSalt] — domain separation, не секрет.
     * [passphraseHash] может быть пустым, если парольная фраза не задана.
     */
    fun deriveMasterKey(
        deviceSecret: ByteArray,
        serverSalt: ByteArray,
        passphraseHash: ByteArray = ByteArray(0),
    ): ByteArray {
        require(deviceSecret.size >= 32) { "device secret must be at least 32 bytes" }
        val info = "taga/master/v1".toByteArray() + passphraseHash
        return hkdfSha256(deviceSecret, serverSalt, info, 32)
    }

    // -------------------------------------------------------------- Argon2id

    /**
     * Argon2id, 64 MiB, 3 итерации. Возвращает 32-байтный хеш.
     */
    fun argon2id(
        passphrase: ByteArray,
        salt: ByteArray = randomBytes(PwHash.ARGON2ID_SALTBYTES),
        memoryBytes: Long = 64L * 1024 * 1024,
        iterations: Long = 3,
        outputLength: Int = 32,
    ): Argon2Result {
        val out = ByteArray(outputLength)
        val ok = sodium.cryptoPwHash(
            out,
            outputLength,
            passphrase,
            passphrase.size,
            salt,
            iterations,
            NativeLong(memoryBytes),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        check(ok) { "argon2id failed" }
        return Argon2Result(hash = out, salt = salt)
    }

    // --------------------------------------------------------------- Ed25519

    fun generateSigningKeyPair(): SigningKeyPair {
        val pair = sodium.cryptoSignKeypair()
        return SigningKeyPair(
            publicKey = pair.publicKey.asBytes,
            secretKey = pair.secretKey.asBytes,
        )
    }

    fun signDetached(message: ByteArray, secretKey: ByteArray): ByteArray {
        val signature = ByteArray(SIGN_BYTES)
        val ok = sodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey)
        check(ok) { "ed25519 sign failed" }
        return signature
    }

    fun verifyDetached(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean =
        sodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)

    // ------------------------------------------------------- AES-256-GCM

    fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        val nonce = randomBytes(GCM_NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, payload: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(payload.size > GCM_NONCE_BYTES) { "payload too short" }
        val nonce = payload.copyOfRange(0, GCM_NONCE_BYTES)
        val body = payload.copyOfRange(GCM_NONCE_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(body)
    }

    // ------------------------------------------------- XChaCha20-Poly1305

    /**
     * XChaCha20-Poly1305-IETF. Формат: ciphertext || tag(16).
     */
    fun xchachaEncrypt(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
        nonce: ByteArray = randomBytes(XCHACHA_NONCE_BYTES),
    ): ByteArray {
        require(key.size == 32) { "XChaCha key must be 32 bytes" }
        require(nonce.size == XCHACHA_NONCE_BYTES) { "XChaCha nonce must be 24 bytes" }
        val ciphertext = ByteArray(plaintext.size + TAG_BYTES)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext,
            null,
            plaintext,
            plaintext.size.toLong(),
            aad,
            aad.size.toLong(),
            null,
            nonce,
            key,
        )
        check(ok) { "xchacha encrypt failed" }
        return nonce + ciphertext
    }

    fun xchachaDecrypt(key: ByteArray, payload: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 32) { "XChaCha key must be 32 bytes" }
        require(payload.size >= XCHACHA_NONCE_BYTES + TAG_BYTES) { "payload too short" }
        val nonce = payload.copyOfRange(0, XCHACHA_NONCE_BYTES)
        val ciphertext = payload.copyOfRange(XCHACHA_NONCE_BYTES, payload.size)
        val plaintext = ByteArray(ciphertext.size - TAG_BYTES)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            plaintext,
            null,
            null,
            ciphertext,
            ciphertext.size.toLong(),
            aad,
            aad.size.toLong(),
            nonce,
            key,
        )
        check(ok) { "xchacha decrypt failed (bad key, nonce or tag)" }
        return plaintext
    }

    // -------------------------------------------------------------- utils

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun fingerprint(publicKey: ByteArray): String =
        sha256(publicKey).toHex().chunked(4).take(15).joinToString(" ")

    data class Argon2Result(val hash: ByteArray, val salt: ByteArray)

    data class SigningKeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    companion object {
        const val TAG_BYTES = 16
        const val XCHACHA_NONCE_BYTES = 24
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val SIGN_BYTES = 64
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
