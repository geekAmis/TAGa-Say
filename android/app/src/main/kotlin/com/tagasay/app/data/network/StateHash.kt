package com.tagasay.app.data.network

import com.tagasay.core.crypto.CryptoEngine
import kotlinx.serialization.json.Json

/** Стабильный хеш содержимого состояния: updatedAt не участвует. */
object StateHash {
    fun of(crypto: CryptoEngine, json: Json, state: SyncedState): String {
        val content = state.copy(updatedAt = 0)
        val bytes = json.encodeToString(SyncedState.serializer(), content).encodeToByteArray()
        return crypto.sha256Hex(bytes)
    }
}
