package com.tagasay.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.sp
import com.tagasay.app.data.model.Contact
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.SearchField
import com.tagasay.app.ui.theme.TagaBlue
import com.tagasay.app.ui.theme.TagaGreen
import com.tagasay.app.ui.theme.TagaPurple

@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    onOpenContact: (Contact) -> Unit,
    onAddByUsername: (String) -> Unit,
    onOpenCalls: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val filtered = remember(contacts, query) {
        contacts.filter {
            query.isBlank() ||
                it.displayName.contains(query, ignoreCase = true) ||
                it.username.contains(query, ignoreCase = true)
        }.sortedBy { it.displayName.lowercase() }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Контакты",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(
                            if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = "Поиск",
                        )
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Filled.SortByAlpha, contentDescription = "Сортировка")
                    }
                }
                if (showSearch) {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Имя, @username или телефон",
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(AnnotatedString("taga://open/@lilanga"))
                                    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(TagaBlue),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = Color.White)
                            }
                            Spacer(Modifier.width(14.dp))
                            Text("Пригласить друзей", style = MaterialTheme.typography.titleMedium)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenCalls)
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(TagaGreen),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Call, contentDescription = null, tint = Color.White)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("Недавние звонки", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "История входящих и исходящих",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            item {
                Text(
                    "Сортировка по времени захода",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            items(filtered, key = { it.id }) { contact ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenContact(contact) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(
                        name = contact.displayName,
                        color = contact.avatarColor,
                        size = 54.dp,
                        online = contact.online,
                        verified = contact.verified,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                contact.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (contact.verified) {
                                Spacer(Modifier.width(4.dp))
                                Text("✔", color = TagaBlue, fontSize = 12.sp)
                            }
                        }
                        Text(
                            if (contact.online) "в сети" else "был(а) недавно",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (contact.online) TagaGreen else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Text("Пригласить в TAGa-Say", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Отправьте ссылку — приложение откроется сразу, без браузера.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "taga://open/@lilanga",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { addDialog = true },
            containerColor = TagaPurple,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 18.dp),
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = "Добавить контакт")
        }
    }

    if (addDialog) {
        AddContactDialog(
            onDismiss = { addDialog = false },
            onFind = { username ->
                addDialog = false
                onAddByUsername(username)
            },
        )
    }
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onFind: (String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Найти по @username") },
        text = {
            Column {
                TextField(
                    value = username,
                    onValueChange = {
                        username = it.trimStart('@')
                            .filter { ch -> ch.isLetterOrDigit() || ch == '_' }
                            .take(32)
                    },
                    singleLine = true,
                    placeholder = { Text("@username") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Поиск работает без доступа к адресной книге.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.length >= 3,
                onClick = { onFind(username) },
            ) { Text("Найти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
