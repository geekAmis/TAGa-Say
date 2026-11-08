package com.tagasay.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tagasay.app.BuildConfig
import kotlinx.coroutines.launch
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.model.ServerStatus
import com.tagasay.app.data.model.ServerInfo
import com.tagasay.app.data.model.UserProfile
import com.tagasay.app.data.network.TwoFactorSetupResponse
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.QrCode
import com.tagasay.app.ui.components.SectionCard
import com.tagasay.app.ui.components.SettingRow
import com.tagasay.app.ui.theme.TagaAmber
import com.tagasay.app.ui.theme.TagaGreen
import com.tagasay.app.ui.theme.TagaPurple
import com.tagasay.app.ui.theme.TagaRed

@Composable
fun SettingsScreen(
    settings: AppSettings,
    profile: UserProfile,
    server: ServerStatus,
    serverInfo: ServerInfo?,
    fingerprint: String,
    onSetup2fa: suspend () -> TwoFactorSetupResponse?,
    onEnable2fa: suspend (String) -> Boolean,
    onTwoFactorStatus: suspend () -> Boolean,
    onOpenProfile: () -> Unit,
    onUpdateProfile: (String, String, String, String) -> Unit,
    onDarkTheme: (Boolean) -> Unit,
    onReadReceipts: (Boolean) -> Unit,
    onNotifications: (Boolean) -> Unit,
    onShowTyping: (Boolean) -> Unit,
    onScreenSecurity: (Boolean) -> Unit,
    onCheckServer: () -> Unit,
    onResetDemo: () -> Unit,
    onWipe: () -> Unit,
    onOpenChatSettings: () -> Unit = {},
    onOpenFolders: () -> Unit = {},
    onOpenStickers: () -> Unit = {},
) {
    var editingProfile by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var twoFaEnabled by remember { mutableStateOf(false) }
    var twoFaSetup by remember { mutableStateOf<TwoFactorSetupResponse?>(null) }
    var twoFaCode by remember { mutableStateOf("") }
    var twoFaError by remember { mutableStateOf("") }
    var twoFaBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { twoFaEnabled = onTwoFactorStatus() }

    val start2fa: () -> Unit = {
        if (!twoFaBusy) {
            twoFaBusy = true
            twoFaError = ""
            scope.launch {
                twoFaSetup = onSetup2fa()
                if (twoFaSetup == null) twoFaError = "Сервер недоступен"
                twoFaBusy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "Настройки",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 10.dp),
        )

        // Profile
        SectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenProfile() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    name = profile.displayName.ifBlank { settings.displayName },
                    color = profile.avatarColor,
                    size = 62.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        profile.displayName.ifBlank { settings.displayName.ifBlank { "Без имени" } },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "@${profile.username.ifBlank { settings.username.ifBlank { "username" } }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Аккаунт защищён паролем",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { editingProfile = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Редактировать профиль")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Security
        Text(
            "Безопасность",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        SectionCard {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Key, contentDescription = null, tint = TagaPurple)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Отпечаток ключа", style = MaterialTheme.typography.titleMedium)
                    Text(
                        fingerprint.ifBlank { "Ключ ещё не создан" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(fingerprint)) },
                    enabled = fingerprint.isNotBlank(),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Скопировать отпечаток")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoRow(
                icon = Icons.Filled.Lock,
                title = "Протокол",
                value = "X3DH + Double Ratchet + PQXDH",
            )
            InfoRow(
                icon = Icons.Filled.VerifiedUser,
                title = "Локальное хранилище",
                value = "AES-256-GCM + Android Keystore",
            )
            InfoRow(
                icon = Icons.Filled.Security,
                title = "Транспорт",
                value = if (BuildConfig.CERT_PIN.isNotBlank()) "TLS 1.3 + pinning" else "TLS 1.3",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { start2fa() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = TagaGreen)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Двухфакторная аутентификация", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (twoFaEnabled) "Включена · Google Authenticator" else "Google Authenticator / Aegis",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = start2fa, enabled = !twoFaBusy) {
                    Text(
                        if (twoFaBusy) "…" else if (twoFaEnabled) "Вкл." else "Настроить",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Privacy
        Text(
            "Приватность",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        SectionCard {
            SettingRow(
                title = "Отчёты о прочтении",
                subtitle = "Собеседник видит, что вы прочитали сообщение",
                checked = settings.readReceipts,
                onCheckedChange = onReadReceipts,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "Показывать «печатает…»",
                subtitle = "Индикатор набора текста",
                checked = settings.showTyping,
                onCheckedChange = onShowTyping,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "Защита от скриншотов",
                subtitle = "FLAG_SECURE: запрет скриншотов и записи экрана",
                checked = settings.screenSecurity,
                onCheckedChange = onScreenSecurity,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "Уведомления",
                subtitle = "Push без содержимого сообщения",
                checked = settings.notifications,
                onCheckedChange = onNotifications,
            )
        }

        Spacer(Modifier.height(14.dp))

        // Appearance
        Text(
            "Оформление",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        SectionCard {
            SettingRow(
                title = "Тёмная тема",
                subtitle = "Как в Telegram и VK",
                checked = settings.darkTheme,
                onCheckedChange = onDarkTheme,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsLinkRow(
                icon = Icons.Filled.Palette,
                title = "Настройки чатов",
                subtitle = "Размер текста, обои, углы пузырей",
                onClick = onOpenChatSettings,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsLinkRow(
                icon = Icons.Filled.Folder,
                title = "Папки с чатами",
                subtitle = "Фильтры и группировка списка чатов",
                onClick = onOpenFolders,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsLinkRow(
                icon = Icons.Filled.EmojiEmotions,
                title = "Стикеры",
                subtitle = "Наборы, избранное, установка",
                onClick = onOpenStickers,
            )
        }

        Spacer(Modifier.height(14.dp))

        // Server
        Text(
            "Сервер",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (server.online) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = if (server.online) TagaGreen else TagaAmber,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (server.online) "Сервер доступен" else "Сервер недоступен",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (server.online) {
                                "Задержка: ${server.latencyMs ?: "—"} мс" +
                                    (server.version?.let { " · v$it" } ?: "")
                            } else {
                                "Обратитесь к владельцу проекта, если проблема сохраняется"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onCheckServer) { Text("Проверить") }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                ServerInfoRow("Адрес", settings.serverUrl)
                Spacer(Modifier.height(6.dp))
                if (serverInfo == null || !serverInfo.aezaAvailable) {
                    Text(
                        if (serverInfo == null) {
                            "Данные хостинга загружаются…"
                        } else {
                            "Данные AEZA недоступны" +
                                (serverInfo.error?.let { ": $it" } ?: "")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ServerInfoRow("Оплачен до", formatHostDate(serverInfo.paidUntil))
                    Spacer(Modifier.height(4.dp))
                    ServerInfoRow(
                        "Баланс ЛК AEZA",
                        formatMoney(serverInfo.balance, serverInfo.currency),
                    )
                    Spacer(Modifier.height(4.dp))
                    ServerInfoRow(
                        "Стоимость сервера",
                        formatMoney(serverInfo.price, serverInfo.currency) +
                            paymentTermLabel(serverInfo.paymentTerm),
                    )
                    Spacer(Modifier.height(4.dp))
                    ServerInfoRow(
                        "Не хватает до продления",
                        formatMoney(serverInfo.shortfall, serverInfo.currency),
                    )
                    Spacer(Modifier.height(4.dp))
                    ServerInfoRow(
                        "Удаление провайдером",
                        (if (serverInfo.deletionEstimated) "≈ " else "") +
                            formatHostDate(serverInfo.deletionAt),
                    )
                    Spacer(Modifier.height(4.dp))
                    ServerInfoRow("Серверов с TAGa-Say", serverInfo.projectServers.toString())
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val url = serverInfo.donateUrl?.takeIf { it.isNotBlank() }
                                ?: "https://vk.ru/rf_dron"
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Донат на сервер")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Data
        Text(
            "Данные",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        SectionCard {
            if (BuildConfig.DEBUG) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { confirmReset = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = TagaAmber)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Сбросить демо-данные", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Вернуть исходные чаты и сообщения",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { confirmWipe = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = TagaRed)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Удалить все данные",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TagaRed,
                    )
                    Text(
                        "Локальный vault и настройки будут уничтожены",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "TAGa-Say ${BuildConfig.VERSION_NAME} · MIT\n" +
                "Ключи не покидают устройство. Сервер видит только шифротекст.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
    }

    twoFaSetup?.let { setup ->
        AlertDialog(
            onDismissRequest = { twoFaSetup = null },
            icon = { Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = TagaGreen) },
            title = { Text("Google Authenticator") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCode(setup.otpauthUri, modifier = Modifier.size(220.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Секрет: ${setup.secret}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = twoFaCode,
                        onValueChange = {
                            twoFaCode = it.filter(Char::isDigit).take(6)
                            twoFaError = ""
                        },
                        label = { Text("Код из приложения") },
                        singleLine = true,
                        isError = twoFaError.isNotBlank(),
                        supportingText = { if (twoFaError.isNotBlank()) Text(twoFaError) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = twoFaCode.length == 6 && !twoFaBusy,
                    onClick = {
                        scope.launch {
                            twoFaBusy = true
                            val ok = onEnable2fa(twoFaCode)
                            twoFaBusy = false
                            if (ok) {
                                twoFaEnabled = true
                                twoFaSetup = null
                                twoFaCode = ""
                            } else {
                                twoFaError = "Неверный код или сервер недоступен"
                            }
                        }
                    },
                ) { Text("Включить") }
            },
            dismissButton = {
                TextButton(onClick = { twoFaSetup = null }) { Text("Отмена") }
            },
        )
    }

    if (editingProfile) {
        EditProfileDialog(
            profile = profile,
            onDismiss = { editingProfile = false },
            onSave = { name, username, bio ->
                onUpdateProfile(name, username, "", bio)
                editingProfile = false
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить демо-данные?") },
            text = { Text("Текущие чаты и сообщения будут заменены исходным набором.") },
            confirmButton = {
                TextButton(onClick = { onResetDemo(); confirmReset = false }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = TagaRed) },
            title = { Text("Удалить всё?") },
            text = {
                Text(
                    "Будут удалены локальное хранилище, ключи сессий и настройки. " +
                        "Это действие необратимо.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onWipe(); confirmWipe = false }) {
                    Text("Удалить", color = TagaRed)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsLinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditProfileDialog(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(profile.displayName) }
    var username by remember { mutableStateOf(profile.username) }
    var bio by remember { mutableStateOf(profile.bio) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Профиль") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Имя") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it.trimStart('@').filter { ch ->
                            ch.isLetterOrDigit() || ch == '_'
                        }.take(32)
                    },
                    label = { Text("@username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it.take(120) },
                    label = { Text("О себе") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), username, bio.trim()) },
                enabled = name.isNotBlank() && username.length >= 3,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
