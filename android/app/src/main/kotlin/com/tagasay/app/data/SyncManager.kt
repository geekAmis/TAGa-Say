package com.tagasay.app.data

import android.content.Context
import android.util.Log
import com.tagasay.app.data.crypto.SignalStore
import com.tagasay.app.data.local.SettingsStore
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.network.AccountManager
import com.tagasay.app.data.network.ApiException
import com.tagasay.app.data.network.ChatSync
import com.tagasay.app.data.network.BotSync
import com.tagasay.app.data.network.CryptoMessenger
import com.tagasay.app.data.network.MediaTransfer
import com.tagasay.app.data.network.StateSync
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
import kotlinx.coroutines.flow.asSharedFlow
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
    private val stateSync: StateSync,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    var chatSync: ChatSync? = null
        private set

    @Volatile
    var botSync: BotSync? = null
        private set

    /** Уведомления бот-чатов (ответы на callback, ошибки блокировки). */
    private val _botNotices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 32)
    val botNotices: kotlinx.coroutines.flow.SharedFlow<String> = _botNotices.asSharedFlow()

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
        botSync = null
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

    suspend fun awaitBotSync(timeoutMs: Long = 5_000): BotSync? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            botSync?.let { return it }
            delay(200)
        }
        return botSync
    }

    private suspend fun runLoop(settings: AppSettings) {
        chatSync = null
        botSync = null
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
                val sync = ChatSync(api, messenger, repository, media, settings.username, crypto)
                chatSync = sync
                val bots = BotSync(appContext, api, repository, settings.username, _botNotices)
                botSync = bots
                backoff = 2
                stateSync.pullAndApply(api, session)
                runCatching { api.rooms().forEach { repository.applyRoom(it) } }
                    .onFailure { Log.w("TAGaSync", "rooms sync failed: ${it.message}") }
                var ticks = 0
                while (currentCoroutineContext().isActive) {
                    sync.poll()
                    if (ticks % BOT_POLL_EVERY_POLLS == 0) {
                        runCatching { bots.poll() }
                            .onFailure { Log.w("TAGaBots", "inbox poll failed: ${it.message}") }
                    }
                    if (ticks % STATE_PUSH_EVERY_POLLS == 0) {
                        stateSync.pushIfChanged(api, session)
                    }
                    if (ticks % ROOM_REFRESH_EVERY_POLLS == 0) {
                        runCatching { api.rooms().forEach { repository.applyRoom(it) } }
                            .onFailure { Log.w("TAGaSync", "rooms refresh failed: ${it.message}") }
                    }
                    ticks++
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
                botSync = null
                delay(backoff * 1_000)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val STATE_PUSH_EVERY_POLLS = 5
        const val ROOM_REFRESH_EVERY_POLLS = 10
        /** 20 запросов/мин на пользователя — берём с запасом (10/мин). */
        const val BOT_POLL_EVERY_POLLS = 2
        const val MAX_BACKOFF_SECONDS = 30L
    }
}
