package com.tagasay.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tagasay.app.TAGaSayApp
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.ScanReport
import com.tagasay.app.data.model.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as TAGaSayApp).container
    private val repository = container.repository
    private val settingsStore = container.settingsStore
    private val serverMonitor = container.serverMonitor

    private val fingerprint = kotlinx.coroutines.flow.MutableStateFlow("")

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
    }

    // ------------------------------------------------------------ onboarding

    fun completeOnboarding(displayName: String, username: String, phone: String) {
        viewModelScope.launch {
            settingsStore.completeOnboarding(displayName, username, phone)
            repository.updateProfile(displayName, username, phone, repository.vault.value.profile.bio)
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
    ) {
        if (text.isBlank() && attachment == null && sticker == null) return
        repository.sendMessage(chatId, text, kind, attachment, sticker, replyToText)
    }

    fun editMessage(chatId: String, messageId: String, text: String) {
        if (text.isBlank()) return
        repository.editMessage(chatId, messageId, text)
    }

    fun deleteMessage(chatId: String, messageId: String) =
        repository.deleteMessage(chatId, messageId)

    fun forwardMessage(sourceChatId: String, messageId: String, targetChatId: String) =
        repository.forwardMessage(sourceChatId, messageId, targetChatId)

    fun markRead(chatId: String) = repository.markRead(chatId)

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

    // -------------------------------------------------------------- settings

    fun setDarkTheme(enabled: Boolean) = updateSettings { it.copy(darkTheme = enabled) }
    fun setPhoneDiscoverable(enabled: Boolean) = updateSettings { it.copy(phoneDiscoverable = enabled) }
    fun setReadReceipts(enabled: Boolean) = updateSettings { it.copy(readReceipts = enabled) }
    fun setNotifications(enabled: Boolean) = updateSettings { it.copy(notifications = enabled) }
    fun setShowTyping(enabled: Boolean) = updateSettings { it.copy(showTyping = enabled) }
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
