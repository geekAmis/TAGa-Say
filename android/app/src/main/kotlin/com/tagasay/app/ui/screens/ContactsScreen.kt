package com.tagasay.app.ui.screens

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tagasay.app.data.model.Contact
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.SearchField
import com.tagasay.app.ui.theme.TagaAmber
import com.tagasay.app.ui.theme.TagaGreen

@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    phoneDiscoverable: Boolean,
    onTogglePhoneDiscovery: (Boolean) -> Unit,
    onOpenContact: (Contact) -> Unit,
    onAddByUsername: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var usernameQuery by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    val filtered = remember(contacts, query) {
        contacts.filter {
            query.isBlank() ||
                it.displayName.contains(query, ignoreCase = true) ||
                it.username.contains(query, ignoreCase = true) ||
                it.phone.contains(query, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 90.dp,
        ),
    ) {
        item {
            Text(
                "Контакты",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Имя, @username или телефон",
            )
            Spacer(Modifier.height(14.dp))
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.PersonAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Найти по @username", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Поиск работает без доступа к адресной книге",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = usernameQuery,
                                onValueChange = {
                                    usernameQuery = it.trimStart('@')
                                        .filter { ch -> ch.isLetterOrDigit() || ch == '_' }
                                        .take(32)
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                decorationBox = { inner ->
                                    androidx.compose.foundation.layout.Box {
                                        if (usernameQuery.isEmpty()) {
                                            Text(
                                                "@username",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { if (usernameQuery.isNotBlank()) onAddByUsername(usernameQuery) },
                            enabled = usernameQuery.length >= 3,
                        ) { Text("Найти") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        item {
            Surface(
                color = if (phoneDiscoverable) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Phone,
                        contentDescription = null,
                        tint = if (phoneDiscoverable) TagaGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (phoneDiscoverable) "Поиск по номеру включён" else "Поиск по номеру выключен",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (phoneDiscoverable) {
                                "Другие могут найти вас по номеру телефона"
                            } else {
                                "Вас нельзя найти по номеру. Хеш номера не участвует в поиске"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = phoneDiscoverable,
                        onCheckedChange = onTogglePhoneDiscovery,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Мои контакты",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }

        items(filtered, key = { it.id }) { contact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpenContact(contact) }
                    .padding(vertical = 9.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    name = contact.displayName,
                    color = contact.avatarColor,
                    size = 50.dp,
                    online = contact.online,
                    verified = contact.verified,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "@${contact.username}" + if (phoneDiscoverable) " · ${contact.phone}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (contact.online) {
                    Text(
                        "в сети",
                        style = MaterialTheme.typography.bodySmall,
                        color = TagaGreen,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(TagaGreen.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.QrCode, contentDescription = null, tint = TagaAmber)
                        Spacer(Modifier.width(10.dp))
                        Text("Пригласить в TAGa-Say", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Отправьте ссылку — приложение откроется сразу, без браузера.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "taga://open/@lilanga",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString("taga://open/@lilanga"))
                            },
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Скопировать ссылку")
                        }
                    }
                }
            }
        }
    }
}
