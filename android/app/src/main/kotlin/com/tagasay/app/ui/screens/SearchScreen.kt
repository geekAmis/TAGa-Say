package com.tagasay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tagasay.app.data.model.Channel
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.Contact
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.SearchField
import com.tagasay.app.ui.components.formatChatTime
import com.tagasay.app.ui.theme.TagaGreen

private val searchTabs = listOf("Чаты", "Контакты", "Каналы", "Медиа")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    chats: List<Chat>,
    contacts: List<Contact>,
    channels: List<Channel>,
    messages: Map<String, List<Message>>,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenContact: (Contact) -> Unit,
    onOpenChannel: (Channel) -> Unit,
    onOpenBots: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(0) }
    var recent by remember { mutableStateOf(emptyList<String>()) }

    val q = query.trim()
    val filteredChats = remember(chats, q) {
        if (q.isBlank()) emptyList() else chats.filter {
            it.title.contains(q, true) ||
                it.username.contains(q, true) ||
                it.lastMessage.contains(q, true)
        }
    }
    val filteredContacts = remember(contacts, q) {
        if (q.isBlank()) emptyList() else contacts.filter {
            it.displayName.contains(q, true) || it.username.contains(q, true)
        }
    }
    val filteredChannels = remember(channels, q) {
        if (q.isBlank()) emptyList() else channels.filter {
            it.title.contains(q, true) ||
                it.username.contains(q, true) ||
                it.description.contains(q, true)
        }
    }
    val media = remember(messages, q) {
        messages.values.flatten()
            .filter { it.kind == MessageKind.IMAGE || it.kind == MessageKind.VIDEO }
            .filter {
                q.isBlank() || it.attachment?.name?.contains(q, true) == true ||
                    it.text.contains(q, true)
            }
            .sortedByDescending { it.timestamp }
            .take(120)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Поиск",
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = q.isNotBlank(),
                onClick = {
                    recent = (listOf(q) + recent.filterNot { it.equals(q, true) }).take(8)
                },
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Найти")
            }
        }

        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.background,
            divider = {},
        ) {
            searchTabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            title,
                            fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                )
            }
        }

        if (q.isBlank()) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                if (recent.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 8.dp, top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Недавние",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { recent = emptyList() }) { Text("Очистить") }
                        }
                    }
                    items(recent) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { query = item }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(item, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(contacts.take(10), key = { it.id }) { contact ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(72.dp)
                                    .clickable { onOpenContact(contact) },
                            ) {
                                Avatar(
                                    name = contact.displayName,
                                    color = contact.avatarColor,
                                    size = 58.dp,
                                    online = contact.online,
                                    verified = contact.verified,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    contact.displayName.substringBefore(' '),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBots() }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Боты",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Каталог, мои боты и @BotFather",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else {
            when (tab) {
                0 -> ResultList(filteredChats.size) {
                    items(filteredChats, key = { it.id }) { chat ->
                        ChatResult(chat, onClick = { onOpenChat(chat.id) })
                    }
                }
                1 -> ResultList(filteredContacts.size) {
                    items(filteredContacts, key = { it.id }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenContact(contact) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                contact.displayName,
                                contact.avatarColor,
                                size = 48.dp,
                                online = contact.online,
                                verified = contact.verified,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "@${contact.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (contact.online) TagaGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                2 -> ResultList(filteredChannels.size) {
                    items(filteredChannels, key = { it.id }) { channel ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChannel(channel) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                channel.title,
                                channel.avatarColor,
                                size = 48.dp,
                                verified = channel.verified,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(channel.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${channel.subscribers} подписчиков · канал",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                else -> MediaGrid(media)
            }
        }
    }
}

@Composable
private fun ResultList(count: Int, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    if (count == 0) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Ничего не найдено", style = MaterialTheme.typography.titleMedium)
            Text(
                "Попробуйте другой запрос.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) { content() }
    }
}

@Composable
private fun ChatResult(chat: Chat, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            chat.title,
            chat.avatarColor,
            size = 48.dp,
            online = chat.online,
            verified = chat.verified,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (chat.type == ChatType.BOT) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "бот",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                chat.lastMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            formatChatTime(chat.lastTimestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MediaGrid(media: List<Message>) {
    if (media.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Медиа не найдено", style = MaterialTheme.typography.titleMedium)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(media, key = { it.id }) { message ->
                val local = message.attachment?.localUri
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (local != null && message.kind == MessageKind.IMAGE) {
                        AsyncImage(
                            model = local,
                            contentDescription = message.attachment?.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (message.kind == MessageKind.VIDEO) Icons.Filled.PlayArrow else Icons.Filled.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (message.kind == MessageKind.VIDEO) "Видео" else "Фото",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun chatSubtitle(chat: Chat): String = when (chat.type) {
    ChatType.CHANNEL -> "канал"
    ChatType.GROUP -> "группа"
    ChatType.SAVED -> "избранное"
    ChatType.BOT -> "бот"
    else -> "@${chat.username}"
}
