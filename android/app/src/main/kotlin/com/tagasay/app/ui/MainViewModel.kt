package com.tagasay.app.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tagasay.app.TAGaSayApp
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.ChatFolder
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.MessageStatus
import com.tagasay.app.data.model.Poll
import com.tagasay.app.data.model.ScanReport
import com.tagasay.app.data.model.ServerInfo
import com.tagasay.app.data.model.UiState
import com.tagasay.app.data.network.TagaApi
import com.tagasay.app.data.network.TwoFactorSetupResponse
import com.tagasay.app.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val syncManager = container.syncManager
    private val updateManager = container.updateManager

    private val fingerprint = kotlinx.coroutines.flow.MutableStateFlow("")
    private val serverInfo = kotlinx.coroutines.flow.MutableStateFlow<ServerInfo?>(null)
    private val serverAndUpdate = combine(serverInfo, updateManager.status) { info, update ->
        info to update
    }

    val uiState: StateFlow<UiState> = combine(
        repository.vault,
        settingsStore.settings,
        serverMonitor.status,
        fingerprint,
        serverAndUpdate,
    ) { vault, settings, server, fp, su ->
        UiState(
            loading = false,
            settings = settings,
            vault = vault,
            server = server,
            serverInfo = su.first,
            update = su.second,
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
        // Цикл синхронизации живёт в AppContainer и переживает пересоздание Activity.
        syncManager.start()
        updateManager.start()
        viewModelScope.launch {
            settingsStore.settings
                .map { it.onboarded }
                .distinctUntilChanged()
                .collect { onboarded ->
                    if (onboarded) SyncService.start(appContext) else SyncService.stop(appContext)
                }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshServerInfo()
                delay(SERVER_INFO_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshServerInfo() {
        val session = container.accountManager.currentSession() ?: return
        runCatching { TagaApi(session.serverUrl, session.accessToken).serverInfo() }
            .onSuccess { serverInfo.value = it }
            .onFailure { Log.w("TAGaServer", "server info failed: ${it.message}") }
    }

    // ------------------------------------------------------------ onboarding

    fun completeOnboarding(
        displayName: String,
        username: String,
        password: String,
        totpCode: String,
    ) {
        viewModelScope.launch {
            val serverUrl = settingsStore.settings.first().serverUrl
            // Verifier сохраняется до onboarded=true, чтобы фоновый sync не
            // опередил онбординг и не упал с «Password is required».
            withContext(Dispatchers.IO) {
                runCatching { container.accountManager.rememberPassword(serverUrl, username, password) }
                    .onFailure { Log.w("TAGaSync", "rememberPassword failed", it) }
            }
            settingsStore.completeOnboarding(displayName, username)
            repository.updateProfile(displayName, username, "", repository.vault.value.profile.bio)
            withContext(Dispatchers.IO) {
                runCatching {
                    container.accountManager.ensureRegistered(
                        serverUrl = serverUrl,
                        username = username,
                        displayName = displayName,
                        totpCode = totpCode,
                    )
                }
            }
            refreshServerInfo()
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
        poll: Poll? = null,
    ) {
        if (text.isBlank() && attachment == null && sticker == null && poll == null) return
        val message = repository.sendMessage(
            chatId = chatId,
            text = text,
            kind = kind,
            attachment = attachment,
            sticker = sticker,
            replyToText = replyToText,
            replyToId = replyToId,
            poll = poll,
        )
        val chat = repository.chat(chatId) ?: return
        if (chat.type != ChatType.PRIVATE) return
        if (kind == MessageKind.SYSTEM || kind == MessageKind.CALL) return

        viewModelScope.launch(Dispatchers.IO) {
            val sync = syncManager.awaitChatSync() ?: run {
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
                    poll = poll,
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
            val sync = syncManager.awaitChatSync() ?: return@launch
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
            syncManager.awaitChatSync()?.edit(chatId, messageId, text)
                ?.onFailure { Log.w("TAGaSync", "edit failed: ${it.message}", it) }
        }
    }

    fun deleteMessage(chatId: String, messageId: String) {
        repository.deleteMessage(chatId, messageId)
        if (repository.chat(chatId)?.type != ChatType.PRIVATE) return
        viewModelScope.launch(Dispatchers.IO) {
            syncManager.awaitChatSync()?.delete(chatId, messageId)
                ?.onFailure { Log.w("TAGaSync", "delete failed: ${it.message}", it) }
        }
    }

    fun forwardMessage(sourceChatId: String, messageId: String, targetChatId: String) {
        val sourceTitle = repository.chat(sourceChatId)?.title ?: "чат"
        val forwarded = repository.forwardMessage(sourceChatId, messageId, targetChatId) ?: return
        if (repository.chat(targetChatId)?.type != ChatType.PRIVATE) return
        viewModelScope.launch(Dispatchers.IO) {
            syncManager.awaitChatSync()?.forward(targetChatId, forwarded, sourceTitle)
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
            syncManager.awaitChatSync()?.sendReadReceipt(chatId)
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

    fun toggleStickerFavorite(sticker: String) = repository.toggleStickerFavorite(sticker)

    // ------------------------------------------------------- folders / calls

    fun saveChatFolder(folder: ChatFolder) = repository.saveFolder(folder)
    fun deleteChatFolder(folderId: String) = repository.deleteFolder(folderId)

    fun recordCall(
        chatId: String,
        seconds: Int,
        video: Boolean = false,
        outgoing: Boolean = true,
        missed: Boolean = false,
    ) = repository.addCall(chatId, seconds, video, outgoing, missed)

    fun clearCallLog() = repository.clearCallLog()

    // -------------------------------------------------------------- stories

    fun addStory(uri: Uri, caption: String) =
        repository.addStory(uri.toString(), caption)

    fun markStoryViewed(storyId: String) = repository.markStoryViewed(storyId)
    fun deleteStory(storyId: String) = repository.deleteStory(storyId)
    fun archiveStory(storyId: String) = repository.archiveStory(storyId)

    fun votePoll(chatId: String, messageId: String, optionId: String) =
        repository.votePoll(chatId, messageId, optionId)

    fun toggleReaction(chatId: String, messageId: String, emoji: String) {
        repository.toggleReaction(chatId, messageId, emoji)
        if (repository.chat(chatId)?.type != ChatType.PRIVATE) return
        viewModelScope.launch(Dispatchers.IO) {
            syncManager.awaitChatSync()?.react(chatId, messageId, emoji)
                ?.onFailure { Log.w("TAGaSync", "reaction failed: ${it.message}") }
        }
    }

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
    fun setMessageTextSize(size: Int) =
        updateSettings { it.copy(messageTextSize = size.coerceIn(12, 24)) }
    fun setBubbleRadius(radius: Int) =
        updateSettings { it.copy(bubbleRadius = radius.coerceIn(4, 24)) }
    fun setChatTheme(theme: String) = updateSettings { it.copy(chatTheme = theme) }
    fun setChatWallpaper(wallpaper: String) = updateSettings { it.copy(chatWallpaper = wallpaper) }

    fun updateProfile(displayName: String, username: String, phone: String, bio: String) {
        viewModelScope.launch {
            settingsStore.setProfile(displayName, username, phone, bio)
            repository.updateProfile(displayName, username, phone, bio)
        }
    }

    fun checkServerNow() {
        viewModelScope.launch {
            serverMonitor.check(settingsStore.settingsValue().serverUrl)
            refreshServerInfo()
        }
    }

    fun installUpdate() {
        viewModelScope.launch(Dispatchers.IO) { updateManager.downloadAndInstall() }
    }

    fun dismissUpdate() = updateManager.dismissOptional()

    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) { updateManager.check() }
    }

    fun resetDemoData() = repository.resetToDemo()

    fun wipeAll() {
        SyncService.stop(appContext)
        syncManager.stop()
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

private const val SERVER_INFO_INTERVAL_MS = 10 * 60 * 1000L
