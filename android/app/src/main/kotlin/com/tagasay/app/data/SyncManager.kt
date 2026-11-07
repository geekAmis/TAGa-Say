package com.tagasay.app.data

import android.content.Context
import android.util.Log
import com.tagasay.app.data.crypto.SignalStore
import com.tagasay.app.data.local.SettingsStore
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.network.AccountManager
import com.tagasay.app.data.network.ApiException
import com.tagasay.app.data.network.ChatSync
import com.tagasay.app.data.network.CryptoMessenger
import com.tagasay.app.data.network.MediaTransfer
import com.tagasay.app.data.network.TagaApi
import com.tagasay.core.crypto.CryptoEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Единственный владелец цикла синхронизации.
 *
 * Живёт в [com.tagasay.app.di.AppContainer], поэтому переживает пересоздание
 * Activity и используется и foreground-service, и ViewModel. Держит одну
 * libsignal-сессию и один polling-цикл — без дублирования обработки конвертов.
 */
class SyncManager(
    context: Context,
    private val repository: AppRepository,
    private val settingsStore: SettingsStore,
    private val accountManager: AccountManager,
    private val signalStore: SignalStore,
    private val crypto: CryptoEngine,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    var chatSync: ChatSync? = null
        private set

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            settingsStore.settings
                .distinctUntilChangedBy { Triple(it.onboarded, it.serverUrl, it.username) }
                .collectLatest { settings -> runLoop(settings) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        chatSync = null
    }

    /** Ждёт, пока после онбординга/смены сети поднимется сессия. */
    suspend fun awaitChatSync(timeoutMs: Long = 5_000): ChatSync? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            chatSync?.let { return it }
            delay(200)
        }
        return chatSync
    }

    private suspend fun runLoop(settings: AppSettings) {
        chatSync = null
        if (!settings.onboarded || settings.serverUrl.isBlank() || settings.username.isBlank()) return

        var backoff = 2L
        while (currentCoroutineContext().isActive) {
            try {
                val session = accountManager.ensureRegistered(
                    serverUrl = settings.serverUrl,
                    username = settings.username,
                    displayName = settings.displayName,
                )
                Log.i("TAGaSync", "registered as ${session.username} at ${session.serverUrl}")
                val api = TagaApi(session.serverUrl, session.accessToken)
                val messenger = CryptoMessenger(signalStore, api)
                val media = MediaTransfer(appContext, crypto, api)
                val sync = ChatSync(api, messenger, repository, media)
                chatSync = sync
                backoff = 2
                while (currentCoroutineContext().isActive) {
                    sync.poll()
                    delay(POLL_INTERVAL_MS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.w("TAGaSync", "sync failed: ${exception.message}", exception)
                if (exception is ApiException && exception.status == 401) {
                    accountManager.clear()
                }
                chatSync = null
                delay(backoff * 1_000)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val MAX_BACKOFF_SECONDS = 30L
    }
}
