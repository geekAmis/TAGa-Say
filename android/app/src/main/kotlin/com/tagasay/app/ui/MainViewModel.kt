package com.tagasay.app.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tagasay.app.TAGaSayApp
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.BotHubState
import com.tagasay.app.data.model.Channel
import com.tagasay.app.data.model.Chat
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
import com.tagasay.app.data.network.CreateRoomRequest
import com.tagasay.app.data.network.toWire
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

    private val _botHub = kotlinx.coroutines.flow.MutableStateFlow(BotHubState())
    val botHub: StateFlow<BotHubState> = _botHub

    private val _botNotice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val botNotice: StateFlow<String?> = _botNotice

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
        // Р¦РёРєР» СЃРёРЅС…СЂРѕРЅРёР·Р°С†РёРё Р¶РёРІС‘С‚ РІ AppContainer Рё РїРµСЂРµР¶РёРІР°РµС‚ РїРµСЂРµСЃРѕР·РґР°РЅРёРµ Activity.
        syncManager.start()
        updateManager.start()
        viewModelScope.launch {
            syncManager.botNotices.collect { text -> _botNotice.value = text }
        }
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
            // Verifier СЃРѕС…СЂР°РЅСЏРµС‚СЃСЏ РґРѕ onboarded=true, С‡С‚РѕР±С‹ С„РѕРЅРѕРІС‹Р№ sync РЅРµ
            // РѕРїРµСЂРµРґРёР» РѕРЅР±РѕСЂРґРёРЅРі Рё РЅРµ СѓРїР°Р» СЃ В«Password is requiredВ».
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
        val chat = repository.chat(chatId) ?: return
        if (kind == MessageKind.SYSTEM || kind == MessageKind.CALL) return

        // Р‘РѕС‚-С‡Р°С‚С‹: РѕС‚РґРµР»СЊРЅС‹Р№ РїСѓС‚СЊ, Р±РµР· E2EE Рё Р±РµР· РєРѕРЅРІРµСЂС‚РѕРІ.
        if (chat.type == ChatType.BOT) {
            if (attachment != null || sticker != null || poll != null) {
                _botNotice.value = "Р‘РѕС‚С‹ РїРѕРєР° РїСЂРёРЅРёРјР°СЋС‚ С‚РѕР»СЊРєРѕ С‚РµРєСЃС‚"
                return
            }
            viewModelScope.launch(Dispatchers.IO) {
                val bots = syncManager.awaitBotSync()
                if (bots == null) {
                    val failed = repository.sendMessage(chatId, text, replyToId = replyToId)
                    repository.setMessageStatus(chatId, failed.id, MessageStatus.FAILED)
                    _botNotice.value = "РќРµС‚ СЃРІСЏР·Рё СЃ СЃРµСЂРІРµСЂРѕРј"
                    return@launch
                }
                bots.sendText(chatId, text, replyToId)
            }
            return
        }

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
        if (chat.type != ChatType.PRIVATE) {
            if (chat.type == ChatType.CHANNEL && !canPostToChannel(chat)) return
            if (attachment != null) {
                // РњРµРґРёР° РІ РіСЂСѓРїРїР°С… вЂ” СЃР»РµРґСѓСЋС‰РёР№ С€Р°Рі; РЅРµ РѕС‚РїСЂР°РІР»СЏРµРј РїСѓСЃС‚РѕР№ С‚РµРєСЃС‚.
                repository.setMessageStatus(chatId, message.id, MessageStatus.FAILED)
                return
            }
            viewModelScope.launch(Dispatchers.IO) {
                val sync = syncManager.awaitChatSync() ?: run {
                    repository.setMessageStatus(chatId, message.id, MessageStatus.FAILED)
                    return@launch
                }
                val senderName = runCatching { settingsStore.settings.first().displayName }
                    .getOrDefault("")
                    .ifBlank { "Р’С‹" }
                val result = sync.sendRoom(
                    chatId = chatId,
                    messageId = message.id,
                    text = text,
                    kind = kind,
                    sticker = sticker,
                    replyToId = replyToId,
                    replyToText = replyToText,
                    senderName = senderName,
                    poll = poll?.toWire(),
                )
                repository.setMessageStatus(
                    chatId,
                    message.id,
                    if (result.isSuccess) MessageStatus.SENT else MessageStatus.FAILED,
                )
                result.onFailure { Log.w("TAGaRooms", "group send failed: ${it.message}") }
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val sync = syncManager.awaitChatSync() ?: run {
                repository.setMessageStatus(chatId, message.id, MessageStatus.FAILED)
                return@launch
            }
            val result: Result<Unit> = when {
                attachment != null -> {
                    val source = attachment.localUri?.let(Uri::parse)
                    if (source == null) {
                        Result.failure(IllegalArgumentException("РЈ РІР»РѕР¶РµРЅРёСЏ РЅРµС‚ Р»РѕРєР°Р»СЊРЅРѕРіРѕ С„Р°Р№Р»Р°"))
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
     * РЎРєР°С‡РёРІР°РµС‚ Рё СЂР°СЃС€РёС„СЂРѕРІС‹РІР°РµС‚ РІР»РѕР¶РµРЅРёРµ РїРѕР»СѓС‡РµРЅРЅРѕРіРѕ СЃРѕРѕР±С‰РµРЅРёСЏ.
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
        val sourceTitle = repository.chat(sourceChatId)?.title ?: "С‡Р°С‚"
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

    // ------------------------------------------------------------------- bots

    fun clearBotNotice() {
        _botNotice.value = null
    }

    /** РћС‚РєСЂС‹РІР°РµС‚ Р±РѕС‚-С‡Р°С‚ (СЃРѕР·РґР°С‘С‚ Р»РѕРєР°Р»СЊРЅРѕ Рё С€Р»С‘С‚ /start РїСЂРё РїРµСЂРІРѕРј РєРѕРЅС‚Р°РєС‚Рµ). */
    fun openBotChat(username: String, onReady: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var chatId: String? = null
            val botSync = syncManager.awaitBotSync()
            if (botSync != null) {
                botSync.openBot(username)
                    .onSuccess { chatId = it }
                    .onFailure {
                        Log.w("TAGaBots", "open bot failed: ${it.message}")
                        _botNotice.value = it.message ?: "Р‘РѕС‚ РЅРµРґРѕСЃС‚СѓРїРµРЅ"
                    }
            }
            if (chatId == null) {
                val cached = repository.botInfo(username)
                if (cached != null) {
                    chatId = repository.ensureBotChat(username, cached)
                } else {
                    _botNotice.value = "Р‘РѕС‚ РЅРµ РЅР°Р№РґРµРЅ"
                    return@launch
                }
            }
            chatId?.let { id ->
                withContext(Dispatchers.Main) { onReady(id) }
            }
        }
    }

    fun openBotFather(onReady: (String) -> Unit) = openBotChat("botfather", onReady)

    fun botCallback(chatId: String, messageId: String, data: String) {
        viewModelScope.launch(Dispatchers.IO) {
            syncManager.awaitBotSync()?.clickCallback(chatId, messageId, data)
        }
    }

    fun toggleBotBlocked(chatId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val chat = repository.chat(chatId) ?: return@launch
            val blocked = repository.botInfo(chat.username)?.blocked == true
            syncManager.awaitBotSync()?.setBlocked(chatId, !blocked)
        }
    }

    fun markBotWarningShown(username: String) {
        repository.markBotWarningShown(username)
    }

    /** РљР°С‚Р°Р»РѕРі Р±РѕС‚РѕРІ + В«РјРѕРё Р±РѕС‚С‹В». РџСѓСЃС‚РѕР№ query вЂ” РЅР°С‡Р°Р»СЊРЅР°СЏ РІС‹РґР°С‡Р° СЃРµСЂРІРµСЂР°. */
    fun refreshBots(query: String = "") {
        _botHub.value = _botHub.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val session = container.accountManager.currentSession()
            if (session == null) {
                _botHub.value = _botHub.value.copy(loading = false, error = "РќРµС‚ СЃРµСЃСЃРёРё")
                return@launch
            }
            val api = TagaApi(session.serverUrl, session.accessToken)
            runCatching {
                val catalog = api.bots(query).filter { it.username.isNotBlank() }
                val mine = api.myBots().filter { it.username.isNotBlank() }
                catalog to mine
            }.onSuccess { (catalog, mine) ->
                mine.forEach { repository.saveBotInfo(it) }
                _botHub.value = BotHubState(
                    catalog = catalog,
                    mine = mine,
                    loading = false,
                    error = null,
                )
            }.onFailure { error ->
                Log.w("TAGaBots", "bots catalog failed: ${error.message}")
                _botHub.value = _botHub.value.copy(
                    loading = false,
                    error = error.message ?: "РќРµ СѓРґР°Р»РѕСЃСЊ Р·Р°РіСЂСѓР·РёС‚СЊ Р±РѕС‚РѕРІ",
                )
            }
        }
    }

    fun createChannel(title: String, username: String, description: String): Channel {
        val channel = repository.createChannel(title, username, description)
        viewModelScope.launch(Dispatchers.IO) {
            val session = container.accountManager.currentSession() ?: return@launch
            val api = TagaApi(session.serverUrl, session.accessToken)
            runCatching {
                api.createRoom(
                    CreateRoomRequest(
                        title = title,
                        username = channel.username,
                        description = description,
                        channel = true,
                    ),
                )
            }.onSuccess { room -> repository.applyRoom(room) }
                .onFailure { Log.w("TAGaRooms", "create channel failed: ${it.message}") }
        }
        return channel
    }

    fun createGroup(title: String, members: List<String>): Chat {
        val owner = container.accountManager.currentSession()?.username
            ?: repository.vault.value.profile.username
        val chat = repository.createLocalGroup(title, members, owner)
        viewModelScope.launch(Dispatchers.IO) {
            val session = container.accountManager.currentSession() ?: return@launch
            val api = TagaApi(session.serverUrl, session.accessToken)
            runCatching {
                api.createRoom(
                    CreateRoomRequest(
                        title = title,
                        username = chat.username,
                        members = members,
                        channel = false,
                    ),
                )
            }.onSuccess { room ->
                repository.applyRoom(room)
                syncManager.awaitChatSync()?.inviteToRoom(chat.id, members)
            }.onFailure { Log.w("TAGaRooms", "create group failed: ${it.message}") }
        }
        return chat
    }

    fun addRoomMember(chatId: String, username: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val chat = repository.chat(chatId) ?: return@launch
            val roomId = chat.roomId ?: return@launch
            val session = container.accountManager.currentSession() ?: return@launch
            val api = TagaApi(session.serverUrl, session.accessToken)
            runCatching { api.changeRoomMember(roomId, username, remove = false) }
                .onSuccess { room ->
                    repository.applyRoom(room)
                    syncManager.awaitChatSync()?.inviteToRoom(chatId, listOf(username))
                }
                .onFailure { Log.w("TAGaRooms", "add member failed: ${it.message}") }
        }
    }

    fun removeRoomMember(chatId: String, username: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val chat = repository.chat(chatId) ?: return@launch
            val roomId = chat.roomId ?: return@launch
            val session = container.accountManager.currentSession() ?: return@launch
            val api = TagaApi(session.serverUrl, session.accessToken)
            runCatching { api.changeRoomMember(roomId, username, remove = true) }
                .onSuccess { room -> repository.applyRoom(room) }
                .onFailure { Log.w("TAGaRooms", "remove member failed: ${it.message}") }
        }
    }

    fun leaveRoom(chatId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val chat = repository.chat(chatId) ?: return@launch
            val roomId = chat.roomId ?: return@launch
            val session = container.accountManager.currentSession() ?: return@launch
            val api = TagaApi(session.serverUrl, session.accessToken)
            val result = runCatching { api.leaveRoom(roomId) }
            if (result.isSuccess) {
                repository.removeRoomChat(roomId)
            } else {
                Log.w("TAGaRooms", "leave room failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun refreshRooms() {
        viewModelScope.launch(Dispatchers.IO) {
            val session = container.accountManager.currentSession() ?: return@launch
            val api = TagaApi(session.serverUrl, session.accessToken)
            runCatching { api.rooms() }
                .onSuccess { rooms -> rooms.forEach(repository::applyRoom) }
                .onFailure { Log.w("TAGaRooms", "refresh rooms failed: ${it.message}") }
        }
    }

    private fun canPostToChannel(chat: Chat): Boolean {
        val me = repository.vault.value.profile.username
        if (chat.ownerUsername.isBlank()) return true
        return chat.ownerUsername.equals(me, true) ||
            chat.admins.any { it.equals(me, true) }
    }

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

    fun addStory(uri: Uri, caption: String) {
        val story = repository.addStory(uri.toString(), caption) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val session = container.accountManager.currentSession() ?: return@launch
            val api = TagaApi(session.serverUrl, session.accessToken)
            runCatching { container.storyTransfer.upload(api, uri) }
                .onSuccess { prepared ->
                    repository.attachStoryMedia(
                        storyId = story.id,
                        mediaId = prepared.mediaId,
                        keyBase64 = prepared.keyBase64,
                        sha256 = prepared.sha256,
                        mimeType = prepared.mimeType,
                        sizeBytes = prepared.sizeBytes,
                        localUri = prepared.localUri,
                    )
                }
                .onFailure { Log.w("TAGaStory", "story upload failed: ${it.message}") }
        }
    }

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
            withContext(Dispatchers.IO) {
                runCatching {
                    val session = container.accountManager.currentSession() ?: return@runCatching
                    TagaApi(session.serverUrl, session.accessToken).updateProfile(displayName, bio)
                }.onFailure { Log.w("TAGaProfile", "profile update failed: ${it.message}") }
            }
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
        container.accountManager.clear()
        appContext.getSharedPreferences("taga_session", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        appContext.getSharedPreferences("taga_state_sync", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        appContext.getSharedPreferences("taga_update", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        listOf("stories", "media_cache", "updates").forEach { name ->
            runCatching { java.io.File(appContext.filesDir, name).deleteRecursively() }
        }
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

