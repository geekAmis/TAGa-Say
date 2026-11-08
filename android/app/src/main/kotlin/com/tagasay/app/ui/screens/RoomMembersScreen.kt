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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.Contact
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.theme.TagaGreen
import com.tagasay.app.ui.theme.TagaPurple

@Composable
fun RoomMembersScreen(
    chat: Chat,
    contacts: List<Contact>,
    currentUsername: String,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onLeave: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    val isAdmin = chat.ownerUsername.equals(currentUsername, true) ||
        chat.admins.any { it.equals(currentUsername, true) }
    val me = currentUsername.trimStart('@').lowercase()
    val memberNames = (chat.members + chat.ownerUsername)
        .filter { it.isNotBlank() }
        .map { it.trimStart('@').lowercase() }
        .distinct()
    val available = contacts.filter { contact ->
        memberNames.none { it.equals(contact.username, true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${memberNames.size} участников · ${if (chat.type == com.tagasay.app.data.model.ChatType.CHANNEL) "канал" else "группа"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isAdmin) {
                IconButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = "Добавить участника")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
        ) {
            items(memberNames, key = { it }) { username ->
                val contact = contacts.firstOrNull { it.username.equals(username, true) }
                val displayName = when {
                    username == me -> "Вы"
                    contact != null -> contact.displayName
                    else -> "@$username"
                }
                val color = contact?.avatarColor ?: 0xFF7B61FF
                val owner = username.equals(chat.ownerUsername, true)
                val admin = chat.admins.any { it.equals(username, true) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name = displayName, color = color, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                owner -> "Владелец"
                                admin -> "Администратор"
                                else -> "@$username"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (owner || admin) TagaGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isAdmin && !owner && username != me) {
                        IconButton(onClick = { onRemove(username) }) {
                            Icon(
                                Icons.Filled.RemoveCircleOutline,
                                contentDescription = "Удалить участника",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onLeave)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (chat.type == com.tagasay.app.data.model.ChatType.CHANNEL) {
                            "Отписаться от канала"
                        } else {
                            "Покинуть группу"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Добавить участника") },
            text = {
                if (available.isEmpty()) {
                    Text("Все контакты уже в этой комнате.")
                } else {
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(available, key = { it.id }) { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onAdd(contact.username)
                                        adding = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(
                                    name = contact.displayName,
                                    color = contact.avatarColor,
                                    size = 36.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(contact.displayName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "@${contact.username}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { adding = false }) { Text("Закрыть") }
            },
        )
    }
}
