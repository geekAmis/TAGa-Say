package com.tagasay.app.data.local

import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.CallEntry
import com.tagasay.app.data.model.Channel
import com.tagasay.app.data.model.Chat
import com.tagasay.app.data.model.ChatFolder
import com.tagasay.app.data.model.ChatType
import com.tagasay.app.data.model.Contact
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.MessageStatus
import com.tagasay.app.data.model.Poll
import com.tagasay.app.data.model.PollOption
import com.tagasay.app.data.model.ScanVerdict
import com.tagasay.app.data.model.StickerPack
import com.tagasay.app.data.model.Story
import com.tagasay.app.data.model.UserProfile
import com.tagasay.app.data.model.VaultData

object SampleData {

    fun vault(now: Long = System.currentTimeMillis()): VaultData {
        val minute = 60_000L
        val hour = 60 * minute

        val chats = listOf(
            Chat(
                id = "saved",
                title = "Избранное",
                username = "me",
                type = ChatType.SAVED,
                avatarColor = 0xFF5B8DEF,
                lastMessage = "Заметки, ссылки и файлы",
                lastTimestamp = now - 3 * hour,
            ),
            Chat(
                id = "anna",
                title = "Анна Соколова",
                username = "anna",
                type = ChatType.PRIVATE,
                avatarColor = 0xFFE06C9F,
                online = true,
                pinned = true,
                unread = 2,
                lastMessage = "Созвонимся в 19:00?",
                lastTimestamp = now - 4 * minute,
            ),
            Chat(
                id = "team",
                title = "TAGa-Say Team",
                username = "tagasay_team",
                type = ChatType.GROUP,
                avatarColor = 0xFF7B61FF,
                verified = true,
                unread = 5,
                lastMessage = "Максим: залил новый билд",
                lastTimestamp = now - 27 * minute,
            ),
            Chat(
                id = "news",
                title = "TAGa-Say News",
                username = "tagasay_news",
                type = ChatType.CHANNEL,
                avatarColor = 0xFF4CC38A,
                verified = true,
                lastMessage = "Обновление 0.1.0-alpha: шифрованные вложения",
                lastTimestamp = now - 2 * hour,
            ),
            Chat(
                id = "maxim",
                title = "Максим Орлов",
                username = "maxorlov",
                type = ChatType.PRIVATE,
                avatarColor = 0xFFF2A65A,
                lastMessage = "Голосовое сообщение",
                lastTimestamp = now - 1 * hour,
            ),
            Chat(
                id = "design",
                title = "Дизайн-ревью",
                username = "design_review",
                type = ChatType.GROUP,
                avatarColor = 0xFF9B8AFB,
                muted = true,
                lastMessage = "Лена: поправила отступы в карточках",
                lastTimestamp = now - 5 * hour,
            ),
        )

        val messages = mapOf(
            "saved" to listOf(
                Message(
                    id = "s1",
                    chatId = "saved",
                    senderId = "me",
                    senderName = "Вы",
                    text = "Ключи от сервера — в менеджере паролей. Не пересылать в открытые чаты.",
                    timestamp = now - 6 * hour,
                    isMine = true,
                ),
                Message(
                    id = "s2",
                    chatId = "saved",
                    senderId = "me",
                    senderName = "Вы",
                    text = "Материалы по Double Ratchet: https://signal.org/docs/specifications/doubleratchet/",
                    timestamp = now - 3 * hour,
                    isMine = true,
                ),
            ),
            "anna" to listOf(
                Message(
                    id = "a1",
                    chatId = "anna",
                    senderId = "anna",
                    senderName = "Анна",
                    text = "Привет! Ты уже видел новый дизайн профиля?",
                    timestamp = now - 40 * minute,
                    isMine = false,
                ),
                Message(
                    id = "a2",
                    chatId = "anna",
                    senderId = "me",
                    senderName = "Вы",
                    text = "Да, выглядит отлично. Особенно тёмная тема.",
                    timestamp = now - 36 * minute,
                    isMine = true,
                    status = MessageStatus.READ,
                ),
                Message(
                    id = "a3",
                    chatId = "anna",
                    senderId = "anna",
                    senderName = "Анна",
                    text = "Созвонимся в 19:00?",
                    timestamp = now - 4 * minute,
                    isMine = false,
                ),
            ),
            "team" to listOf(
                Message(
                    id = "t1",
                    chatId = "team",
                    senderId = "maxim",
                    senderName = "Максим",
                    text = "Собрал alpha-сборку. Проверьте отправку вложений на своих устройствах.",
                    timestamp = now - 55 * minute,
                    isMine = false,
                ),
                Message(
                    id = "t2",
                    chatId = "team",
                    senderId = "lena",
                    senderName = "Лена",
                    text = "На TECNO всё летает. Антивирус поймал тестовый EICAR — работает.",
                    timestamp = now - 42 * minute,
                    isMine = false,
                ),
                Message(
                    id = "t3",
                    chatId = "team",
                    senderId = "me",
                    senderName = "Вы",
                    text = "Отлично. Добавляю проверку архивов и предупреждение при смене ключа.",
                    timestamp = now - 30 * minute,
                    isMine = true,
                    status = MessageStatus.DELIVERED,
                ),
                Message(
                    id = "t4",
                    chatId = "team",
                    senderId = "maxim",
                    senderName = "Максим",
                    text = "Залил новый билд.",
                    timestamp = now - 27 * minute,
                    isMine = false,
                    reactions = mapOf("🔥" to 7),
                ),
                Message(
                    id = "t5",
                    chatId = "team",
                    senderId = "lena",
                    senderName = "Лена",
                    text = "",
                    timestamp = now - 20 * minute,
                    isMine = false,
                    kind = MessageKind.POLL,
                    poll = Poll(
                        question = "Что улучшить в следующем релизе?",
                        options = listOf(
                            PollOption("opt1", "Звонки", 6),
                            PollOption("opt2", "Папки чатов", 4),
                            PollOption("opt3", "Истории", 3),
                        ),
                        myVotes = listOf("opt2"),
                    ),
                ),
            ),
            "news" to listOf(
                Message(
                    id = "n1",
                    chatId = "news",
                    senderId = "news",
                    senderName = "TAGa-Say News",
                    text = "Добро пожаловать в официальный канал TAGa-Say.",
                    timestamp = now - 1 * hour,
                    isMine = false,
                ),
                Message(
                    id = "n2",
                    chatId = "news",
                    senderId = "news",
                    senderName = "TAGa-Say News",
                    text = "Обновление 0.1.0-alpha: шифрованные вложения, проверка файлов и новые стикеры.",
                    timestamp = now - 2 * hour,
                    isMine = false,
                ),
            ),
            "maxim" to listOf(
                Message(
                    id = "m1",
                    chatId = "maxim",
                    senderId = "maxim",
                    senderName = "Максим",
                    text = "Скинь, пожалуйста, макет профиля в тёмной теме.",
                    timestamp = now - 2 * hour,
                    isMine = false,
                ),
                Message(
                    id = "m2",
                    chatId = "maxim",
                    senderId = "me",
                    senderName = "Вы",
                    text = "Сейчас отправлю файлом.",
                    timestamp = now - 90 * minute,
                    isMine = true,
                    status = MessageStatus.READ,
                ),
                Message(
                    id = "m3",
                    chatId = "maxim",
                    senderId = "me",
                    senderName = "Вы",
                    text = "",
                    timestamp = now - 85 * minute,
                    isMine = true,
                    kind = MessageKind.FILE,
                    attachment = Attachment(
                        name = "profile-dark.fig",
                        mimeType = "application/octet-stream",
                        sizeBytes = 2_840_112,
                        sha256 = "c0ffee",
                        scanVerdict = ScanVerdict.SAFE,
                    ),
                ),
                Message(
                    id = "m4",
                    chatId = "maxim",
                    senderId = "maxim",
                    senderName = "Максим",
                    text = "",
                    timestamp = now - 1 * hour,
                    isMine = false,
                    kind = MessageKind.AUDIO,
                    attachment = Attachment(
                        name = "voice-2026-11-06.ogg",
                        mimeType = "audio/ogg",
                        sizeBytes = 184_320,
                        sha256 = "a11ce",
                        scanVerdict = ScanVerdict.SAFE,
                    ),
                ),
            ),
            "design" to listOf(
                Message(
                    id = "d1",
                    chatId = "design",
                    senderId = "lena",
                    senderName = "Лена",
                    text = "Поправила отступы в карточках чатов.",
                    timestamp = now - 5 * hour,
                    isMine = false,
                ),
                Message(
                    id = "d2",
                    chatId = "design",
                    senderId = "me",
                    senderName = "Вы",
                    text = "Супер, посмотрю вечером.",
                    timestamp = now - 4 * hour,
                    isMine = true,
                    status = MessageStatus.READ,
                ),
            ),
        )

        val contacts = listOf(
            Contact("anna", "anna", "Анна Соколова", "+7 921 555-14-22", "Дизайнер интерфейсов", 0xFFE06C9F, online = true, verified = true),
            Contact("maxim", "maxorlov", "Максим Орлов", "+7 916 222-88-10", "Android-разработчик", 0xFFF2A65A, online = true),
            Contact("lena", "lena_k", "Елена Ким", "+7 999 101-77-35", "Продуктовый дизайнер", 0xFF4CC38A),
            Contact("igor", "igor_s", "Игорь Смирнов", "+7 903 444-19-08", "Безопасность", 0xFF5B8DEF, verified = true),
        )

        val channels = listOf(
            Channel("news", "tagasay_news", "TAGa-Say News", 128_400, 0xFF4CC38A, verified = true, description = "Официальные новости проекта"),
            Channel("devlog", "tagasay_dev", "TAGa-Say Devlog", 21_300, 0xFF7B61FF, description = "Дневник разработки"),
            Channel("stickers", "tagasay_stickers", "Стикеры TAGa-Say", 54_100, 0xFF9B8AFB, description = "Новые стикер-паки"),
        )

        val packs = listOf(
            StickerPack(
                id = "taga-classic",
                title = "TAGa Classic",
                author = "TAGa-Say",
                stickers = listOf("🛡️", "🔐", "💬", "✨", "👋", "👍", "🔥", "💜", "🤝", "🚀", "🌙", "☕"),
                installed = true,
            ),
            StickerPack(
                id = "cats",
                title = "Коты-шифровальщики",
                author = "@lena_k",
                stickers = listOf("😺", "😸", "😹", "😻", "😼", "🙀", "😿", "😾"),
                installed = true,
            ),
            StickerPack(
                id = "night",
                title = "Night Mode",
                author = "@maxorlov",
                stickers = listOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘"),
            ),
        )

        val folders = listOf(
            ChatFolder(
                id = "personal",
                title = "Личные",
                emoji = "👤",
                types = listOf(ChatType.PRIVATE, ChatType.SAVED),
                includeSaved = true,
            ),
            ChatFolder(
                id = "work",
                title = "Работа",
                emoji = "💼",
                types = listOf(ChatType.GROUP, ChatType.CHANNEL),
            ),
            ChatFolder(
                id = "unread",
                title = "Непрочитанные",
                emoji = "🔔",
                unreadOnly = true,
            ),
        )

        val calls = listOf(
            CallEntry(
                id = "c1",
                chatId = "anna",
                title = "Анна Соколова",
                avatarColor = 0xFFE06C9F,
                outgoing = false,
                timestamp = now - 35 * minute,
                seconds = 312,
            ),
            CallEntry(
                id = "c2",
                chatId = "maxim",
                title = "Максим Орлов",
                avatarColor = 0xFFF2A65A,
                outgoing = true,
                timestamp = now - 2 * hour,
                seconds = 84,
            ),
            CallEntry(
                id = "c3",
                chatId = "lena",
                title = "Елена Ким",
                avatarColor = 0xFF4CC38A,
                outgoing = true,
                missed = true,
                timestamp = now - 5 * hour,
            ),
            CallEntry(
                id = "c4",
                chatId = "anna",
                title = "Анна Соколова",
                avatarColor = 0xFFE06C9F,
                outgoing = true,
                timestamp = now - 26 * hour,
                seconds = 1_204,
                video = true,
            ),
        )

        val stories = listOf(
            Story(
                id = "st1",
                authorUsername = "anna",
                authorName = "Анна",
                avatarColor = 0xFFE06C9F,
                caption = "Новый макет профиля",
                createdAt = now - 2 * hour,
            ),
            Story(
                id = "st2",
                authorUsername = "maxim",
                authorName = "Максим",
                avatarColor = 0xFFF2A65A,
                caption = "Сборка на телефоне",
                createdAt = now - 4 * hour,
            ),
            Story(
                id = "st3",
                authorUsername = "lena",
                authorName = "Лена",
                avatarColor = 0xFF4CC38A,
                caption = "Палитра для тёмной темы",
                createdAt = now - 7 * hour,
            ),
        )

        return VaultData(
            profile = UserProfile(
                username = "lilanga",
                displayName = "Lilanga",
                phone = "+7 900 000-00-00",
                bio = "Строю TAGa-Say",
                avatarColor = 0xFF7B61FF,
            ),
            chats = chats,
            messages = messages,
            contacts = contacts,
            channels = channels,
            stickerPacks = packs,
            chatFolders = folders,
            callLog = calls,
            stories = stories,
        )
    }
}
