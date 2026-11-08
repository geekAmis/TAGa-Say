package com.tagasay.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.UiState
import com.tagasay.app.data.model.UpdateStatus
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.ServerBanner
import com.tagasay.app.ui.screens.CallScreen
import com.tagasay.app.ui.screens.CallsScreen
import com.tagasay.app.ui.screens.ChannelsScreen
import com.tagasay.app.ui.screens.ChatFoldersScreen
import com.tagasay.app.ui.screens.ChatScreen
import com.tagasay.app.ui.screens.ChatSettingsScreen
import com.tagasay.app.ui.screens.ChatsScreen
import com.tagasay.app.ui.screens.ContactsScreen
import com.tagasay.app.ui.screens.MandatoryUpdateScreen
import com.tagasay.app.ui.screens.MediaGalleryScreen
import com.tagasay.app.ui.screens.MyStoriesScreen
import com.tagasay.app.ui.screens.OnboardingScreen
import com.tagasay.app.ui.screens.ProfileScreen
import com.tagasay.app.ui.screens.SearchScreen
import com.tagasay.app.ui.screens.SettingsScreen
import com.tagasay.app.ui.screens.StickerPacksScreen
import com.tagasay.app.ui.screens.UpdateBanner
import com.tagasay.app.ui.theme.TagaPurple

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    Tab("chats", "Чаты", Icons.Filled.ChatBubble),
    Tab("contacts", "Контакты", Icons.Filled.Contacts),
    Tab("settings", "Настройки", Icons.Filled.Settings),
    Tab("profile", "Профиль", Icons.Filled.Person),
)

@Composable
fun TAGaSayRoot(
    viewModel: MainViewModel,
    state: UiState,
    deepLink: String?,
    onDeepLinkConsumed: () -> Unit,
    openChatId: String? = null,
    onOpenChatConsumed: () -> Unit = {},
) {
    val update = state.update
    if (update.mandatory) {
        MandatoryUpdateScreen(
            update = update,
            onInstall = viewModel::installUpdate,
        )
        return
    }
    if (state.loading) {
        // Не показываем онбординг, пока DataStore не отдал сохранённые настройки.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = TagaPurple)
        }
    } else if (!state.settings.onboarded) {
        OnboardingScreen(onComplete = viewModel::completeOnboarding)
    } else {
        MainNavHost(
            viewModel = viewModel,
            state = state,
            deepLink = deepLink,
            onDeepLinkConsumed = onDeepLinkConsumed,
            openChatId = openChatId,
            onOpenChatConsumed = onOpenChatConsumed,
            update = update,
            onInstallUpdate = viewModel::installUpdate,
            onDismissUpdate = viewModel::dismissUpdate,
        )
    }
}

@Composable
private fun MainNavHost(
    viewModel: MainViewModel,
    state: UiState,
    deepLink: String?,
    onDeepLinkConsumed: () -> Unit,
    openChatId: String?,
    onOpenChatConsumed: () -> Unit,
    update: UpdateStatus,
    onInstallUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevelRoutes = remember { tabs.map { it.route }.toSet() }
    val unread = state.vault.chats.sumOf { it.unread }
    val settings = state.settings
    val vault = state.vault

    LaunchedEffect(deepLink) {
        if (!deepLink.isNullOrBlank()) {
            navController.navigate("profile/$deepLink") { launchSingleTop = true }
            onDeepLinkConsumed()
        }
    }

    LaunchedEffect(openChatId) {
        if (!openChatId.isNullOrBlank()) {
            navController.navigate("chat/$openChatId") { launchSingleTop = true }
            onOpenChatConsumed()
        }
    }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute in topLevelRoutes) {
                NavigationBar(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = {
                                if (tab.route == "profile") {
                                    Avatar(
                                        name = settings.displayName.ifBlank { "Вы" },
                                        color = vault.profile.avatarColor,
                                        size = 26.dp,
                                    )
                                } else if (tab.route == "chats" && unread > 0) {
                                    BadgedBox(badge = { Badge { Text(unread.toString()) } }) {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                            label = {
                                Text(
                                    tab.label,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    fontSize = 10.sp,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TagaPurple,
                                selectedTextColor = TagaPurple,
                                indicatorColor = TagaPurple.copy(alpha = 0.14f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ServerBanner(
                status = state.server,
                visible = !state.server.online,
                modifier = if (state.server.online) Modifier else Modifier.statusBarsPadding(),
            )
            UpdateBanner(
                update = update,
                onInstall = onInstallUpdate,
                onDismiss = onDismissUpdate,
            )
            NavHost(
                navController = navController,
                startDestination = "chats",
                modifier = Modifier.fillMaxSize(),
            ) {
                composable("chats") {
                    ChatsScreen(
                        chats = vault.chats,
                        folders = vault.chatFolders,
                        stories = vault.stories,
                        profile = vault.profile,
                        onOpenChat = { navController.navigate("chat/$it") },
                        onNewChat = { navController.navigateToTab("contacts") },
                        onOpenSearch = { navController.navigate("search") },
                        onOpenFolders = { navController.navigate("folders") },
                        onOpenCalls = { navController.navigate("calls") },
                        onOpenChannels = { navController.navigate("channels") },
                        onOpenStories = { navController.navigate("stories") },
                        onMarkStoryViewed = viewModel::markStoryViewed,
                    )
                }

                composable("contacts") {
                    ContactsScreen(
                        contacts = vault.contacts,
                        onOpenContact = { contact ->
                            val chatId = viewModel.ensureDirectChat(
                                contact.username,
                                contact.displayName,
                                contact.avatarColor,
                            )
                            navController.navigate("chat/$chatId")
                        },
                        onAddByUsername = { username ->
                            val contact = viewModel.contact(username)
                            val chatId = viewModel.ensureDirectChat(
                                username,
                                contact?.displayName ?: "@$username",
                                contact?.avatarColor ?: 0xFF7B61FF,
                            )
                            navController.navigate("chat/$chatId")
                        },
                        onOpenCalls = { navController.navigate("calls") },
                    )
                }

                composable("settings") {
                    SettingsScreen(
                        settings = settings,
                        profile = vault.profile,
                        server = state.server,
                        serverInfo = state.serverInfo,
                        fingerprint = state.fingerprint,
                        onSetup2fa = viewModel::setup2fa,
                        onEnable2fa = viewModel::enable2fa,
                        onTwoFactorStatus = viewModel::twoFactorStatus,
                        onOpenProfile = { navController.navigateToTab("profile") },
                        onUpdateProfile = viewModel::updateProfile,
                        onDarkTheme = viewModel::setDarkTheme,
                        onReadReceipts = viewModel::setReadReceipts,
                        onNotifications = viewModel::setNotifications,
                        onShowTyping = viewModel::setShowTyping,
                        onScreenSecurity = viewModel::setScreenSecurity,
                        onCheckServer = viewModel::checkServerNow,
                        onResetDemo = viewModel::resetDemoData,
                        onWipe = viewModel::wipeAll,
                        onOpenChatSettings = { navController.navigate("chatSettings") },
                        onOpenFolders = { navController.navigate("folders") },
                        onOpenStickers = { navController.navigate("stickers") },
                    )
                }

                composable("profile") {
                    ProfileScreen(
                        displayName = settings.displayName,
                        username = settings.username,
                        phone = settings.phone,
                        bio = vault.profile.bio,
                        avatarColor = vault.profile.avatarColor,
                        verified = false,
                        online = true,
                        fingerprint = state.fingerprint,
                        phoneVisible = settings.phone.isNotBlank(),
                        isSelf = true,
                        isRegistered = true,
                        onBack = { navController.navigateToTab("chats") },
                        onMessage = { navController.navigateToTab("settings") },
                        onCall = {},
                        storiesCount = vault.stories.count { it.mine && !it.archived },
                        onOpenStories = { navController.navigate("stories") },
                        showBack = false,
                        onEdit = { navController.navigateToTab("settings") },
                        onOpenSettings = { navController.navigateToTab("settings") },
                    )
                }

                composable("search") {
                    SearchScreen(
                        chats = vault.chats,
                        contacts = vault.contacts,
                        channels = vault.channels,
                        messages = vault.messages,
                        onBack = { navController.backOrHome() },
                        onOpenChat = { navController.navigate("chat/$it") },
                        onOpenContact = { contact ->
                            val chatId = viewModel.ensureDirectChat(
                                contact.username,
                                contact.displayName,
                                contact.avatarColor,
                            )
                            navController.navigate("chat/$chatId")
                        },
                        onOpenChannel = { channel -> navController.navigate("chat/${channel.id}") },
                    )
                }

                composable("folders") {
                    ChatFoldersScreen(
                        folders = vault.chatFolders,
                        onBack = { navController.backOrHome() },
                        onSave = viewModel::saveChatFolder,
                        onDelete = viewModel::deleteChatFolder,
                    )
                }

                composable("calls") {
                    CallsScreen(
                        calls = vault.callLog,
                        onCall = { chatId -> navController.navigate("call/$chatId") },
                        onClear = viewModel::clearCallLog,
                        onBack = { navController.backOrHome() },
                    )
                }

                composable("channels") {
                    ChannelsScreen(
                        channels = vault.channels,
                        onOpenChannel = { channel -> navController.navigate("chat/${channel.id}") },
                        onCreateChannel = { title, username, description ->
                            val channel = viewModel.createChannel(title, username, description)
                            navController.navigate("chat/${channel.id}")
                        },
                    )
                }

                composable("chat/{chatId}") { entry ->
                    val chatId = entry.arguments?.getString("chatId").orEmpty()
                    val chat = vault.chats.firstOrNull { it.id == chatId }
                    if (chat == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        ChatScreen(
                            chat = chat,
                            messages = vault.messages[chatId].orEmpty(),
                            profile = vault.profile,
                            stickerPacks = vault.stickerPacks,
                            stickerFavorites = vault.stickerFavorites,
                            settings = settings,
                            allChats = vault.chats,
                            onBack = { navController.backOrHome() },
                            onOpenProfile = {
                                val target = if (chat.type == com.tagasay.app.data.model.ChatType.PRIVATE) {
                                    "profile/${chat.username}"
                                } else {
                                    "profile/me"
                                }
                                navController.navigate(target)
                            },
                            onOpenSettings = { navController.navigate("chatSettings") },
                            onOpenMedia = { navController.navigate("media/$chatId") },
                            onCall = { navController.navigate("call/$chatId") },
                            onSend = { text, kind, attachment, sticker, reply, replyId, poll ->
                                viewModel.sendMessage(
                                    chatId, text, kind, attachment, sticker, reply, replyId, poll,
                                )
                            },
                            onEdit = { messageId, text ->
                                viewModel.editMessage(chatId, messageId, text)
                            },
                            onDelete = { messageId ->
                                viewModel.deleteMessage(chatId, messageId)
                            },
                            onForward = { messageId, targetChatId ->
                                viewModel.forwardMessage(chatId, messageId, targetChatId)
                            },
                            onMarkRead = { viewModel.markRead(chatId) },
                            onInspect = { uri -> viewModel.inspectFile(uri) },
                            onDownloadAttachment = { messageId ->
                                viewModel.downloadAttachment(chatId, messageId)
                            },
                            onVotePoll = { messageId, optionId ->
                                viewModel.votePoll(chatId, messageId, optionId)
                            },
                            onToggleStickerFavorite = viewModel::toggleStickerFavorite,
                            onManageStickers = { navController.navigate("stickers") },
                            onReact = { messageId, emoji ->
                                viewModel.toggleReaction(chatId, messageId, emoji)
                            },
                        )
                    }
                }

                composable("chatSettings") {
                    ChatSettingsScreen(
                        messageTextSize = settings.messageTextSize,
                        bubbleRadius = settings.bubbleRadius,
                        chatTheme = settings.chatTheme,
                        chatWallpaper = settings.chatWallpaper,
                        darkTheme = settings.darkTheme,
                        onBack = { navController.backOrHome() },
                        onTextSize = viewModel::setMessageTextSize,
                        onBubbleRadius = viewModel::setBubbleRadius,
                        onTheme = viewModel::setChatTheme,
                        onWallpaper = viewModel::setChatWallpaper,
                        onDarkTheme = viewModel::setDarkTheme,
                    )
                }

                composable("media/{chatId}") { entry ->
                    val chatId = entry.arguments?.getString("chatId").orEmpty()
                    val chat = vault.chats.firstOrNull { it.id == chatId }
                    if (chat == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        MediaGalleryScreen(
                            chatTitle = chat.title,
                            messages = vault.messages[chatId].orEmpty(),
                            onBack = { navController.backOrHome() },
                            onDownload = { messageId ->
                                viewModel.downloadAttachment(chatId, messageId)
                            },
                        )
                    }
                }

                composable("stickers") {
                    StickerPacksScreen(
                        packs = vault.stickerPacks,
                        favorites = vault.stickerFavorites,
                        onBack = { navController.backOrHome() },
                        onInstall = viewModel::installStickerPack,
                        onAddPack = viewModel::addStickerPack,
                        onToggleFavorite = viewModel::toggleStickerFavorite,
                    )
                }

                composable("stories") {
                    MyStoriesScreen(
                        stories = vault.stories,
                        onBack = { navController.backOrHome() },
                        onAdd = { uri, caption -> viewModel.addStory(uri, caption) },
                        onDelete = viewModel::deleteStory,
                        onArchive = viewModel::archiveStory,
                        onViewed = viewModel::markStoryViewed,
                    )
                }

                composable("profile/{username}") { entry ->
                    val username = entry.arguments?.getString("username").orEmpty()
                    val isSelf = username.equals("me", true) ||
                        username.equals(settings.username, true)
                    val contact = vault.contacts.firstOrNull {
                        it.username.equals(username, true)
                    }
                    val chat = vault.chats.firstOrNull {
                        it.username.equals(username, true)
                    }
                    val displayName = when {
                        isSelf -> settings.displayName
                        contact != null -> contact.displayName
                        chat != null -> chat.title
                        else -> "@$username"
                    }
                    val color = when {
                        isSelf -> vault.profile.avatarColor
                        contact != null -> contact.avatarColor
                        chat != null -> chat.avatarColor
                        else -> 0xFF7B61FF
                    }
                    ProfileScreen(
                        displayName = displayName,
                        username = if (isSelf) settings.username else username,
                        phone = "",
                        bio = if (isSelf) vault.profile.bio else contact?.bio.orEmpty(),
                        avatarColor = color,
                        verified = contact?.verified ?: (chat?.verified ?: false),
                        online = contact?.online ?: (chat?.online ?: false),
                        fingerprint = if (isSelf) state.fingerprint else "",
                        phoneVisible = false,
                        isSelf = isSelf,
                        isRegistered = isSelf || contact != null || chat != null,
                        onBack = { navController.backOrHome() },
                        onMessage = {
                            if (isSelf) {
                                navController.navigateToTab("settings")
                            } else {
                                val chatId = viewModel.ensureDirectChat(username, displayName, color)
                                navController.navigate("chat/$chatId")
                            }
                        },
                        onCall = {
                            if (!isSelf) {
                                val chatId = viewModel.ensureDirectChat(username, displayName, color)
                                navController.navigate("call/$chatId")
                            }
                        },
                    )
                }

                composable("call/{chatId}") { entry ->
                    val chatId = entry.arguments?.getString("chatId").orEmpty()
                    val chat = vault.chats.firstOrNull { it.id == chatId }
                    if (chat == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        CallScreen(
                            displayName = chat.title,
                            avatarColor = chat.avatarColor,
                            onEnd = { seconds, video ->
                                viewModel.recordCall(
                                    chatId = chatId,
                                    seconds = seconds,
                                    video = video,
                                    outgoing = true,
                                    missed = seconds == 0,
                                )
                                viewModel.sendMessage(
                                    chatId = chatId,
                                    text = "",
                                    kind = MessageKind.CALL,
                                )
                                navController.popBackStack()
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun NavHostController.backOrHome() {
    if (!popBackStack()) {
        navigate("chats") {
            popUpTo(graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
