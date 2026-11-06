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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.SearchField
import com.tagasay.app.ui.components.UnreadBadge
import com.tagasay.app.ui.components.formatChatTime
import com.tagasay.app.ui.components.previewText
import com.tagasay.app.ui.theme.TagaPurple

@Composable
fun ChatsScreen(
    chats: List<Chat>,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(chats, query) {
        val q = query.trim()
        chats
            .filter {
                q.isBlank() ||
                    it.title.contains(q, ignoreCase = true) ||
                    it.lastMessage.contains(q, ignoreCase = true) ||
                    it.username.contains(q, ignoreCase = true)
            }
            .sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.lastTimestamp })
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
                    .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
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
                Avatar(
                    name = "Вы",
                    color = 0xFF7B61FF,
                    size = 40.dp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onOpenProfile),
                )
            }

            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Поиск чатов и сообщений",
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    bottom = 96.dp,
                ),
            ) {
                items(filtered, key = { it.id }) { chat ->
                    ChatRow(chat = chat, onClick = { onOpenChat(chat.id) })
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onNewChat,
            containerColor = TagaPurple,
            contentColor = androidx.compose.ui.graphics.Color.White,
            icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            text = { Text("Новый чат") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 18.dp),
        )
    }
}

@Composable
private fun ChatRow(chat: Chat, onClick: () -> Unit) {
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
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (chat.type == ChatType.CHANNEL) {
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
                Spacer(Modifier.weight(1f))
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
                    text = previewText(chat.lastMessage.removePrefix("📷 ").removePrefix("📎 "), 46),
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
