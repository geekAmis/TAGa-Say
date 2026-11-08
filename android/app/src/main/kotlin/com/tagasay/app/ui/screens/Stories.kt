package com.tagasay.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tagasay.app.data.model.Story
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.formatTime
import com.tagasay.app.ui.theme.TagaPurple
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Группирует истории по автору: для каждого — последняя активная. */
private fun latestByAuthor(stories: List<Story>): List<Story> = stories
    .filterNot { it.archived || it.expired }
    .groupBy { it.authorUsername }
    .values
    .mapNotNull { list -> list.maxByOrNull { it.createdAt } }

@Composable
fun StoriesRow(
    stories: List<Story>,
    myName: String,
    myColor: Long,
    onOpenMine: () -> Unit,
    onOpenStory: (String) -> Unit,
) {
    val mine = stories.filter { it.mine && !it.archived }
    val others = latestByAuthor(stories.filterNot { it.mine })
        .sortedWith(compareBy({ it.viewed }, { -it.createdAt }))

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(74.dp)
                    .clickable(onClick = onOpenMine),
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    StoryAvatar(
                        name = myName,
                        color = myColor,
                        localUri = mine.firstOrNull()?.localUri,
                        viewed = false,
                        ring = mine.isNotEmpty(),
                    )
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Моя история",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 9.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        items(others, key = { it.authorUsername }) { story ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(74.dp)
                    .clickable { onOpenStory(story.id) },
            ) {
                StoryAvatar(
                    name = story.authorName,
                    color = story.avatarColor,
                    localUri = story.localUri,
                    viewed = story.viewed,
                    ring = true,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    story.authorName.substringBefore(' '),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StoryAvatar(
    name: String,
    color: Long,
    localUri: String?,
    viewed: Boolean,
    ring: Boolean,
) {
    val ringBrush = if (viewed) {
        Brush.linearGradient(listOf(Color(0xFF5A5A66), Color(0xFF5A5A66)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF4CC38A), Color(0xFF2FB6B0)))
    }
    Box(
        modifier = Modifier
            .size(66.dp)
            .then(
                if (ring) {
                    Modifier
                        .clip(CircleShape)
                        .background(ringBrush)
                        .padding(3.dp)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (ring) 60.dp else 60.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (localUri != null) {
                AsyncImage(
                    model = localUri,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(color), TagaPurple))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.take(2).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun StoryViewer(
    stories: List<Story>,
    startId: String,
    onClose: () -> Unit,
    onViewed: (String) -> Unit,
    onDelete: (String) -> Unit = {},
    onArchive: (String) -> Unit = {},
) {
    if (stories.isEmpty()) return
    var index by remember(stories, startId) {
        mutableStateOf(stories.indexOfFirst { it.id == startId }.coerceAtLeast(0))
    }
    var progress by remember { mutableFloatStateOf(0f) }
    val story = stories.getOrNull(index) ?: return

    LaunchedEffect(index) {
        progress = 0f
        onViewed(story.id)
        val started = System.currentTimeMillis()
        while (progress < 1f) {
            delay(50)
            progress = ((System.currentTimeMillis() - started) / 6000f).coerceIn(0f, 1f)
        }
        if (index < stories.lastIndex) index++ else onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable {
                if (progress < 1f) progress = 1f
            },
    ) {
        if (story.localUri != null) {
            AsyncImage(
                model = story.localUri,
                contentDescription = story.caption,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(story.avatarColor), Color(0xFF14141E)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    story.authorName.take(2).uppercase(),
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                stories.forEachIndexed { i, _ ->
                    LinearProgressIndicator(
                        progress = {
                            when {
                                i < index -> 1f
                                i == index -> progress
                                else -> 0f
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    name = story.authorName,
                    color = story.avatarColor,
                    size = 38.dp,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        story.authorName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "сегодня в ${formatTime(story.createdAt)}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            if (story.caption.isNotBlank()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        story.caption,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            if (story.mine) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.clickable { onArchive(story.id); onClose() },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Inventory2,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("В архив", color = Color.White)
                        }
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.clickable { onDelete(story.id); onClose() },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = Color(0xFFFF8A80),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Удалить", color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clickable {
                            if (index > 0) index-- else onClose()
                        },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clickable {
                            if (index < stories.lastIndex) index++ else onClose()
                        },
                )
            }
        }
    }
}

@Composable
fun MyStoriesScreen(
    stories: List<Story>,
    onBack: () -> Unit,
    onAdd: (Uri, String) -> Unit,
    onDelete: (String) -> Unit,
    onArchive: (String) -> Unit,
    onViewed: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var captionFor by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            caption = ""
            captionFor = uri
        }
    }

    val active = stories.filter { it.mine && !it.archived }
    val archived = stories.filter { it.mine && it.archived }
    val visible = if (tab == 0) active else archived

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
                "Мои истории",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { picker.launch("image/*") }) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить историю")
            }
        }

        if (visible.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("📖", fontSize = 72.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (tab == 0) "Публикаций пока нет…" else "В архиве пусто",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (tab == 0) "Публикуйте фотографии и видео в своём профиле" else "Сюда попадут истории, которые вы уберёте из профиля",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                if (tab == 0) {
                    Button(
                        onClick = { picker.launch("image/*") },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Добавить")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.id }) { story ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { viewing = story.id }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            if (story.localUri != null) {
                                AsyncImage(
                                    model = story.localUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text("📷", fontSize = 26.sp, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                story.caption.ifBlank { "История" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                storyDate(story.createdAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(story.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Удалить",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = {
                    Text(
                        "Опубликованные (${active.size})",
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = {
                    Text(
                        "Архив (${archived.size})",
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
            )
        }
    }

    captionFor?.let { uri ->
        AlertDialog(
            onDismissRequest = { captionFor = null },
            title = { Text("Новая история") },
            text = {
                Column {
                    TextField(
                        value = caption,
                        onValueChange = { caption = it.take(120) },
                        placeholder = { Text("Подпись") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "История будет видна 24 часа и хранится на этом устройстве.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAdd(uri, caption.trim())
                        captionFor = null
                    },
                ) { Text("Опубликовать") }
            },
            dismissButton = {
                TextButton(onClick = { captionFor = null }) { Text("Отмена") }
            },
        )
    }

    viewing?.let { id ->
        StoryViewer(
            stories = visible,
            startId = id,
            onClose = { viewing = null },
            onViewed = onViewed,
            onDelete = onDelete,
            onArchive = onArchive,
        )
    }
}

private fun storyDate(timestamp: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(timestamp))
