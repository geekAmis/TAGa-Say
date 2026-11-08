package com.tagasay.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.AppSettings
import com.tagasay.app.data.model.BotEntity
import com.tagasay.app.data.model.BotInfo
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.InlineKeyboardButton
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.MessageStatus
import com.tagasay.app.data.model.Poll
import com.tagasay.app.data.model.PollOption
import com.tagasay.app.data.model.ScanReport
import com.tagasay.app.data.model.ScanVerdict
import com.tagasay.app.data.model.StickerPack
import com.tagasay.app.data.model.UserProfile
import com.tagasay.app.data.model.validBotEntities
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.formatBytes
import com.tagasay.app.ui.components.formatDateSeparator
import com.tagasay.app.ui.components.formatDuration
import com.tagasay.app.ui.components.formatTime
import com.tagasay.app.ui.theme.ChatPalette
import com.tagasay.app.ui.theme.TagaAmber
import com.tagasay.app.ui.theme.TagaGreen
import com.tagasay.app.ui.theme.TagaPurple
import com.tagasay.app.ui.theme.TagaRed
import com.tagasay.app.ui.theme.chatPalette
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private data class PendingAttachment(val uri: Uri, val report: ScanReport)

/** Запасной список команд BotFather, если карточка ещё не отдала commands. */
private val BOTFATHER_COMMANDS = listOf(
    com.tagasay.app.data.model.BotCommandInfo("start", "Начать и показать меню"),
    com.tagasay.app.data.model.BotCommandInfo("help", "Список команд"),
    com.tagasay.app.data.model.BotCommandInfo("newbot", "Создать бота"),
    com.tagasay.app.data.model.BotCommandInfo("mybots", "Мои боты и карточки"),
    com.tagasay.app.data.model.BotCommandInfo("token", "Показать токен"),
    com.tagasay.app.data.model.BotCommandInfo("revoke", "Отозвать токен"),
    com.tagasay.app.data.model.BotCommandInfo("deletebot", "Удалить бота"),
    com.tagasay.app.data.model.BotCommandInfo("setname", "Имя бота"),
    com.tagasay.app.data.model.BotCommandInfo("setabouttext", "Короткое описание"),
    com.tagasay.app.data.model.BotCommandInfo("setdescription", "Описание до /start"),
    com.tagasay.app.data.model.BotCommandInfo("setcommands", "Команды бота"),
    com.tagasay.app.data.model.BotCommandInfo("deletecommands", "Очистить команды"),
    com.tagasay.app.data.model.BotCommandInfo("setprivacy", "Приватность в группах"),
    com.tagasay.app.data.model.BotCommandInfo("setjoingroups", "Добавление в группы"),
    com.tagasay.app.data.model.BotCommandInfo("setinline", "Inline-режим"),
    com.tagasay.app.data.model.BotCommandInfo("cancel", "Отменить диалог"),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    chat: Chat,
    messages: List<Message>,
    profile: UserProfile,
    stickerPacks: List<StickerPack>,
    stickerFavorites: List<String>,
    settings: AppSettings,
    allChats: List<Chat>,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenMembers: () -> Unit,
    onCall: () -> Unit,
    onSend: (String, MessageKind, Attachment?, String?, String?, String?, Poll?) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onForward: (String, String) -> Unit,
    onMarkRead: () -> Unit,
    onInspect: suspend (Uri) -> ScanReport,
    onDownloadAttachment: (String) -> Unit,
    onVotePoll: (String, String) -> Unit,
    onToggleStickerFavorite: (String) -> Unit,
    onManageStickers: () -> Unit,
    onReact: (String, String) -> Unit,
    bot: BotInfo? = null,
    botNotice: String? = null,
    onBotNoticeShown: () -> Unit = {},
    onBotCallback: (String, String) -> Unit = { _, _ -> },
    onBotOpenUrl: (String) -> Unit = {},
    onBotWarningShown: () -> Unit = {},
    onBotBlockToggle: () -> Unit = {},
) {
    var input by remember(chat.id) { mutableStateOf("") }
    var editing by remember(chat.id) { mutableStateOf<Message?>(null) }
    var replying by remember(chat.id) { mutableStateOf<Message?>(null) }
    var selected by remember { mutableStateOf<Message?>(null) }
    var pending by remember { mutableStateOf<PendingAttachment?>(null) }
    var showStickers by remember { mutableStateOf(false) }
    var stickerFavoritesTab by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showPoll by remember { mutableStateOf(false) }
    var stickerMenu by remember { mutableStateOf<String?>(null) }
    var forwarding by remember { mutableStateOf<Message?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showBotCommands by remember { mutableStateOf(false) }
    var showBotWarning by remember(chat.id) { mutableStateOf(false) }
    val requestedDownloads = remember(chat.id) { mutableSetOf<String>() }
    val palette = chatPalette(settings.chatTheme, settings.chatWallpaper, settings.darkTheme)
    val textSize = settings.messageTextSize.sp
    val isBot = chat.type == ChatType.BOT
    val botDisabled = isBot && (bot?.deleted == true || bot?.blocked == true)
    val canPost = !botDisabled && (chat.type != ChatType.CHANNEL ||
        chat.ownerUsername.isBlank() ||
        chat.ownerUsername.equals(settings.username, ignoreCase = true) ||
        chat.admins.any { it.equals(settings.username, ignoreCase = true) })

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

    val openAttachment: (Message) -> Unit = { message ->
        val attachment = message.attachment
        if (attachment != null) {
            val local = attachment.localUri
            if (local == null) {
                if (attachment.mediaId != null) {
                    onDownloadAttachment(message.id)
                    scope.launch { snackbar.showSnackbar("Загрузка вложения…") }
                }
            } else {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(local), attachment.mimeType ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Открыть"))
                }.onFailure {
                    scope.launch { snackbar.showSnackbar("Нет приложения для этого файла") }
                }
            }
        }
    }

    LaunchedEffect(chat.id) { onMarkRead() }
    LaunchedEffect(chat.id, bot?.warningShown) {
        if (isBot && bot != null && !bot.warningShown) showBotWarning = true
    }
    LaunchedEffect(botNotice) {
        if (!botNotice.isNullOrBlank()) {
            snackbar.showSnackbar(botNotice)
            onBotNoticeShown()
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.lastIndex) }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onOpenProfile),
                    ) {
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
                                    isBot && bot?.deleted == true -> "бот удалён"
                                    isBot -> "бот · не E2EE"
                                    chat.type == ChatType.CHANNEL -> "канал"
                                    chat.type == ChatType.GROUP -> "группа"
                                    chat.online -> "в сети"
                                    else -> "был(а) недавно"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isBot) TagaAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (!isBot) {
                        IconButton(onClick = onCall) {
                            Icon(Icons.Filled.Call, contentDescription = "Позвонить")
                        }
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Меню чата")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Профиль") },
                            onClick = { showMenu = false; onOpenProfile() },
                        )
                        DropdownMenuItem(
                            text = { Text("Медиа и файлы") },
                            onClick = { showMenu = false; onOpenMedia() },
                            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                        )
                        if (chat.type == ChatType.GROUP || chat.type == ChatType.CHANNEL) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (chat.type == ChatType.CHANNEL) "Подписчики" else "Участники",
                                    )
                                },
                                onClick = { showMenu = false; onOpenMembers() },
                                leadingIcon = { Icon(Icons.Filled.Groups, contentDescription = null) },
                            )
                        }
                        if (isBot && (bot?.username.equals("botfather", true) || !bot?.commands.isNullOrEmpty())) {
                            DropdownMenuItem(
                                text = { Text("Команды") },
                                onClick = { showMenu = false; showBotCommands = true },
                                leadingIcon = { Icon(Icons.Filled.SmartToy, contentDescription = null) },
                            )
                        }
                        if (isBot) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (bot?.blocked == true) "Разблокировать бота" else "Заблокировать бота",
                                    )
                                },
                                onClick = { showMenu = false; onBotBlockToggle() },
                                leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Настройки чата") },
                            onClick = { showMenu = false; onOpenSettings() },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        )
                        if (!isBot) {
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
                                        snackbar.showSnackbar("Double Ratchet активен · PQXDH · ключи на устройстве")
                                    }
                                },
                                leadingIcon = { Icon(Icons.Filled.Security, contentDescription = null) },
                            )
                        }
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
                        if (!isBot) {
                            IconButton(onClick = { showAttach = true }) {
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
                        }
                        TextField(
                            value = input,
                            onValueChange = {
                                input = it
                                if (it.isNotEmpty() && editing == null) replying = replying
                            },
                            enabled = canPost,
                            placeholder = {
                                Text(
                                    when {
                                        !canPost && bot?.deleted == true -> "Бот удалён"
                                        !canPost && bot?.blocked == true -> "Бот заблокирован"
                                        !canPost -> "Только администраторы могут писать"
                                        isBot -> "Сообщение боту"
                                        chat.type == ChatType.CHANNEL -> "Написать в канал"
                                        else -> "Сообщение"
                                    },
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
                                        target != null && text.isNotEmpty() -> {
                                            onEdit(target.id, text)
                                            editing = null
                                            input = ""
                                        }
                                        text.isNotEmpty() -> {
                                            onSend(text, MessageKind.TEXT, null, null, replying?.text, replying?.id, null)
                                            input = ""
                                            replying = null
                                        }
                                        !isBot -> picker.launch("image/*")
                                    }
                                },
                                enabled = canPost,
                            ) {
                                Icon(
                                    if (input.isBlank() && editing == null && !isBot) {
                                        Icons.Filled.PhotoCamera
                                    } else {
                                        Icons.AutoMirrored.Filled.Send
                                    },
                                    contentDescription = if (input.isBlank()) "Камера" else "Отправить",
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
                .padding(padding)
                .background(palette.background),
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
                                if (isBot) Icons.Filled.LockOpen else Icons.Filled.Lock,
                                contentDescription = null,
                                tint = if (isBot) TagaAmber else TagaGreen,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (isBot) {
                                    "Без сквозного шифрования: бот видит сообщения"
                                } else {
                                    "Сквозное шифрование libsignal включено"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isBot) TagaAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
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
                    if (
                        message.kind == MessageKind.IMAGE &&
                        message.attachment?.mediaId != null &&
                        message.attachment.localUri == null
                    ) {
                        LaunchedEffect(message.id) {
                            if (requestedDownloads.add(message.id)) {
                                onDownloadAttachment(message.id)
                            }
                        }
                    }
                    MessageBubble(
                        message = message,
                        showAuthor = chat.type != ChatType.PRIVATE && !message.isMine,
                        palette = palette,
                        bubbleRadius = settings.bubbleRadius,
                        textSize = textSize,
                        onLongPress = { selected = message },
                        onAttachmentClick = openAttachment,
                        onVote = { optionId -> onVotePoll(message.id, optionId) },
                        onReact = { emoji -> onReact(message.id, emoji) },
                        onBotCallback = { data -> onBotCallback(message.id, data) },
                        onBotOpenUrl = onBotOpenUrl,
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!isBot) {
                        listOf("👍", "❤️", "🔥", "😂", "😮", "😢").forEach { emoji ->
                            Surface(
                                color = if (message.myReaction == emoji) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.clickable {
                                    onReact(message.id, emoji)
                                    selected = null
                                },
                            ) {
                                Text(
                                    emoji,
                                    fontSize = 22.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
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
                if (!isBot) {
                    ActionRow(Icons.Filled.Forward, "Переслать") {
                        forwarding = message
                        selected = null
                    }
                }
                if (!isBot && message.kind == MessageKind.STICKER && !message.sticker.isNullOrBlank()) {
                    val sticker = message.sticker.orEmpty()
                    val favorite = sticker in stickerFavorites
                    ActionRow(
                        if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        if (favorite) "Убрать из избранного" else "В избранное",
                    ) {
                        onToggleStickerFavorite(sticker)
                        selected = null
                    }
                }
                if (!isBot && message.isMine && message.kind == MessageKind.TEXT) {
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
        val stickers = if (stickerFavoritesTab) {
            stickerFavorites
        } else {
            installed.flatMap { it.stickers }.distinct()
        }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Стикеры",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            showStickers = false
                            onManageStickers()
                        },
                    ) { Text("Управление") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = !stickerFavoritesTab,
                        onClick = { stickerFavoritesTab = false },
                        label = { Text("Наборы") },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = stickerFavoritesTab,
                        onClick = { stickerFavoritesTab = true },
                        label = { Text("Избранное (${stickerFavorites.size})") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (stickers.isEmpty()) {
                    Text(
                        if (stickerFavoritesTab) {
                            "Удерживайте стикер в чате или в наборе, чтобы добавить его в избранное."
                        } else {
                            "Установите набор стикеров в разделе «Управление»."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.heightIn(max = 420.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(stickers) { sticker ->
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = {
                                            onSend(
                                                "",
                                                MessageKind.STICKER,
                                                null,
                                                sticker,
                                                replying?.text,
                                                replying?.id,
                                                null,
                                            )
                                            showStickers = false
                                            replying = null
                                        },
                                        onLongClick = { stickerMenu = sticker },
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

    stickerMenu?.let { sticker ->
        val favorite = sticker in stickerFavorites
        AlertDialog(
            onDismissRequest = { stickerMenu = null },
            title = { Text(sticker, fontSize = 40.sp) },
            text = { Text(if (favorite) "Стикер уже в избранном." else "Добавить стикер в избранное?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onToggleStickerFavorite(sticker)
                        stickerMenu = null
                    },
                ) { Text(if (favorite) "Убрать" else "Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { stickerMenu = null }) { Text("Закрыть") }
            },
        )
    }

    // -------------------------------------------------------- attach sheet

    if (showAttach) {
        ModalBottomSheet(
            onDismissRequest = { showAttach = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                Text("Прикрепить", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    AttachOption(Icons.Filled.PhotoLibrary, "Галерея") {
                        showAttach = false
                        picker.launch("image/*")
                    }
                    AttachOption(Icons.Filled.InsertDriveFile, "Файл") {
                        showAttach = false
                        picker.launch("*/*")
                    }
                    AttachOption(Icons.Filled.Poll, "Опрос") {
                        showAttach = false
                        showPoll = true
                    }
                    AttachOption(Icons.Filled.Place, "Место") {
                        showAttach = false
                        scope.launch { snackbar.showSnackbar("Геолокация появится в следующем обновлении") }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showPoll) {
        PollCreateDialog(
            onDismiss = { showPoll = false },
            onPublish = { question, options, multiple, anonymous ->
                onSend(
                    "",
                    MessageKind.POLL,
                    null,
                    null,
                    replying?.text,
                    replying?.id,
                    Poll(
                        question = question,
                        options = options.mapIndexed { index, text ->
                            PollOption("opt_$index", text)
                        },
                        multiple = multiple,
                        anonymous = anonymous,
                    ),
                )
                showPoll = false
                replying = null
            },
        )
    }

    // ----------------------------------------------------------- bot dialogs

    if (showBotWarning) {
        AlertDialog(
            onDismissRequest = {
                showBotWarning = false
                onBotWarningShown()
            },
            icon = { Icon(Icons.Filled.SmartToy, contentDescription = null, tint = TagaAmber) },
            title = { Text("Бот · без шифрования") },
            text = {
                Text(
                    "Это бот, а не человек. Сообщения этого чата не защищены " +
                        "сквозным шифрованием: их видит сервер и владелец бота. " +
                        "Не отправляйте пароли, коды и личные секреты.",
                    fontSize = 16.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBotWarning = false
                        onBotWarningShown()
                    },
                ) { Text("Понятно") }
            },
        )
    }

    if (showBotCommands) {
        val isBotFather = bot?.username.equals("botfather", true)
        val commands = bot?.commands.orEmpty().ifEmpty {
            if (isBotFather) BOTFATHER_COMMANDS else emptyList()
        }
        val unsupported = if (isBotFather) {
            listOf(
                "/setuserpic", "/deleteuserpic", "/setdomain",
                "/newapp", "/myapps", "/editapp", "/deleteapp",
                "/setmenubutton", "/setinlinegeo", "/authorize",
            )
        } else {
            emptyList()
        }
        ModalBottomSheet(
            onDismissRequest = { showBotCommands = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    "Команды @${bot?.username.orEmpty()}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                if (commands.isEmpty()) {
                    Text(
                        "Бот пока не опубликовал список команд",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                commands.forEach { command ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                input = "/${command.command} "
                                showBotCommands = false
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "/${command.command}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            command.description,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (unsupported.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Пока недоступно",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    unsupported.forEach { command ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                command,
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "скоро",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
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
                            replying?.id,
                            null,
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
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PollCreateDialog(
    onDismiss: () -> Unit,
    onPublish: (String, List<String>, Boolean, Boolean) -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var multiple by remember { mutableStateOf(false) }
    var anonymous by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый опрос") },
        text = {
            Column {
                TextField(
                    value = question,
                    onValueChange = { question = it.take(200) },
                    placeholder = { Text("Вопрос") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { index, value ->
                    TextField(
                        value = value,
                        onValueChange = { next ->
                            options = options.toMutableList().also { it[index] = next.take(80) }
                        },
                        placeholder = { Text("Вариант ${index + 1}") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
                if (options.size < 6) {
                    TextButton(
                        onClick = { options = options + "" },
                        modifier = Modifier.align(Alignment.Start),
                    ) {
                        Text("+ Добавить вариант")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Несколько ответов",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = multiple,
                        onCheckedChange = { multiple = it },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Анонимный опрос",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = anonymous,
                        onCheckedChange = { anonymous = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = question.isNotBlank() &&
                    options.count { it.isNotBlank() } >= 2,
                onClick = {
                    onPublish(
                        question.trim(),
                        options.map { it.trim() }.filter { it.isNotBlank() },
                        multiple,
                        anonymous,
                    )
                },
            ) { Text("Опубликовать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
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
    palette: ChatPalette,
    bubbleRadius: Int,
    textSize: androidx.compose.ui.unit.TextUnit,
    onLongPress: () -> Unit,
    onAttachmentClick: (Message) -> Unit,
    onVote: (String) -> Unit,
    onReact: (String) -> Unit,
    onBotCallback: (String) -> Unit = {},
    onBotOpenUrl: (String) -> Unit = {},
) {
    val bubbleColor = if (message.isMine) palette.outgoing else palette.incoming
    val contentColor = if (message.isMine) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (message.isMine) {
        Color.White.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val radius = bubbleRadius.dp

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
                        topStart = radius,
                        topEnd = radius,
                        bottomStart = if (message.isMine) radius else radius / 4,
                        bottomEnd = if (message.isMine) radius / 4 else radius,
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
                MessageKind.IMAGE, MessageKind.VIDEO -> MediaAttachment(message, onAttachmentClick)
                MessageKind.AUDIO -> AudioAttachment(message, contentColor, secondary, onAttachmentClick)
                MessageKind.FILE -> FileAttachment(message, contentColor, secondary, onAttachmentClick)
                MessageKind.CALL -> CallAttachment(message, contentColor, secondary)
                MessageKind.POLL -> PollAttachment(
                    poll = message.poll,
                    contentColor = contentColor,
                    secondary = secondary,
                    accent = palette.accent,
                    textSize = textSize,
                    onVote = onVote,
                )
                else -> if (message.text.isNotBlank()) {
                    if (message.entities.isNotEmpty()) {
                        BotEntityText(
                            text = message.text,
                            entities = message.entities,
                            color = contentColor,
                            fontSize = textSize,
                            onOpenUrl = onBotOpenUrl,
                        )
                    } else {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = textSize),
                            color = contentColor,
                        )
                    }
                }
            }

            if (
                message.text.isNotBlank() &&
                message.kind in setOf(
                    MessageKind.IMAGE,
                    MessageKind.VIDEO,
                    MessageKind.AUDIO,
                    MessageKind.FILE,
                )
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    modifier = Modifier.padding(top = 4.dp),
                )
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
                        contentDescription = when (message.status) {
                            MessageStatus.READ -> "Прочитано"
                            MessageStatus.DELIVERED -> "Доставлено"
                            MessageStatus.SENT -> "Отправлено"
                            MessageStatus.SENDING -> "Отправляется"
                            MessageStatus.FAILED -> "Не отправлено"
                        },
                        tint = if (message.status == MessageStatus.READ) Color(0xFFB9F6CA) else secondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            if (message.reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    message.reactions.forEach { (emoji, count) ->
                        Surface(
                            color = contentColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.clickable { onReact(emoji) },
                        ) {
                            Text(
                                "$emoji $count",
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            if (message.keyboard.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    message.keyboard.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            row.forEach { button ->
                                BotKeyboardButton(
                                    button = button,
                                    contentColor = contentColor,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onBotCallback(button.callbackData) },
                                    onOpenUrl = onBotOpenUrl,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaAttachment(message: Message, onAttachmentClick: (Message) -> Unit) {
    val attachment = message.attachment
    val local = attachment?.localUri
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
            )
            .clickable { onAttachmentClick(message) },
        contentAlignment = Alignment.Center,
    ) {
        if (message.kind == MessageKind.IMAGE && local != null) {
            AsyncImage(
                model = local,
                contentDescription = attachment?.name ?: "Фото",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (message.kind == MessageKind.VIDEO) Icons.Filled.PlayArrow else Icons.Filled.Image,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    attachment?.name ?: if (message.kind == MessageKind.VIDEO) "Видео" else "Фото",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                val hint = when {
                    attachment?.mediaId != null && local == null -> "Нажмите, чтобы загрузить"
                    message.kind == MessageKind.VIDEO && local != null -> "Нажмите, чтобы открыть видео"
                    else -> null
                }
                if (hint != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = TagaAmber,
                    )
                }
                if (attachment?.scanVerdict == ScanVerdict.WARNING) {
                    Text(
                        "⚠ проверено с предупреждением",
                        style = MaterialTheme.typography.bodySmall,
                        color = TagaAmber,
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioAttachment(
    message: Message,
    contentColor: Color,
    secondary: Color,
    onAttachmentClick: (Message) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .width(230.dp)
            .clickable { onAttachmentClick(message) },
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
private fun FileAttachment(
    message: Message,
    contentColor: Color,
    secondary: Color,
    onAttachmentClick: (Message) -> Unit,
) {
    val attachment = message.attachment ?: return
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .width(250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(contentColor.copy(alpha = 0.12f))
            .clickable { onAttachmentClick(message) }
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
                when {
                    attachment.mediaId != null && attachment.localUri == null ->
                        "${formatBytes(attachment.sizeBytes)} · нажмите, чтобы скачать"
                    attachment.scanVerdict == ScanVerdict.SAFE ->
                        "${formatBytes(attachment.sizeBytes)} · проверено"
                    else -> "${formatBytes(attachment.sizeBytes)} · внимание"
                },
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
private fun PollAttachment(
    poll: Poll?,
    contentColor: Color,
    secondary: Color,
    accent: Color,
    textSize: androidx.compose.ui.unit.TextUnit,
    onVote: (String) -> Unit,
) {
    if (poll == null) return
    val total = poll.totalVotes
    Column(
        modifier = Modifier
            .width(260.dp)
            .padding(top = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Poll,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Опрос · ${if (poll.anonymous) "анонимный" else "открытый"}",
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            poll.question,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = textSize),
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
        Spacer(Modifier.height(6.dp))
        poll.options.forEach { option ->
            val mine = option.id in poll.myVotes
            val fraction = if (total > 0) option.votes.toFloat() / total else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(contentColor.copy(alpha = 0.10f))
                    .clickable { onVote(option.id) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(36.dp)
                        .background(accent.copy(alpha = 0.35f)),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .height(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (mine) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Ваш голос",
                            tint = contentColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "${if (total > 0) option.votes * 100 / total else 0}% · ${option.votes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${total} голосов" + if (poll.multiple) " · можно выбрать несколько" else "",
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
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
    MessageKind.POLL -> "Опрос ${message.poll?.question.orEmpty()}"
}

/**
 * Текст с форматированием Bot API. Кликабельные text_link/text_mention
 * открываются через [onOpenUrl]; некорректные диапазоны отбрасываются.
 */
@Composable
private fun BotEntityText(
    text: String,
    entities: List<BotEntity>,
    color: Color,
    fontSize: TextUnit,
    onOpenUrl: (String) -> Unit,
) {
    val linkColor = Color(0xFF8FB8FF)
    val annotated = remember(text, entities, color) {
        buildAnnotatedString {
            append(text)
            validBotEntities(entities, text.length).forEach { entity ->
                val style = when (entity.type) {
                    "bold" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "italic" -> SpanStyle(fontStyle = FontStyle.Italic)
                    "underline" -> SpanStyle(textDecoration = TextDecoration.Underline)
                    "strikethrough" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    "code", "pre" -> SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = color.copy(alpha = 0.12f),
                    )
                    "text_link", "text_mention" -> SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    )
                    "bot_command" -> SpanStyle(color = TagaPurple, fontWeight = FontWeight.SemiBold)
                    else -> null
                }
                style?.let { addStyle(it, entity.offset, entity.end) }
                if (entity.type == "text_link" && entity.url.isNotBlank()) {
                    addStringAnnotation("URL", entity.url, entity.offset, entity.end)
                }
            }
        }
    }
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = fontSize,
            color = color,
        ),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset)
                .firstOrNull()
                ?.item
                ?.let(onOpenUrl)
        },
    )
}

@Composable
private fun BotKeyboardButton(
    button: InlineKeyboardButton,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Surface(
        color = contentColor.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.clickable {
            when {
                button.isUrl -> onOpenUrl(button.url)
                button.isWebApp -> button.webApp?.url?.takeIf { it.isNotBlank() }?.let(onOpenUrl)
                button.isCallback -> onClick()
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = button.text,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 16.sp,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
