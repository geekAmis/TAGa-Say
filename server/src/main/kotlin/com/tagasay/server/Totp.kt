package com.tagasay.server

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP (RFC 6238) для Google Authenticator / Aegis / 1Password.
 * Секрет хранится на сервере только для проверки кодов.
 */
object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val PERIOD_SECONDS = 30
    private const val DIGITS = 6

    fun randomSecret(bytes: Int = 20): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return base32Encode(data)
    }

    fun otpauthUri(issuer: String, account: String, secret: String): String =
        "otpauth://totp/${urlEncode(issuer)}:${urlEncode(account)}" +
            "?secret=$secret&issuer=${urlEncode(issuer)}&algorithm=SHA1" +
            "&digits=$DIGITS&period=$PERIOD_SECONDS"

    fun verify(secret: String, code: String, window: Int = 3): Boolean {
        val normalized = code.filter { it.isDigit() }
        if (normalized.length != DIGITS) return false
        val key = base32Decode(secret) ?: return false
        val counter = System.currentTimeMillis() / 1000 / PERIOD_SECONDS
        for (offset in -window..window) {
            if (generate(key, counter + offset) == normalized) return true
        }
        return false
    }

    private fun generate(key: ByteArray, counter: Long): String {
        val buffer = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            buffer[i] = (value and 0xff).toByte()
            value = value shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(buffer)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 1_000_000
        return otp.toString().padStart(DIGITS, '0')
    }

    private fun base32Encode(data: ByteArray): String {
        val result = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                result.append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) result.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return result.toString()
    }

    private fun base32Decode(value: String): ByteArray? {
        val clean = value.trim().trimEnd('=').uppercase()
        var buffer = 0
        var bits = 0
        val output = ArrayList<Byte>()
        for (character in clean) {
            val index = ALPHABET.indexOf(character)
            if (index < 0) return null
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                output += ((buffer shr (bits - 8)) and 0xff).toByte()
                bits -= 8
            }
        }
        return output.toByteArray()
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
