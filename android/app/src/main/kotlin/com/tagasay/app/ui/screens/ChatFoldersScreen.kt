package com.tagasay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tagasay.app.data.model.ChatFolder
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.ui.theme.TagaPurple

private val folderEmojis = listOf(
    "💬", "💼", "👤", "🔔", "🔥", "🌙", "🎮", "🎨", "📚", "🚀", "🛡️", "☕",
)

@Composable
fun ChatFoldersScreen(
    folders: List<ChatFolder>,
    onBack: () -> Unit,
    onSave: (ChatFolder) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<ChatFolder?>(null) }
    var creating by remember { mutableStateOf(false) }
    var tagsEnabled by remember { mutableStateOf(false) }

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
            Text(
                "Папки с чатами",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 40.dp,
            ),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🗂️", fontSize = 40.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Создайте папки с нужными чатами и переключайтесь между ними.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Text(
                    "Рекомендованные папки",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            item {
                RecommendedFolder(
                    emoji = "🔔",
                    title = "Новые",
                    subtitle = "Чаты с новыми сообщениями.",
                    exists = folders.any { it.unreadOnly },
                ) {
                    onSave(
                        ChatFolder(
                            id = "recommended_unread",
                            title = "Новые",
                            emoji = "🔔",
                            unreadOnly = true,
                        ),
                    )
                }
            }
            item {
                RecommendedFolder(
                    emoji = "👤",
                    title = "Личные",
                    subtitle = "Сообщения из личных чатов.",
                    exists = folders.any {
                        !it.unreadOnly &&
                            ChatType.PRIVATE in it.types &&
                            ChatType.SAVED in it.types
                    },
                ) {
                    onSave(
                        ChatFolder(
                            id = "recommended_personal",
                            title = "Личные",
                            emoji = "👤",
                            types = listOf(ChatType.PRIVATE, ChatType.SAVED),
                            includeSaved = true,
                        ),
                    )
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Папки с чатами",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            items(folders, key = { it.id }) { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = folder }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(folder.emoji.ifBlank { "💬" }, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        folder.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { editing = folder }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Удалить папку",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(TagaPurple.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = TagaPurple,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Создать новую папку",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Теги для чатов", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Названия папок рядом с каждым чатом в списке.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = tagsEnabled, onCheckedChange = { tagsEnabled = it })
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        FolderDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = {
                onSave(it)
                creating = false
            },
            onDelete = {},
        )
    }

    editing?.let { folder ->
        FolderDialog(
            initial = folder,
            onDismiss = { editing = null },
            onSave = {
                onSave(it)
                editing = null
            },
            onDelete = {
                onDelete(folder.id)
                editing = null
            },
        )
    }
}

@Composable
private fun RecommendedFolder(
    emoji: String,
    title: String,
    subtitle: String,
    exists: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (exists) {
            Text(
                "Добавлено",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Surface(
                color = TagaPurple,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable(onClick = onAdd),
            ) {
                Text(
                    "Добавить",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderDialog(
    initial: ChatFolder?,
    onDismiss: () -> Unit,
    onSave: (ChatFolder) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var emoji by remember(initial) { mutableStateOf(initial?.emoji ?: "💬") }
    var types by remember(initial) { mutableStateOf(initial?.types.orEmpty()) }
    var unreadOnly by remember(initial) { mutableStateOf(initial?.unreadOnly ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text(emoji, fontSize = 30.sp) },
        title = { Text(if (initial == null) "Новая папка" else "Изменить папку") },
        text = {
            Column {
                TextField(
                    value = title,
                    onValueChange = { title = it.take(40) },
                    placeholder = { Text("Название папки") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("Иконка", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    folderEmojis.take(8).forEach { item ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (item == emoji) {
                                        TagaPurple.copy(alpha = 0.22f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .clickable { emoji = item },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(item, fontSize = 17.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Типы чатов", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TypeChip("Личные", ChatType.PRIVATE, types) { types = it }
                    TypeChip("Группы", ChatType.GROUP, types) { types = it }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TypeChip("Каналы", ChatType.CHANNEL, types) { types = it }
                    TypeChip("Избранное", ChatType.SAVED, types) { types = it }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Только непрочитанные",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = unreadOnly, onCheckedChange = { unreadOnly = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        ChatFolder(
                            id = initial?.id ?: "folder_${System.currentTimeMillis()}",
                            title = title.trim(),
                            emoji = emoji,
                            types = types,
                            unreadOnly = unreadOnly,
                            includeSaved = ChatType.SAVED in types,
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@Composable
private fun TypeChip(
    label: String,
    type: ChatType,
    selected: List<ChatType>,
    onChange: (List<ChatType>) -> Unit,
) {
    FilterChip(
        selected = type in selected,
        onClick = {
            onChange(if (type in selected) selected - type else selected + type)
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}
