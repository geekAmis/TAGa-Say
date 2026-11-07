package com.tagasay.app.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tagasay.app.TAGaSayApp
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.ScanReport
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.MessageStatus
import com.tagasay.app.data.model.UiState
import com.tagasay.app.data.network.ApiException
import com.tagasay.app.data.network.ChatSync
import com.tagasay.app.data.network.CryptoMessenger
import com.tagasay.app.data.network.MediaTransfer
import com.tagasay.app.data.network.TagaApi
import com.tagasay.app.data.network.TwoFactorSetupResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as TAGaSayApp).container
    private val appContext = application.applicationContext
    private val repository = container.repository
    private val settingsStore = container.settingsStore
    private val serverMonitor = container.serverMonitor

    private val fingerprint = kotlinx.coroutines.flow.MutableStateFlow("")
    @Volatile
    private var chatSync: ChatSync? = null

    val uiState: StateFlow<UiState> = combine(
        repository.vault,
        settingsStore.settings,
        serverMonitor.status,
        fingerprint,
    ) { vault, settings, server, fp ->
        UiState(
            loading = false,
            settings = settings,
            vault = vault,
            server = server,
            fingerprint = fp,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState(),
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            fingerprint.value = runCatching { container.deviceKeyStore.fingerprint() }
                .getOrDefault("")
        }
        viewModelScope.launch {
            settingsStore.settings
                .map { it.serverUrl }
                .distinctUntilChanged()
                .collectLatest { url -> serverMonitor.watch(url) }
        }
        viewModelScope.launch {
            settingsStore.settings
                .distinctUntilChangedBy { Triple(it.onboarded, it.serverUrl, it.username) }
                .collectLatest { settings -> runNetworkSync(settings) }
        }
    }

    /**
     * Регистрирует устройство на relay и держит цикл приёма E2EE-конвертов.
     * При смене сервера/username корутина отменяется и стартует заново.
     */
    private suspend fun runNetworkSync(settings: AppSettings) {
        chatSync = null
        if (!settings.onboarded || settings.serverUrl.isBlank() || settings.username.isBlank()) return
        var backoff = 2L
        while (viewModelScope.isActive) {
            try {
                val session = withContext(Dispatchers.IO) {
                    container.accountManager.ensureRegistered(
                        serverUrl = settings.serverUrl,
                        username = settings.username,
                        displayName = settings.displayName,
                    )
                }
                Log.i("TAGaSync", "registered as ${session.username} at ${session.serverUrl}")
                val api = TagaApi(session.serverUrl, session.accessToken)
                val messenger = CryptoMessenger(container.signalStore, api)
                val media = MediaTransfer(appContext, container.crypto, api)
                val sync = ChatSync(api, messenger, repository, media)
                chatSync = sync
                backoff = 2
                while (viewModelScope.isActive) {
                    sync.poll()
                    delay(3_000)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.w("TAGaSync", "sync failed: ${exception.message}", exception)
                if (exception is ApiException && exception.status == 401) {
                    container.accountManager.clear()
                }
                chatSync = null
                delay(backoff * 1_000)
                backoff = (backoff * 2).coerceAtMost(30)
            }
        }
    }

    // ------------------------------------------------------------ onboarding

    fun completeOnboarding(
        displayName: String,
        username: String,
        password: String,
        totpCode: String,
    ) {
        viewModelScope.launch {
            settingsStore.completeOnboarding(displayName, username)
            repository.updateProfile(displayName, username, "", repository.vault.value.profile.bio)
            val settings = settingsStore.settings.first()
            withContext(Dispatchers.IO) {
                runCatching {
                    container.accountManager.ensureRegistered(
                        serverUrl = settings.serverUrl,
                        username = username,
                        displayName = displayName,
                        password = password,
                        totpCode = totpCode,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- messaging

    fun sendMessage(
        chatId: String,
        text: String,
        kind: MessageKind = MessageKind.TEXT,
        attachment: Attachment? = null,
        sticker: String? = null,
        replyToText: String? = null,
        replyToId: String? = null,
    ) {
        if (text.isBlank() && attachment == null && sticker == null) return
        val message = repository.sendMessage(
            chatId = chatId,
            text = text,
            kind = kind,
            attachment = attachment,
            sticker = sticker,
            replyToText = replyToText,
            replyToId = replyToId,
        )
        val chat = repository.chat(chatId) ?: return
        if (chat.type != ChatType.PRIVATE) return
        if (kind == MessageKind.SYSTEM || kind == MessageKind.CALL) return

        viewModelScope.launch(Dispatchers.IO) {
            val sync = chatSync ?: run {
                repository.setMessageStatus(chatId, message.id, MessageStatus.FAILED)
                return@launch
            }
            val result: Result<Unit> = when {
                attachment != null -> {
                    val source = attachment.localUri?.let(Uri::parse)
                    if (source == null) {
                        Result.failure(IllegalArgumentException("У вложения нет локального файла"))
                    } else {
                        sync.sendAttachment(
                            chatId = chatId,
                            messageId = message.id,
                            kind = kind,
                            attachment = attachment,
                            source = source,
                            caption = text,
                            replyToId = replyToId,
                            replyToText = replyToText,
                        ).map { prepared ->
                            repository.updateAttachment(
                                chatId,
                                message.id,
                                attachment.copy(
                                    localUri = prepared.cachedUri.toString(),
                                    mediaId = prepared.mediaId,
                                    keyBase64 = prepared.keyBase64,
                                ),
                            )
                        }
                    }
                }
                else -> sync.send(
                    chatId = chatId,
                    messageId = message.id,
                    text = text,
                    kind = kind,
                    sticker = sticker,
                    replyToId = replyToId,
                    replyToText = replyToText,
                )
            }
            repository.setMessageStatus(
                chatId,
                message.id,
                if (result.isSuccess) MessageStatus.SENT else MessageStatus.FAILED,
            )
            result.onFailure { Log.w("TAGaSync", "send failed: ${it.message}", it) }
        }
    }

    /**
     * Скачивает и расшифровывает вложение полученного сообщения.
     */
    fun downloadAttachment(chatId: String, messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val message = repository.messages(chatId).firstOrNull { it.id == messageId } ?: return@launch
            val attachment = message.attachment ?: return@launch
            if (attachment.localUri != null) return@launch
            val sync = chatSync ?: return@launch
            runCatching { sync.downloadAttachment(attachment) }
                .onSuccess { uri ->
                    repository.updateAttachment(
                        chatId,
                        messageId,
                        attachment.copy(localUri = uri.toString()),
                    )
                }
                .onFailure { Log.w("TAGaMedia", "download failed: ${it.message}", it) }
        }
    }

    fun editMessage(chatId: String, messageId: String, text: String) {
        if (text.isBlank()) return
        repository.editMessage(chatId, messageId, text)
        if (repository.chat(chatId)?.type != ChatType.PRIVATE) return
        viewModelScope.launch(Dispatchers.IO) {
            chatSync?.edit(chatId, messageId, text)
                ?.onFailure { Log.w("TAGaSync", "edit failed: ${it.message}", it) }
        }
    }

    fun deleteMessage(chatId: String, messageId: String) {
        repository.deleteMessage(chatId, messageId)
        if (repository.chat(chatId)?.type != ChatType.PRIVATE) return
        viewModelScope.launch(Dispatchers.IO) {
            chatSync?.delete(chatId, messageId)
                ?.onFailure { Log.w("TAGaSync", "delete failed: ${it.message}", it) }
        }
    }

    fun forwardMessage(sourceChatId: String, messageId: String, targetChatId: String) {
        val sourceTitle = repository.chat(sourceChatId)?.title ?: "чат"
        val forwarded = repository.forwardMessage(sourceChatId, messageId, targetChatId) ?: return
        if (repository.chat(targetChatId)?.type != ChatType.PRIVATE) return
        viewModelScope.launch(Dispatchers.IO) {
            chatSync?.forward(targetChatId, forwarded, sourceTitle)
                ?.onFailure { Log.w("TAGaSync", "forward failed: ${it.message}", it) }
        }
    }

    fun markRead(chatId: String) {
        repository.markRead(chatId)
        if (repository.chat(chatId)?.type != ChatType.PRIVATE) return
        viewModelScope.launch(Dispatchers.IO) {
            val receiptsEnabled = runCatching { settingsStore.settings.first().readReceipts }
                .getOrDefault(true)
            if (!receiptsEnabled) return@launch
            chatSync?.sendReadReceipt(chatId)
                ?.onFailure { Log.w("TAGaSync", "read receipt failed: ${it.message}", it) }
        }
    }

    fun ensureDirectChat(username: String, displayName: String, avatarColor: Long): String =
        repository.ensureDirectChat(username, displayName, avatarColor)

    fun createChannel(title: String, username: String, description: String) =
        repository.createChannel(title, username, description)

    fun installStickerPack(packId: String, installed: Boolean) =
        repository.installStickerPack(packId, installed)

    fun addStickerPack(title: String, stickers: List<String>) =
        repository.addStickerPack(title, stickers)

    suspend fun inspectFile(uri: Uri): ScanReport = withContext(Dispatchers.IO) {
        container.fileInspector.inspect(uri)
    }

    // ------------------------------------------------------------------- 2FA

    suspend fun setup2fa(): TwoFactorSetupResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val session = container.accountManager.currentSession()
                ?: error("no session")
            TagaApi(session.serverUrl, session.accessToken).setup2fa()
        }.onFailure { Log.w("TAGa2FA", "setup failed", it) }.getOrNull()
    }

    suspend fun enable2fa(code: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val session = container.accountManager.currentSession() ?: return@runCatching false
            TagaApi(session.serverUrl, session.accessToken).enable2fa(code).enabled
        }.getOrDefault(false)
    }

    suspend fun twoFactorStatus(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val session = container.accountManager.currentSession() ?: return@runCatching false
            TagaApi(session.serverUrl, session.accessToken).twoFactorStatus().enabled
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------- settings

    fun setDarkTheme(enabled: Boolean) = updateSettings { it.copy(darkTheme = enabled) }
    fun setPhoneDiscoverable(enabled: Boolean) = updateSettings { it.copy(phoneDiscoverable = enabled) }
    fun setReadReceipts(enabled: Boolean) = updateSettings { it.copy(readReceipts = enabled) }
    fun setNotifications(enabled: Boolean) = updateSettings { it.copy(notifications = enabled) }
    fun setShowTyping(enabled: Boolean) = updateSettings { it.copy(showTyping = enabled) }
    fun setScreenSecurity(enabled: Boolean) = updateSettings { it.copy(screenSecurity = enabled) }
    fun setServerUrl(url: String) = updateSettings { it.copy(serverUrl = url.trim()) }

    fun updateProfile(displayName: String, username: String, phone: String, bio: String) {
        viewModelScope.launch {
            settingsStore.setProfile(displayName, username, phone, bio)
            repository.updateProfile(displayName, username, phone, bio)
        }
    }

    fun checkServerNow() {
        viewModelScope.launch {
            serverMonitor.check(settingsStore.settingsValue().serverUrl)
        }
    }

    fun resetDemoData() = repository.resetToDemo()

    fun wipeAll() {
        repository.wipeAll()
        container.deviceKeyStore.wipe()
        viewModelScope.launch {
            settingsStore.update { AppSettings() }
        }
    }

    fun chat(chatId: String) = repository.chat(chatId)
    fun messages(chatId: String): List<Message> = repository.messages(chatId)
    fun contact(username: String) = repository.contactByUsername(username)
    fun chatByUsername(username: String) = repository.chatByUsername(username)

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }
}

private suspend fun com.tagasay.app.data.local.SettingsStore.settingsValue(): AppSettings =
    settings.first()
