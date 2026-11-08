package com.tagasay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tagasay.app.ui.theme.TagaPurple
import com.tagasay.app.ui.theme.chatPalette

private data class ChatThemeOption(val id: String, val label: String, val emoji: String)

private val chatThemes = listOf(
    ChatThemeOption("violet", "Фиолетовая", "💜"),
    ChatThemeOption("green", "Зелёная", "🌿"),
    ChatThemeOption("blue", "Синяя", "🌊"),
    ChatThemeOption("sunset", "Закат", "🌅"),
    ChatThemeOption("mono", "Моно", "🎨"),
)

private val wallpapers = listOf(
    "default" to "Стандарт",
    "solid" to "Однотонная",
    "aurora" to "Аврора",
    "dots" to "Точки",
)

@Composable
fun ChatSettingsScreen(
    messageTextSize: Int,
    bubbleRadius: Int,
    chatTheme: String,
    chatWallpaper: String,
    darkTheme: Boolean,
    onBack: () -> Unit,
    onTextSize: (Int) -> Unit,
    onBubbleRadius: (Int) -> Unit,
    onTheme: (String) -> Unit,
    onWallpaper: (String) -> Unit,
    onDarkTheme: (Boolean) -> Unit,
) {
    val palette = chatPalette(chatTheme, chatWallpaper, darkTheme)

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
                "Настройки чатов",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Размер текста сообщений",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Slider(
                    value = messageTextSize.toFloat(),
                    onValueChange = { onTextSize(it.toInt()) },
                    valueRange = 12f..24f,
                    steps = 11,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    messageTextSize.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(30.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(palette.background)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Surface(
                        color = palette.incoming,
                        shape = RoundedCornerShape(bubbleRadius.dp),
                        modifier = Modifier.widthIn(max = 230.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                "Михаил",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Доброе утро! 👋",
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = messageTextSize.sp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = palette.outgoing,
                        shape = RoundedCornerShape(bubbleRadius.dp),
                        modifier = Modifier
                            .align(Alignment.End)
                            .widthIn(max = 230.dp),
                    ) {
                        Text(
                            "В Токио утро 😎",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = messageTextSize.sp),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Цветовая тема",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chatThemes.forEach { option ->
                    val selected = option.id == chatTheme
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onTheme(option.id) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(62.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            chatPalette(option.id, chatWallpaper, darkTheme).outgoing.copy(alpha = 0.85f),
                                            chatPalette(option.id, chatWallpaper, darkTheme).outgoing.copy(alpha = 0.35f),
                                        ),
                                    ),
                                )
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(option.emoji, fontSize = 24.sp)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Ночная тема",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = darkTheme, onCheckedChange = onDarkTheme)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Фон чата",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                wallpapers.forEach { (id, label) ->
                    val selected = id == chatWallpaper
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onWallpaper(id) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(chatPalette(chatTheme, id, darkTheme).background)
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        if (selected) {
                            Text("Выбрано", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Углы блоков с сообщениями",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Slider(
                    value = bubbleRadius.toFloat(),
                    onValueChange = { onBubbleRadius(it.toInt()) },
                    valueRange = 4f..24f,
                    steps = 19,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    bubbleRadius.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(30.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Оформление применяется ко всем чатам на этом устройстве.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
