package com.tagasay.app.data.local

import android.content.Context
import com.tagasay.app.data.model.VaultData
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Зашифрованное локальное хранилище. Файл содержит AES-256-GCM конверт,
 * ключ выводится из device secret, обёрнутого Android Keystore.
 */
class VaultStore(
    context: Context,
    private val keyStore: DeviceKeyStore,
) {
    private val file = File(context.filesDir, "taga_vault.bin")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exists(): Boolean = file.exists()

    fun load(): VaultData? {
        if (!file.exists()) return null
        return runCatching {
            val plaintext = keyStore.decryptLocal(file.readText(), AAD)
            json.decodeFromString<VaultData>(plaintext.decodeToString())
        }.getOrNull()
    }

    fun save(data: VaultData) {
        val plaintext = json.encodeToString(VaultData.serializer(), data).encodeToByteArray()
        val encrypted = keyStore.encryptLocal(plaintext, AAD)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(encrypted)
        if (!temp.renameTo(file)) {
            file.writeText(encrypted)
            temp.delete()
        }
    }

    fun wipe() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private companion object {
        const val AAD = "taga/vault/v1"
    }
}
