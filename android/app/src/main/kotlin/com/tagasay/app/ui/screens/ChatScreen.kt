package com.tagasay.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.FilePresent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.MessageStatus
import com.tagasay.app.data.model.ScanReport
import com.tagasay.app.data.model.ScanVerdict
import com.tagasay.app.data.model.StickerPack
import com.tagasay.app.data.model.UserProfile
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.formatBytes
import com.tagasay.app.ui.components.formatDateSeparator
import com.tagasay.app.ui.components.formatDuration
import com.tagasay.app.ui.components.formatTime
import com.tagasay.app.ui.theme.TagaAmber
import com.tagasay.app.ui.theme.TagaGreen
import com.tagasay.app.ui.theme.TagaPurple
import com.tagasay.app.ui.theme.TagaRed
import kotlinx.coroutines.launch

private data class PendingAttachment(val uri: Uri, val report: ScanReport)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    chat: Chat,
    messages: List<Message>,
    profile: UserProfile,
    stickerPacks: List<StickerPack>,
    allChats: List<Chat>,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onCall: () -> Unit,
    onSend: (String, MessageKind, Attachment?, String?, String?) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onForward: (String, String) -> Unit,
    onMarkRead: () -> Unit,
    onInspect: suspend (Uri) -> ScanReport,
) {
    var input by remember(chat.id) { mutableStateOf("") }
    var editing by remember(chat.id) { mutableStateOf<Message?>(null) }
    var replying by remember(chat.id) { mutableStateOf<Message?>(null) }
    var selected by remember { mutableStateOf<Message?>(null) }
    var pending by remember { mutableStateOf<PendingAttachment?>(null) }
    var showStickers by remember { mutableStateOf(false) }
    var forwarding by remember { mutableStateOf<Message?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val report = onInspect(uri)
                pending = PendingAttachment(uri, report)
            }
        }
    }

    LaunchedEffect(chat.id) { onMarkRead() }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.lastIndex) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            name = chat.title,
                            color = chat.avatarColor,
                            size = 36.dp,
                            online = chat.online,
                            verified = chat.verified,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                chat.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    chat.type == ChatType.CHANNEL -> "канал"
                                    chat.type == ChatType.GROUP -> "группа"
                                    chat.online -> "в сети"
                                    else -> "был(а) недавно"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onCall) {
                        Icon(Icons.Filled.Call, contentDescription = "Позвонить")
                    }
                    IconButton(onClick = onOpenProfile) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Профиль")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Профиль") },
                            onClick = { showMenu = false; onOpenProfile() },
                        )
                        DropdownMenuItem(
                            text = { Text("Видеозвонок") },
                            onClick = { showMenu = false; onCall() },
                            leadingIcon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Проверить шифрование") },
                            onClick = {
                                showMenu = false
                                scope.launch {
                                    snackbar.showSnackbar("Локальный vault активен · E2EE-транспорт в разработке")
                                }
                            },
                            leadingIcon = { Icon(Icons.Filled.Security, contentDescription = null) },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column {
                if (editing != null || replying != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (editing != null) Icons.Filled.Edit else Icons.Filled.Reply,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (editing != null) "Редактирование" else "Ответ на сообщение",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                (editing ?: replying)?.text?.ifBlank { "Вложение" } ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { editing = null; replying = null }) {
                            Icon(Icons.Filled.Close, contentDescription = "Отменить")
                        }
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { picker.launch("*/*") }) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = "Прикрепить файл",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { showStickers = true }) {
                            Icon(
                                Icons.Filled.EmojiEmotions,
                                contentDescription = "Стикеры",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextField(
                            value = input,
                            onValueChange = {
                                input = it
                                if (it.isNotEmpty() && editing == null) replying = replying
                            },
                            placeholder = {
                                Text(
                                    if (chat.type == ChatType.CHANNEL) "Написать в канал" else "Сообщение",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            maxLines = 5,
                            shape = RoundedCornerShape(22.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(TagaPurple),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconButton(
                                onClick = {
                                    val text = input.trim()
                                    val target = editing
                                    when {
                                        target != null -> {
                                            onEdit(target.id, text)
                                            editing = null
                                            input = ""
                                        }
                                        text.isNotEmpty() -> {
                                            onSend(text, MessageKind.TEXT, null, null, replying?.text)
                                            input = ""
                                            replying = null
                                        }
                                    }
                                },
                                enabled = input.isNotBlank(),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Отправить",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item("encryption-note") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                tint = TagaGreen,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "Локальное шифрование · E2EE-транспорт в разработке",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                val previous = messages.getOrNull(index - 1)
                if (previous == null || !sameDay(previous.timestamp, message.timestamp)) {
                    Text(
                        text = formatDateSeparator(message.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                if (message.kind == MessageKind.SYSTEM) {
                    SystemMessage(message)
                } else {
                    MessageBubble(
                        message = message,
                        showAuthor = chat.type != ChatType.PRIVATE && !message.isMine,
                        onLongPress = { selected = message },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------- message actions

    selected?.let { message ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Text(
                    previewOf(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                ActionRow(Icons.Filled.Reply, "Ответить") {
                    replying = message
                    selected = null
                }
                if (message.text.isNotBlank()) {
                    ActionRow(Icons.Filled.ContentCopy, "Копировать текст") {
                        clipboard.setText(AnnotatedString(message.text))
                        selected = null
                        scope.launch { snackbar.showSnackbar("Скопировано") }
                    }
                }
                ActionRow(Icons.Filled.Forward, "Переслать") {
                    forwarding = message
                    selected = null
                }
                if (message.isMine && message.kind == MessageKind.TEXT) {
                    ActionRow(Icons.Filled.Edit, "Редактировать") {
                        editing = message
                        input = message.text
                        selected = null
                    }
                }
                ActionRow(Icons.Filled.Delete, "Удалить", danger = true) {
                    onDelete(message.id)
                    selected = null
                    scope.launch { snackbar.showSnackbar("Сообщение удалено") }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // --------------------------------------------------------- forward sheet

    forwarding?.let { message ->
        AlertDialog(
            onDismissRequest = { forwarding = null },
            title = { Text("Переслать сообщение") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    allChats.filter { it.id != chat.id }.forEach { target ->
                        item(target.id) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = {
                                            onForward(message.id, target.id)
                                            forwarding = null
                                            scope.launch { snackbar.showSnackbar("Переслано в «${target.title}»") }
                                        },
                                        onLongClick = {},
                                    )
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(target.title, target.avatarColor, size = 40.dp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(target.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        when (target.type) {
                                            ChatType.CHANNEL -> "канал"
                                            ChatType.GROUP -> "группа"
                                            ChatType.SAVED -> "избранное"
                                            else -> "@${target.username}"
                                        },
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
                TextButton(onClick = { forwarding = null }) { Text("Отмена") }
            },
        )
    }

    // --------------------------------------------------------- sticker sheet

    if (showStickers) {
        val installed = stickerPacks.filter { it.installed }
        ModalBottomSheet(
            onDismissRequest = { showStickers = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                Text("Стикеры", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    installed.forEach { pack ->
                        items(pack.stickers) { sticker ->
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = {
                                            onSend("", MessageKind.STICKER, null, sticker, replying?.text)
                                            showStickers = false
                                            replying = null
                                        },
                                        onLongClick = {},
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(sticker, fontSize = 30.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // ------------------------------------------------------------ scan dialog

    pending?.let { attachment ->
        val report = attachment.report
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = {
                Icon(
                    imageVector = when (report.verdict) {
                        ScanVerdict.SAFE -> Icons.Filled.Shield
                        ScanVerdict.WARNING -> Icons.Filled.ErrorOutline
                        ScanVerdict.DANGEROUS -> Icons.Filled.ErrorOutline
                    },
                    contentDescription = null,
                    tint = when (report.verdict) {
                        ScanVerdict.SAFE -> TagaGreen
                        ScanVerdict.WARNING -> TagaAmber
                        ScanVerdict.DANGEROUS -> TagaRed
                    },
                )
            },
            title = {
                Text(
                    when (report.verdict) {
                        ScanVerdict.SAFE -> "Файл безопасен"
                        ScanVerdict.WARNING -> "Есть предупреждения"
                        ScanVerdict.DANGEROUS -> "Файл заблокирован"
                    },
                )
            },
            text = {
                Column {
                    Text(
                        "${report.fileName} · ${formatBytes(report.sizeBytes)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${report.detectedType} · ${report.mimeType ?: "unknown"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    report.reasons.forEach { reason ->
                        Text("• $reason", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "SHA-256: ${report.sha256.take(24)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = report.verdict != ScanVerdict.DANGEROUS,
                    onClick = {
                        val kind = when {
                            report.mimeType?.startsWith("image/") == true -> MessageKind.IMAGE
                            report.mimeType?.startsWith("video/") == true -> MessageKind.VIDEO
                            report.mimeType?.startsWith("audio/") == true -> MessageKind.AUDIO
                            else -> MessageKind.FILE
                        }
                        onSend(
                            "",
                            kind,
                            Attachment(
                                name = report.fileName,
                                mimeType = report.mimeType,
                                sizeBytes = report.sizeBytes,
                                sha256 = report.sha256,
                                scanVerdict = report.verdict,
                                localUri = attachment.uri.toString(),
                            ),
                            null,
                            replying?.text,
                        )
                        replying = null
                        pending = null
                    },
                ) { Text(if (report.verdict == ScanVerdict.DANGEROUS) "Заблокировано" else "Отправить") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun SystemMessage(message: Message) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    showAuthor: Boolean,
    onLongPress: () -> Unit,
) {
    val bubbleColor = if (message.isMine) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (message.isMine) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (message.isMine) {
        Color.White.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (message.isMine) 18.dp else 4.dp,
                        bottomEnd = if (message.isMine) 4.dp else 18.dp,
                    ),
                )
                .background(bubbleColor)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (message.forwardedFrom != null) {
                Text(
                    "Переслано от ${message.forwardedFrom}",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (showAuthor) {
                Text(
                    message.senderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TagaPurple,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (message.replyToText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.12f))
                        .padding(8.dp),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (message.isMine) Color.White else TagaPurple),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message.replyToText,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when (message.kind) {
                MessageKind.STICKER -> Text(
                    message.sticker.orEmpty(),
                    fontSize = 64.sp,
                    modifier = Modifier.padding(4.dp),
                )
                MessageKind.IMAGE, MessageKind.VIDEO -> MediaAttachment(message, contentColor)
                MessageKind.AUDIO -> AudioAttachment(message, contentColor, secondary)
                MessageKind.FILE -> FileAttachment(message, contentColor, secondary)
                MessageKind.CALL -> CallAttachment(message, contentColor, secondary)
                else -> if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.edited) {
                    Text(
                        "изм.",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    formatTime(message.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                )
                if (message.isMine) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = when (message.status) {
                            MessageStatus.READ -> Icons.Filled.DoneAll
                            MessageStatus.DELIVERED -> Icons.Filled.DoneAll
                            MessageStatus.SENT -> Icons.Filled.Check
                            else -> Icons.Filled.Check
                        },
                        contentDescription = null,
                        tint = if (message.status == MessageStatus.READ) Color(0xFFB9F6CA) else secondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaAttachment(message: Message, contentColor: Color) {
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .width(240.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2B2352), Color(0xFF1C3057)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (message.kind == MessageKind.VIDEO) Icons.Filled.PlayArrow else Icons.Filled.Image,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message.attachment?.name ?: if (message.kind == MessageKind.VIDEO) "Видео" else "Фото",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (message.attachment?.scanVerdict == ScanVerdict.WARNING) {
                Text(
                    "⚠ проверено с предупреждением",
                    style = MaterialTheme.typography.bodySmall,
                    color = TagaAmber,
                )
            }
        }
    }
}

@Composable
private fun AudioAttachment(message: Message, contentColor: Color, secondary: Color) {
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .width(230.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Воспроизвести", tint = contentColor)
        }
        Spacer(Modifier.width(10.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val heights = listOf(8, 16, 24, 12, 20, 30, 14, 22, 10, 18, 26, 12, 8, 20, 14, 24, 10)
            heights.forEach { h ->
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(h.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(contentColor.copy(alpha = 0.7f)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("0:14", style = MaterialTheme.typography.bodySmall, color = secondary)
    }
}

@Composable
private fun FileAttachment(message: Message, contentColor: Color, secondary: Color) {
    val attachment = message.attachment ?: return
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .width(250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(contentColor.copy(alpha = 0.12f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(contentColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.FilePresent, contentDescription = null, tint = contentColor)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatBytes(attachment.sizeBytes)} · ${if (attachment.scanVerdict == ScanVerdict.SAFE) "проверено" else "внимание"}",
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }
    }
}

@Composable
private fun CallAttachment(message: Message, contentColor: Color, secondary: Color) {
    Row(
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = contentColor)
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Звонок", style = MaterialTheme.typography.bodyLarge, color = contentColor)
            Text(
                message.callSeconds?.let { "Длительность ${formatDuration(it)}" } ?: "Отменён",
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .androidxClickable(onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clip(RoundedCornerShape(4.dp))).then(
        Modifier.combinedClickable(onClick = onClick, onLongClick = {}),
    )

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

private fun previewOf(message: Message): String = when (message.kind) {
    MessageKind.TEXT -> message.text
    MessageKind.STICKER -> "Стикер ${message.sticker.orEmpty()}"
    MessageKind.IMAGE -> "Фото"
    MessageKind.VIDEO -> "Видео"
    MessageKind.AUDIO -> "Голосовое сообщение"
    MessageKind.FILE -> "Файл ${message.attachment?.name.orEmpty()}"
    MessageKind.SYSTEM -> message.text
    MessageKind.CALL -> "Звонок"
}
