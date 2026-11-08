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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
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
import com.tagasay.app.data.model.StickerPack
import com.tagasay.app.ui.theme.TagaPurple

@Composable
fun StickerPacksScreen(
    packs: List<StickerPack>,
    favorites: List<String>,
    onBack: () -> Unit,
    onInstall: (String, Boolean) -> Unit,
    onAddPack: (String, List<String>) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    var viewing by remember { mutableStateOf<StickerPack?>(null) }
    var adding by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
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
                    "Стикеры",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (favorites.isNotEmpty()) {
                    item {
                        Text(
                            "Избранное",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            modifier = Modifier.heightIn(max = 220.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(favorites) { sticker ->
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { onToggleFavorite(sticker) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(sticker, fontSize = 26.sp)
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Наборы",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(packs, key = { it.id }) { pack ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewing = pack },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(TagaPurple.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(pack.stickers.firstOrNull() ?: "🙂", fontSize = 26.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    pack.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${pack.stickers.size} стикеров · ${pack.author}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onInstall(pack.id, !pack.installed) }) {
                                Text(if (pack.installed) "Убрать" else "Установить")
                            }
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { adding = true },
            containerColor = TagaPurple,
            contentColor = Color.White,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Свой набор") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 18.dp),
        )
    }

    viewing?.let { pack ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(pack.title) },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.heightIn(max = 320.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(pack.stickers) { sticker ->
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onToggleFavorite(sticker) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(sticker, fontSize = 24.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewing = null }) { Text("Закрыть") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onInstall(pack.id, !pack.installed)
                        viewing = null
                    },
                ) { Text(if (pack.installed) "Убрать набор" else "Установить набор") }
            },
        )
    }

    if (adding) {
        AddPackDialog(
            onDismiss = { adding = false },
            onAdd = { title, stickers ->
                onAddPack(title, stickers)
                adding = false
            },
        )
    }
}

@Composable
private fun AddPackDialog(
    onDismiss: () -> Unit,
    onAdd: (String, List<String>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var stickers by remember { mutableStateOf("😀 😎 🥳") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Свой набор стикеров") },
        text = {
            Column {
                TextField(
                    value = title,
                    onValueChange = { title = it.take(40) },
                    placeholder = { Text("Название набора") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = stickers,
                    onValueChange = { stickers = it.take(200) },
                    placeholder = { Text("Стикеры через пробел") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Вставьте эмодзи через пробел — они станут стикерами набора.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stickers.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stickers, fontSize = 24.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && stickers.isNotBlank(),
                onClick = {
                    val list = stickers.split(' ', '\n', ',')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                    onAdd(title.trim(), list)
                },
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
