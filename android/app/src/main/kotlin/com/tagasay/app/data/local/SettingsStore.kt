package com.tagasay.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tagasay.app.BuildConfig
import com.tagasay.app.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "taga_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val onboarded = booleanPreferencesKey("onboarded")
        val username = stringPreferencesKey("username")
        val displayName = stringPreferencesKey("display_name")
        val phone = stringPreferencesKey("phone")
        val phoneDiscoverable = booleanPreferencesKey("phone_discoverable")
        val readReceipts = booleanPreferencesKey("read_receipts")
        val darkTheme = booleanPreferencesKey("dark_theme")
        val notifications = booleanPreferencesKey("notifications")
        val serverUrl = stringPreferencesKey("server_url")
        val showTyping = booleanPreferencesKey("show_typing")
        val screenSecurity = booleanPreferencesKey("screen_security")
        val messageTextSize = intPreferencesKey("message_text_size")
        val bubbleRadius = intPreferencesKey("bubble_radius")
        val chatTheme = stringPreferencesKey("chat_theme")
        val chatWallpaper = stringPreferencesKey("chat_wallpaper")
    }

    /**
     * Release-сборка всегда использует relay, заданный при сборке.
     * Пользователь не может переключить сервер через настройки или DataStore.
     */
    private fun effectiveServerUrl(stored: String?): String =
        if (BuildConfig.DEBUG) {
            stored?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SERVER_URL
        } else {
            BuildConfig.DEFAULT_SERVER_URL
        }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs -> prefs.toSettings() }

    private fun androidx.datastore.preferences.core.Preferences.toSettings(): AppSettings = AppSettings(
        onboarded = this[Keys.onboarded] ?: false,
        username = this[Keys.username] ?: "",
        displayName = this[Keys.displayName] ?: "",
        phone = this[Keys.phone] ?: "",
        phoneDiscoverable = this[Keys.phoneDiscoverable] ?: true,
        readReceipts = this[Keys.readReceipts] ?: true,
        darkTheme = this[Keys.darkTheme] ?: true,
        notifications = this[Keys.notifications] ?: true,
        serverUrl = effectiveServerUrl(this[Keys.serverUrl]),
        showTyping = this[Keys.showTyping] ?: true,
        screenSecurity = this[Keys.screenSecurity] ?: true,
        messageTextSize = this[Keys.messageTextSize] ?: 16,
        bubbleRadius = this[Keys.bubbleRadius] ?: 18,
        chatTheme = this[Keys.chatTheme] ?: "violet",
        chatWallpaper = this[Keys.chatWallpaper] ?: "default",
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.onboarded] = next.onboarded
            prefs[Keys.username] = next.username
            prefs[Keys.displayName] = next.displayName
            prefs[Keys.phone] = next.phone
            prefs[Keys.phoneDiscoverable] = next.phoneDiscoverable
            prefs[Keys.readReceipts] = next.readReceipts
            prefs[Keys.darkTheme] = next.darkTheme
            prefs[Keys.notifications] = next.notifications
            prefs[Keys.serverUrl] = effectiveServerUrl(next.serverUrl)
            prefs[Keys.showTyping] = next.showTyping
            prefs[Keys.screenSecurity] = next.screenSecurity
            prefs[Keys.messageTextSize] = next.messageTextSize
            prefs[Keys.bubbleRadius] = next.bubbleRadius
            prefs[Keys.chatTheme] = next.chatTheme
            prefs[Keys.chatWallpaper] = next.chatWallpaper
        }
    }

    suspend fun completeOnboarding(displayName: String, username: String) {
        update {
            it.copy(
                onboarded = true,
                displayName = displayName,
                username = username.trimStart('@'),
                phone = "",
                phoneDiscoverable = false,
            )
        }
    }

    suspend fun setPhoneDiscoverable(enabled: Boolean) = update { it.copy(phoneDiscoverable = enabled) }
    suspend fun setDarkTheme(enabled: Boolean) = update { it.copy(darkTheme = enabled) }
    suspend fun setReadReceipts(enabled: Boolean) = update { it.copy(readReceipts = enabled) }
    suspend fun setNotifications(enabled: Boolean) = update { it.copy(notifications = enabled) }
    suspend fun setTyping(enabled: Boolean) = update { it.copy(showTyping = enabled) }
    suspend fun setServerUrl(url: String) = update { it.copy(serverUrl = url) }
    suspend fun setProfile(displayName: String, username: String, phone: String, bio: String) =
        update { it.copy(displayName = displayName, username = username.trimStart('@'), phone = phone) }

    /** Применяет настройки, пришедшие с relay. Аккаунт и сервер не меняются. */
    suspend fun applySynced(next: AppSettings) = update { current ->
        current.copy(
            displayName = next.displayName.ifBlank { current.displayName },
            phone = next.phone,
            phoneDiscoverable = next.phoneDiscoverable,
            readReceipts = next.readReceipts,
            darkTheme = next.darkTheme,
            notifications = next.notifications,
            showTyping = next.showTyping,
            screenSecurity = next.screenSecurity,
            messageTextSize = next.messageTextSize,
            bubbleRadius = next.bubbleRadius,
            chatTheme = next.chatTheme,
            chatWallpaper = next.chatWallpaper,
        )
    }
}
