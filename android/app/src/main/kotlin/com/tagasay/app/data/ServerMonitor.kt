package com.tagasay.app.data

import com.tagasay.app.data.model.ServerStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * Мониторинг доступности сервера. При недоступности UI показывает плашку
 * с контактом владельца проекта.
 */
class ServerMonitor {

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 4_000
            socketTimeoutMillis = 5_000
        }
    }

    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    suspend fun watch(url: String) {
        while (currentCoroutineContext().isActive) {
            check(url)
            delay(if (_status.value.online) 30_000 else 15_000)
        }
    }

    suspend fun check(url: String) {
        val clean = url.trim().trimEnd('/')
        if (clean.isBlank()) {
            _status.value = ServerStatus(configured = false, online = false, checkedAt = System.currentTimeMillis())
            return
        }
        val started = System.currentTimeMillis()
        try {
            val response = client.get("$clean/health")
            val body = response.bodyAsText()
            val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
            _status.value = ServerStatus(
                configured = true,
                online = response.status.value in 200..299,
                latencyMs = System.currentTimeMillis() - started,
                checkedAt = System.currentTimeMillis(),
                version = version,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _status.value = ServerStatus(
                configured = true,
                online = false,
                latencyMs = null,
                checkedAt = System.currentTimeMillis(),
            )
        }
    }
}
