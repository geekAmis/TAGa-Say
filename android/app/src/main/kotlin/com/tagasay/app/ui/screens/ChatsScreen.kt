package com.tagasay.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.ChatFolder
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.Contact
import com.tagasay.app.data.model.Story
import com.tagasay.app.data.model.UserProfile
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.SearchField
import com.tagasay.app.ui.components.UnreadBadge
import com.tagasay.app.ui.components.formatChatTime
import com.tagasay.app.ui.components.previewText
import com.tagasay.app.ui.theme.TagaPurple

@Composable
fun ChatsScreen(
    chats: List<Chat>,
    folders: List<ChatFolder>,
    stories: List<Story>,
    profile: UserProfile,
    contacts: List<Contact>,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onCreateGroup: (String, List<String>) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenCalls: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenStories: () -> Unit,
    onMarkStoryViewed: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var folderId by remember { mutableStateOf<String?>(null) }
    var viewingStory by remember { mutableStateOf<String?>(null) }
    var showNewGroup by remember { mutableStateOf(false) }
    val folder = folders.firstOrNull { it.id == folderId }
    val q = query.trim()

    val filtered = remember(chats, folder, q) {
        chats
            .filter { chat -> folder == null || chat.inFolder(folder) }
            .filter {
                q.isBlank() ||
                    it.title.contains(q, ignoreCase = true) ||
                    it.lastMessage.contains(q, ignoreCase = true) ||
                    it.username.contains(q, ignoreCase = true)
            }
            .sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.lastTimestamp })
    }

    val visibleStories = remember(stories) {
        stories.filterNot { it.archived || it.expired }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "TAGa-Say",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = "Шифрование включено",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showNewGroup = true }) {
                    Icon(Icons.Filled.Groups, contentDescription = "Новая группа")
                }
                IconButton(onClick = onOpenCalls) {
                    Icon(Icons.Filled.Call, contentDescription = "Звонки")
                }
                IconButton(onClick = onOpenChannels) {
                    Icon(Icons.Filled.Campaign, contentDescription = "Каналы")
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Поиск")
                }
            }

            Spacer(Modifier.height(2.dp))
            StoriesRow(
                stories = visibleStories,
                myName = profile.displayName.ifBlank { "Вы" },
                myColor = profile.avatarColor,
                onOpenMine = onOpenStories,
                onOpenStory = { viewingStory = it },
            )
            Spacer(Modifier.height(10.dp))

            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Поиск чатов и сообщений",
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FolderChip(
                        title = "Все",
                        emoji = "",
                        selected = folderId == null,
                        onClick = { folderId = null },
                    )
                }
                items(folders, key = { it.id }) { item ->
                    FolderChip(
                        title = item.title,
                        emoji = item.emoji,
                        selected = folderId == item.id,
                        onClick = { folderId = item.id },
                    )
                }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.clickable(onClick = onOpenFolders),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Настроить папки",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp).size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Здесь пока нет чатов", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (folder != null) "Измените папку или создайте новый чат." else "Начните переписку во вкладке «Контакты».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
                ) {
                    items(filtered, key = { it.id }) { chat ->
                        ChatRow(chat = chat, folders = folders, onClick = { onOpenChat(chat.id) })
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onNewChat,
            containerColor = TagaPurple,
            contentColor = Color.White,
            icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            text = { Text("Новый чат") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 18.dp),
        )
    }

    viewingStory?.let { id ->
        val viewerStories = visibleStories
            .filterNot { it.mine }
            .sortedBy { it.createdAt }
        StoryViewer(
            stories = viewerStories.ifEmpty { visibleStories },
            startId = id,
            onClose = { viewingStory = null },
            onViewed = onMarkStoryViewed,
        )
    }

    if (showNewGroup) {
        NewGroupDialog(
            contacts = contacts,
            onDismiss = { showNewGroup = false },
            onCreate = { title, members ->
                showNewGroup = false
                onCreateGroup(title, members)
            },
        )
    }
}

@Composable
private fun NewGroupDialog(
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая группа") },
        text = {
            Column {
                TextField(
                    value = title,
                    onValueChange = { title = it.take(80) },
                    placeholder = { Text("Название группы") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Участники (${selected.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(contacts, key = { it.id }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (contact.username in selected) {
                                        selected - contact.username
                                    } else {
                                        selected + contact.username
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = contact.username in selected,
                                onCheckedChange = {
                                    selected = if (it) selected + contact.username else selected - contact.username
                                },
                            )
                            Avatar(
                                name = contact.displayName,
                                color = contact.avatarColor,
                                size = 34.dp,
                            )
                            Spacer(Modifier.width(8.dp))
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
            TextButton(
                enabled = title.isNotBlank() && selected.size >= 1,
                onClick = { onCreate(title.trim(), selected.toList()) },
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun FolderChip(
    title: String,
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(50),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (emoji.isNotBlank()) {
                Text(emoji, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun ChatRow(chat: Chat, folders: List<ChatFolder>, onClick: () -> Unit) {
    val folderTag = folders.firstOrNull { it.types.isNotEmpty() && chat.inFolder(it) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name = chat.title,
            color = chat.avatarColor,
            size = 54.dp,
            online = chat.online,
            verified = chat.verified,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when (chat.type) {
                    ChatType.CHANNEL -> {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "канал",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    ChatType.BOT -> {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "бот",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    else -> Unit
                }
                if (folderTag != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        folderTag.emoji.ifBlank { "•" },
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatChatTime(chat.lastTimestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.lastMessage.startsWith("📷") || chat.lastMessage.startsWith("📎") ||
                    chat.lastMessage.startsWith("🎤") || chat.lastMessage.startsWith("Стикер")
                ) {
                    Text(
                        text = chat.lastMessage.take(2),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    text = previewText(
                        chat.lastMessage
                            .removePrefix("📷 ").removePrefix("📎 ").removePrefix("🎬 ")
                            .removePrefix("🎤 ").removePrefix("📊 "),
                        46,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (chat.unread > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.muted) {
                    Icon(
                        imageVector = Icons.Filled.VolumeOff,
                        contentDescription = "Без звука",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                if (chat.pinned && chat.unread == 0) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = "Закреплён",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (chat.unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    UnreadBadge(chat.unread, chat.muted)
                }
            }
        }
    }
}

private fun Chat.inFolder(folder: ChatFolder): Boolean {
    if (folder.unreadOnly && unread == 0) return false
    if (folder.types.isEmpty()) return true
    if (type in folder.types) return true
    return folder.includeSaved && type == ChatType.SAVED
}
