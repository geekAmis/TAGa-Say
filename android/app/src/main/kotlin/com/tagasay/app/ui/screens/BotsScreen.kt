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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tagasay.app.data.model.BotHubState
import com.tagasay.app.data.model.BotInfo
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.theme.TagaAmber
import com.tagasay.app.ui.theme.TagaPurple

/**
 * Хаб ботов: @BotFather, «мои боты» и каталог.
 *
 * Экран только открывает бот-чаты; управление (создание, токены, удаление)
 * живёт в самом BotFather — так поведение совпадает с серверным FSM и не
 * дублирует логику на клиенте.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(
    state: BotHubState,
    botFather: BotInfo?,
    onBack: () -> Unit,
    onOpenBot: (String) -> Unit,
    onOpenBotFather: () -> Unit,
    onRefresh: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }

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
            Column(Modifier.weight(1f)) {
                Text(
                    "Боты",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Каталог и управление через @BotFather",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                enabled = !state.loading,
                onClick = { onRefresh(query.trim()) },
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item("e2ee-note") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TagaAmber.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.LockOpen,
                        contentDescription = null,
                        tint = TagaAmber,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Бот-чаты не защищены сквозным шифрованием. Не отправляйте секреты.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            item("botfather") {
                SectionTitle("BotFather")
                BotCard(
                    username = "botfather",
                    title = botFather?.title ?: "BotFather",
                    subtitle = "Создать бота, токены, настройки и удаление",
                    verified = botFather?.verified == true,
                    badge = "официальный",
                    onClick = onOpenBotFather,
                )
            }

            if (state.mine.isNotEmpty()) {
                item("mine-title") { SectionTitle("Мои боты") }
                items(state.mine, key = { "mine:${it.username}" }) { bot ->
                    BotCard(
                        username = bot.username,
                        title = bot.title,
                        subtitle = buildString {
                            append("@${bot.username}")
                            if (bot.status != "active") append(" · ${bot.status}")
                        },
                        verified = bot.verified,
                        badge = "мой",
                        onClick = { onOpenBot(bot.username) },
                    )
                }
            }

            item("search") {
                SectionTitle("Каталог")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск ботов") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onRefresh(query.trim()) },
                    ),
                )
            }

            if (state.loading) {
                item("loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            state.error?.let { error ->
                item("error") {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }

            val catalog = state.catalog
                .filterNot { it.username.equals("botfather", ignoreCase = true) }
                .filterNot { candidate -> state.mine.any { it.username.equals(candidate.username, true) } }
            if (!state.loading && catalog.isEmpty() && state.error == null) {
                item("empty") {
                    Text(
                        if (query.isBlank()) {
                            "Пока нет публичных ботов. Создайте своего в @BotFather."
                        } else {
                            "По запросу «$query» ничего не найдено"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
            items(catalog, key = { it.username }) { bot ->
                BotCard(
                    username = bot.username,
                    title = bot.title,
                    subtitle = bot.about.ifBlank {
                        bot.description.ifBlank { "@${bot.username}" }
                    },
                    verified = bot.verified,
                    badge = if (bot.builtin) "официальный" else "",
                    onClick = { onOpenBot(bot.username) },
                )
            }

            item("hint") {
                TextButton(
                    onClick = onOpenBotFather,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "Управление ботами — в чате @BotFather",
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun BotCard(
    username: String,
    title: String,
    subtitle: String,
    verified: Boolean,
    badge: String,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(
                name = title.ifBlank { username },
                color = 0xFF7B61FF,
                size = 48.dp,
                verified = verified,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title.ifBlank { "@$username" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badge.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "@$username",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
