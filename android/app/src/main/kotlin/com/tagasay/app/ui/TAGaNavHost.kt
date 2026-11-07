package com.tagasay.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Contacts
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.UiState
import com.tagasay.app.ui.components.ServerBanner
import com.tagasay.app.ui.screens.CallScreen
import com.tagasay.app.ui.screens.ChannelsScreen
import com.tagasay.app.ui.screens.ChatScreen
import com.tagasay.app.ui.screens.ChatsScreen
import com.tagasay.app.ui.screens.ContactsScreen
import com.tagasay.app.ui.screens.OnboardingScreen
import com.tagasay.app.ui.screens.ProfileScreen
import com.tagasay.app.ui.screens.SettingsScreen
import com.tagasay.app.ui.theme.TagaPurple

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    Tab("chats", "Чаты", Icons.Filled.ChatBubble),
    Tab("contacts", "Контакты", Icons.Filled.Contacts),
    Tab("channels", "Каналы", Icons.Filled.Campaign),
    Tab("settings", "Настройки", Icons.Filled.Settings),
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
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevelRoutes = remember { tabs.map { it.route }.toSet() }
    val unread = state.vault.chats.sumOf { it.unread }

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
                                if (tab.route == "chats" && unread > 0) {
                                    BadgedBox(badge = { Badge { Text(unread.toString()) } }) {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) },
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
            NavHost(
                navController = navController,
                startDestination = "chats",
                modifier = Modifier.fillMaxSize(),
            ) {
                composable("chats") {
                    ChatsScreen(
                        chats = state.vault.chats,
                        onOpenChat = { navController.navigate("chat/$it") },
                        onNewChat = { navController.navigateToTab("contacts") },
                        onOpenProfile = { navController.navigate("profile/me") },
                    )
                }

                composable("contacts") {
                    ContactsScreen(
                        contacts = state.vault.contacts,
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
                    )
                }

                composable("channels") {
                    ChannelsScreen(
                        channels = state.vault.channels,
                        onOpenChannel = { channel -> navController.navigate("chat/${channel.id}") },
                        onCreateChannel = { title, username, description ->
                            val channel = viewModel.createChannel(title, username, description)
                            navController.navigate("chat/${channel.id}")
                        },
                    )
                }

                composable("settings") {
                    SettingsScreen(
                        settings = state.settings,
                        profile = state.vault.profile,
                        server = state.server,
                        fingerprint = state.fingerprint,
                        onSetup2fa = viewModel::setup2fa,
                        onEnable2fa = viewModel::enable2fa,
                        onTwoFactorStatus = viewModel::twoFactorStatus,
                        onOpenProfile = { navController.navigate("profile/me") },
                        onUpdateProfile = viewModel::updateProfile,
                        onDarkTheme = viewModel::setDarkTheme,
                        onReadReceipts = viewModel::setReadReceipts,
                        onNotifications = viewModel::setNotifications,
                        onShowTyping = viewModel::setShowTyping,
                        onScreenSecurity = viewModel::setScreenSecurity,
                        onServerUrl = viewModel::setServerUrl,
                        onCheckServer = viewModel::checkServerNow,
                        onResetDemo = viewModel::resetDemoData,
                        onWipe = viewModel::wipeAll,
                    )
                }

                composable("chat/{chatId}") { entry ->
                    val chatId = entry.arguments?.getString("chatId").orEmpty()
                    val chat = state.vault.chats.firstOrNull { it.id == chatId }
                    if (chat == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        ChatScreen(
                            chat = chat,
                            messages = state.vault.messages[chatId].orEmpty(),
                            profile = state.vault.profile,
                            stickerPacks = state.vault.stickerPacks,
                            allChats = state.vault.chats,
                            onBack = { navController.popBackStack() },
                            onOpenProfile = {
                                val target = if (chat.type == com.tagasay.app.data.model.ChatType.PRIVATE) {
                                    "profile/${chat.username}"
                                } else {
                                    "profile/me"
                                }
                                navController.navigate(target)
                            },
                            onCall = { navController.navigate("call/$chatId") },
                            onSend = { text, kind, attachment, sticker, reply, replyId ->
                                viewModel.sendMessage(chatId, text, kind, attachment, sticker, reply, replyId)
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
                        )
                    }
                }

                composable("profile/{username}") { entry ->
                    val username = entry.arguments?.getString("username").orEmpty()
                    val isSelf = username.equals("me", true) ||
                        username.equals(state.settings.username, true)
                    val contact = state.vault.contacts.firstOrNull {
                        it.username.equals(username, true)
                    }
                    val chat = state.vault.chats.firstOrNull {
                        it.username.equals(username, true)
                    }
                    val displayName = when {
                        isSelf -> state.settings.displayName
                        contact != null -> contact.displayName
                        chat != null -> chat.title
                        else -> "@$username"
                    }
                    val color = when {
                        isSelf -> state.vault.profile.avatarColor
                        contact != null -> contact.avatarColor
                        chat != null -> chat.avatarColor
                        else -> 0xFF7B61FF
                    }
                    ProfileScreen(
                        displayName = displayName,
                        username = if (isSelf) state.settings.username else username,
                        phone = "",
                        bio = if (isSelf) state.vault.profile.bio else contact?.bio.orEmpty(),
                        avatarColor = color,
                        verified = contact?.verified ?: (chat?.verified ?: false),
                        online = contact?.online ?: (chat?.online ?: false),
                        fingerprint = if (isSelf) state.fingerprint else "",
                        phoneVisible = false,
                        isSelf = isSelf,
                        isRegistered = isSelf || contact != null || chat != null,
                        onBack = { navController.popBackStack() },
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
                    val chat = state.vault.chats.firstOrNull { it.id == chatId }
                    if (chat == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        CallScreen(
                            displayName = chat.title,
                            avatarColor = chat.avatarColor,
                            onEnd = {
                                viewModel.sendMessage(
                                    chatId = chatId,
                                    text = "",
                                    kind = MessageKind.CALL,
                                    attachment = null,
                                    sticker = null,
                                    replyToText = null,
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

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}


