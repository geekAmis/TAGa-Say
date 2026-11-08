package com.tagasay.app.data.network

import android.content.Context
import android.util.Log
import com.tagasay.app.data.AppRepository
import com.tagasay.app.data.local.SettingsStore
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.model.CallEntry
import com.tagasay.app.data.model.Channel
import com.tagasay.app.data.model.ChatFolder
import com.tagasay.app.data.model.Contact
import com.tagasay.app.data.model.StickerPack
import com.tagasay.app.data.model.Story
import com.tagasay.app.data.model.UserProfile
import com.tagasay.core.crypto.CryptoEngine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class SyncedState(
    val updatedAt: Long = 0,
    val profile: UserProfile = UserProfile(),
    val settings: AppSettings = AppSettings(),
    val folders: List<ChatFolder> = emptyList(),
    val stickerPacks: List<StickerPack> = emptyList(),
    val stickerFavorites: List<String> = emptyList(),
    val callLog: List<CallEntry> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val stories: List<Story> = emptyList(),
)

/**
 * Синхронизация состояния приложения через relay.
 *
 * Сервер хранит только AES-256-GCM ciphertext. Ключ детерминированно
 * выводится из Argon2id-verifier пароля (он одинаков на всех устройствах
 * аккаунта) через HKDF-SHA256, поэтому второе устройство может расшифровать
 * профиль, настройки, папки, стикеры, звонки, контакты, каналы и истории.
 */
class StateSync(
    context: Context,
    private val repository: AppRepository,
    private val settingsStore: SettingsStore,
    private val crypto: CryptoEngine,
    private val storyTransfer: StoryTransfer,
    private val deviceKeyStore: com.tagasay.app.data.local.DeviceKeyStore,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("taga_state_sync", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun pullAndApply(api: TagaApi, session: Session): Boolean = runCatching {
        val remote = api.getState()
        if (remote.version <= prefs.getLong(KEY_VERSION, 0) || remote.ciphertext.isBlank()) {
            return@runCatching false
        }
        val state = decrypt(session, remote.ciphertext)
        apply(api, state)
        prefs.edit()
            .putLong(KEY_VERSION, remote.version)
            .putLong(KEY_UPDATED_AT, state.updatedAt)
            .apply()
        Log.i(TAG, "state pulled: v${remote.version}")
        true
    }.onFailure {
        Log.w(TAG, "state pull failed: ${it.message}")
    }.getOrDefault(false)

    suspend fun pushIfChanged(api: TagaApi, session: Session): Boolean = runCatching {
        val remote = api.getState()
        if (remote.version > prefs.getLong(KEY_VERSION, 0) && remote.ciphertext.isNotBlank()) {
            // Другое устройство успело раньше: сначала принимаем его версию.
            val state = decrypt(session, remote.ciphertext)
            apply(api, state)
            prefs.edit()
                .putLong(KEY_VERSION, remote.version)
                .putLong(KEY_UPDATED_AT, state.updatedAt)
                .apply()
        }
        val state = buildState()
        // Хешируем содержимое без updatedAt, иначе timestamp делает hash
        // нестабильным и состояние уходит на сервер каждые 15 секунд.
        val content = state.copy(updatedAt = 0)
        val contentPlaintext = json.encodeToString(SyncedState.serializer(), content).encodeToByteArray()
        val hash = crypto.sha256Hex(contentPlaintext)
        if (hash == prefs.getString(KEY_HASH, null) &&
            prefs.getString(KEY_FORMAT, null) == "v2"
        ) {
            return@runCatching false
        }
        val now = System.currentTimeMillis()
        val toSend = state.copy(updatedAt = now)
        val sendPlaintext = json.encodeToString(SyncedState.serializer(), toSend).encodeToByteArray()
        val ciphertext = Base64.getEncoder().encodeToString(
            StateCrypto.encryptV2(crypto, masterKey(session), session.username, sendPlaintext),
        )
        val saved = api.putState(ciphertext, now)
        prefs.edit()
            .putLong(KEY_VERSION, saved.version)
            .putLong(KEY_UPDATED_AT, now)
            .putString(KEY_HASH, hash)
            .putString(KEY_FORMAT, "v2")
            .apply()
        Log.i(TAG, "state pushed: v${saved.version}")
        true
    }.onFailure {
        Log.w(TAG, "state push failed: ${it.message}")
    }.getOrDefault(false)

    private suspend fun buildState(): SyncedState {
        val vault = repository.vault.value
        val settings = settingsStore.settings.first()
        return SyncedState(
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0),
            profile = vault.profile,
            settings = settings,
            folders = vault.chatFolders,
            stickerPacks = vault.stickerPacks,
            stickerFavorites = vault.stickerFavorites,
            callLog = vault.callLog,
            contacts = vault.contacts,
            channels = vault.channels,
            stories = vault.stories,
        )
    }

    private suspend fun apply(api: TagaApi, state: SyncedState) {
        repository.applySyncedState(
            profile = state.profile,
            folders = state.folders,
            stickerPacks = state.stickerPacks,
            stickerFavorites = state.stickerFavorites,
            callLog = state.callLog,
            contacts = state.contacts,
            channels = state.channels,
            stories = state.stories,
        )
        settingsStore.applySynced(state.settings)

        // Подтягиваем медиа историй, которых нет на этом устройстве.
        repository.vault.value.stories
            .filter { it.mediaId != null && it.localUri == null }
            .forEach { story ->
                val uri = runCatching { storyTransfer.download(api, story) }.getOrNull()
                if (uri != null) {
                    repository.attachStoryMedia(
                        storyId = story.id,
                        mediaId = story.mediaId.orEmpty(),
                        keyBase64 = story.keyBase64.orEmpty(),
                        sha256 = story.sha256.orEmpty(),
                        mimeType = story.mimeType ?: "image/jpeg",
                        sizeBytes = story.sizeBytes,
                        localUri = uri,
                    )
                }
            }
    }

    private fun decrypt(session: Session, ciphertext: String): SyncedState {
        val bytes = Base64.getDecoder().decode(ciphertext)
        val plaintext = runCatching {
            StateCrypto.decryptV2(crypto, masterKey(session), session.username, bytes)
        }.getOrElse {
            // Состояние старого формата. Расшифровываем один раз и сразу
            // перезаписываем сервер версией v2.
            Log.w(TAG, "legacy state detected; migrating to v2")
            prefs.edit().remove(KEY_FORMAT).apply()
            StateCrypto.decryptLegacy(
                crypto,
                session.passwordVerifier,
                session.username,
                bytes,
            )
        }
        return json.decodeFromString(SyncedState.serializer(), plaintext.decodeToString())
    }

    private fun masterKey(session: Session): ByteArray =
        deviceKeyStore.accountMasterKey(
            "${session.serverUrl.trimEnd('/')}|${session.username.lowercase()}",
        )

    private companion object {
        const val TAG = "TAGaState"
        const val KEY_VERSION = "version"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_HASH = "last_hash"
        const val KEY_FORMAT = "format"
    }
}
