package com.tagasay.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            onboarded = prefs[Keys.onboarded] ?: false,
            username = prefs[Keys.username] ?: "",
            displayName = prefs[Keys.displayName] ?: "",
            phone = prefs[Keys.phone] ?: "",
            phoneDiscoverable = prefs[Keys.phoneDiscoverable] ?: true,
            readReceipts = prefs[Keys.readReceipts] ?: true,
            darkTheme = prefs[Keys.darkTheme] ?: true,
            notifications = prefs[Keys.notifications] ?: true,
            serverUrl = prefs[Keys.serverUrl] ?: "",
            showTyping = prefs[Keys.showTyping] ?: true,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = AppSettings(
                onboarded = prefs[Keys.onboarded] ?: false,
                username = prefs[Keys.username] ?: "",
                displayName = prefs[Keys.displayName] ?: "",
                phone = prefs[Keys.phone] ?: "",
                phoneDiscoverable = prefs[Keys.phoneDiscoverable] ?: true,
                readReceipts = prefs[Keys.readReceipts] ?: true,
                darkTheme = prefs[Keys.darkTheme] ?: true,
                notifications = prefs[Keys.notifications] ?: true,
                serverUrl = prefs[Keys.serverUrl] ?: "",
                showTyping = prefs[Keys.showTyping] ?: true,
            )
            val next = transform(current)
            prefs[Keys.onboarded] = next.onboarded
            prefs[Keys.username] = next.username
            prefs[Keys.displayName] = next.displayName
            prefs[Keys.phone] = next.phone
            prefs[Keys.phoneDiscoverable] = next.phoneDiscoverable
            prefs[Keys.readReceipts] = next.readReceipts
            prefs[Keys.darkTheme] = next.darkTheme
            prefs[Keys.notifications] = next.notifications
            prefs[Keys.serverUrl] = next.serverUrl
            prefs[Keys.showTyping] = next.showTyping
        }
    }

    suspend fun completeOnboarding(displayName: String, username: String, phone: String) {
        update {
            it.copy(
                onboarded = true,
                displayName = displayName,
                username = username.trimStart('@'),
                phone = phone,
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
}
