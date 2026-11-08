package com.tagasay.server.bot

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Отдельное хранилище ботов (ADR-006).
 *
 * `root == null` — режим только в памяти (dev/test без TAGASAY_DATA).
 * Пользовательский снапшот [com.tagasay.server.data.InMemoryStore] не читается
 * и не переписывается.
 */
class BotStore(private val root: File? = defaultRoot()) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val log = LoggerFactory.getLogger(BotStore::class.java)
    private val lock = Any()

    private val bots = LinkedHashMap<String, Bot>()
    private val botIdsByUsername = HashMap<String, String>()
    private val botIdsByNumeric = HashMap<Long, String>()
    private val tokens = ArrayList<BotToken>()
    private val userStates = HashMap<String, BotUserState>()
    private val sessions = HashMap<String, BotFatherSession>()
    private val updates = HashMap<String, ArrayDeque<BotUpdate>>()
    private val updateCounters = HashMap<String, Long>()
    private val offsets = HashMap<String, Long>()
    private val webhooks = HashMap<String, BotWebhook>()
    private val messages = HashMap<String, LinkedHashMap<String, BotMessage>>()
    private val answers = ArrayList<BotCallbackAnswer>()
    private val auditLog = ArrayList<BotAudit>()

    private var nextNumericId = 1L
    private var nextSeq = 1L
    private var updatesWritten = 0
    private var chatsWritten = 0

    init {
        load()
    }

    // ------------------------------------------------------------------- bots

    fun bot(id: String): Bot? = synchronized(lock) { bots[id] }

    fun botByUsername(username: String): Bot? = synchronized(lock) {
        val clean = username.trimStart('@').lowercase()
        botIdsByUsername[clean]?.let { bots[it] }?.takeIf { it.status == "active" }
    }

    fun botByNumeric(numericId: Long): Bot? = synchronized(lock) {
        botIdsByNumeric[numericId]?.let { bots[it] }
    }

    fun botsOwned(owner: String): List<Bot> = synchronized(lock) {
        val clean = owner.lowercase()
        bots.values
            .filter { it.owner.equals(clean, true) && it.status == "active" }
            .sortedBy { it.createdAt }
    }

    fun countOwned(owner: String): Int = synchronized(lock) {
        val clean = owner.lowercase()
        bots.values.count { it.owner.equals(clean, true) && it.status == "active" }
    }

    fun search(query: String, limit: Int): List<Bot> = synchronized(lock) {
        val clean = query.trim().removePrefix("@").lowercase()
        bots.values
            .filter { it.status == "active" }
            .filter {
                clean.isEmpty() ||
                    it.username.contains(clean, true) ||
                    it.displayName.contains(clean, true)
            }
            .sortedWith(compareByDescending<Bot> { it.builtin }.thenBy { it.username })
            .take(limit.coerceIn(1, 100))
    }

    fun createBot(
        owner: String,
        username: String,
        displayName: String,
        builtin: Boolean = false,
        numericId: Long? = null,
        verified: Boolean = false,
    ): Bot? = synchronized(lock) {
        val clean = username.trimStart('@').lowercase()
        if (botIdsByUsername.containsKey(clean)) return@synchronized null
        val numeric = numericId ?: nextNumericId++
        if (botIdsByNumeric.containsKey(numeric)) return@synchronized null
        if (numericId != null) nextNumericId = maxOf(nextNumericId, numeric + 1)
        val bot = Bot(
            id = "bot_$numeric",
            numericId = numeric,
            username = clean,
            owner = owner.lowercase(),
            displayName = displayName.take(64).ifBlank { clean },
            verified = verified,
            builtin = builtin,
            createdAt = System.currentTimeMillis(),
            scopes = if (builtin) BotScopes.ALL else BotScopes.DEFAULT,
        )
        bots[bot.id] = bot
        botIdsByUsername[clean] = bot.id
        botIdsByNumeric[numeric] = bot.id
        saveSnapshot()
        bot
    }

    fun updateBot(bot: Bot) = synchronized(lock) {
        val previous = bots[bot.id] ?: return@synchronized
        if (!previous.username.equals(bot.username, true)) {
            botIdsByUsername.remove(previous.username)
            botIdsByUsername[bot.username] = bot.id
        }
        bots[bot.id] = bot
        saveSnapshot()
    }

    fun deleteBot(id: String): Bot? = synchronized(lock) {
        val bot = bots[id] ?: return@synchronized null
        if (bot.builtin) return@synchronized null
        val next = bot.copy(status = "deleted", deletedAt = System.currentTimeMillis())
        bots[id] = next
        if (botIdsByUsername[bot.username] == id) botIdsByUsername.remove(bot.username)
        auditLog.add(BotAudit(botId = id, action = "delete", at = System.currentTimeMillis()))
        saveSnapshot()
        next
    }

    // ----------------------------------------------------------------- tokens

    fun addToken(token: BotToken) = synchronized(lock) {
        tokens.removeAll { it.botId == token.botId && it.revokedAt == 0L }
        tokens.add(token)
        saveSnapshot()
    }

    fun tokensFor(botId: String): List<BotToken> = synchronized(lock) {
        tokens.filter { it.botId == botId }
    }

    fun revokeTokens(botId: String, now: Long): Int = synchronized(lock) {
        var revoked = 0
        for (index in tokens.indices) {
            val token = tokens[index]
            if (token.botId == botId && token.revokedAt == 0L) {
                tokens[index] = token.copy(revokedAt = now)
                revoked++
            }
        }
        auditLog.add(BotAudit(botId = botId, action = "revoke", at = now))
        saveSnapshot()
        revoked
    }

    /** Обновляет lastUsedAt только в памяти: не переписываем снапшот на запрос. */
    fun markTokenUsed(botId: String, tokenId: String, now: Long) = synchronized(lock) {
        val index = tokens.indexOfFirst { it.id == tokenId && it.botId == botId }
        if (index >= 0) tokens[index] = tokens[index].copy(lastUsedAt = now)
    }

    // ------------------------------------------------------------ user state

    fun userState(botId: String, username: String): BotUserState? = synchronized(lock) {
        userStates[key(botId, username)]
    }

    fun markStarted(botId: String, username: String): BotUserState = synchronized(lock) {
        val clean = username.lowercase()
        val current = userStates[key(botId, clean)]
        val now = System.currentTimeMillis()
        val next = BotUserState(
            botId = botId,
            username = clean,
            startedAt = current?.startedAt?.takeIf { it > 0 } ?: now,
            blockedAt = 0,
            lastInteraction = now,
        )
        userStates[key(botId, clean)] = next
        saveSnapshot()
        next
    }

    fun blockUser(botId: String, username: String, blocked: Boolean): BotUserState = synchronized(lock) {
        val clean = username.lowercase()
        val current = userStates[key(botId, clean)]
            ?: BotUserState(botId = botId, username = clean, startedAt = System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val next = current.copy(
            blockedAt = if (blocked) now else 0,
            lastInteraction = now,
        )
        userStates[key(botId, clean)] = next
        saveSnapshot()
        next
    }

    fun touchUser(botId: String, username: String) = synchronized(lock) {
        val clean = username.lowercase()
        val current = userStates[key(botId, clean)] ?: return@synchronized
        userStates[key(botId, clean)] = current.copy(lastInteraction = System.currentTimeMillis())
    }

    // --------------------------------------------------------------- sessions

    fun session(owner: String): BotFatherSession? = synchronized(lock) {
        sessions[owner.lowercase()]
    }

    fun sessionFor(owner: String, ttlMs: Long): BotFatherSession? = synchronized(lock) {
        val clean = owner.lowercase()
        val current = sessions[clean] ?: return@synchronized null
        if (System.currentTimeMillis() - current.updatedAt > ttlMs) {
            sessions.remove(clean)
            saveSnapshot()
            return@synchronized null
        }
        current
    }

    fun saveSession(session: BotFatherSession) = synchronized(lock) {
        sessions[session.owner.lowercase()] = session.copy(
            owner = session.owner.lowercase(),
            updatedAt = if (session.updatedAt > 0) session.updatedAt else System.currentTimeMillis(),
        )
        saveSnapshot()
    }

    fun resetSession(owner: String) = synchronized(lock) {
        if (sessions.remove(owner.lowercase()) != null) saveSnapshot()
    }

    // ---------------------------------------------------------------- updates

    fun appendUpdate(
        botId: String,
        type: String,
        message: BotMessage? = null,
        callbackQuery: BotCallbackQuery? = null,
        chatMember: BotChatMember? = null,
    ): BotUpdate = synchronized(lock) {
        val now = System.currentTimeMillis()
        val updateId = (updateCounters[botId] ?: 1L)
        updateCounters[botId] = updateId + 1
        val update = BotUpdate(
            botId = botId,
            updateId = updateId,
            type = type,
            message = message,
            callbackQuery = callbackQuery,
            chatMember = chatMember,
            createdAt = now,
            expiresAt = now + UPDATE_TTL_MS,
        )
        val queue = updates.getOrPut(botId) { ArrayDeque() }
        queue.addLast(update)
        while (queue.size > MAX_UPDATES_PER_BOT) queue.removeFirst()
        appendUpdateLine(update)
        update
    }

    fun pendingUpdates(
        botId: String,
        offset: Long,
        limit: Int,
        allowed: Set<String>,
    ): List<BotUpdate> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val queue = updates[botId] ?: return@synchronized emptyList()
        queue.removeAll { it.expiresAt in 1..now }
        val effectiveOffset = maxOf(offset, offsets[botId] ?: 0L)
        queue.asSequence()
            .filter { it.updateId >= effectiveOffset }
            .filter { allowed.isEmpty() || it.type in allowed }
            .take(limit)
            .toList()
    }

    fun webhookPending(
        botId: String,
        limit: Int,
        allowed: Set<String>,
    ): List<BotUpdate> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val queue = updates[botId] ?: return@synchronized emptyList()
        queue.removeAll { it.expiresAt in 1..now }
        queue.asSequence()
            .filter { allowed.isEmpty() || it.type in allowed }
            .take(limit)
            .toList()
    }

    fun pendingUpdateCount(botId: String): Int = synchronized(lock) {
        val now = System.currentTimeMillis()
        updates[botId]?.count { it.expiresAt == 0L || it.expiresAt > now } ?: 0
    }

    // -------------------------------------------------------------- webhooks

    fun webhook(botId: String): BotWebhook? = synchronized(lock) { webhooks[botId] }

    fun setWebhook(hook: BotWebhook) = synchronized(lock) {
        webhooks[hook.botId] = hook
        saveSnapshot()
    }

    fun deleteWebhook(botId: String): Boolean = synchronized(lock) {
        val removed = webhooks.remove(botId) != null
        if (removed) saveSnapshot()
        removed
    }

    fun webhookBotIds(): List<String> = synchronized(lock) { webhooks.keys.toList() }

    fun updateWebhook(botId: String, transform: (BotWebhook) -> BotWebhook) = synchronized(lock) {
        val current = webhooks[botId] ?: return@synchronized
        webhooks[botId] = transform(current)
        saveSnapshot()
    }

    fun acknowledge(botId: String, offset: Long) = synchronized(lock) {
        if (offset <= 0) return@synchronized
        val previous = offsets[botId] ?: 0L
        if (offset > previous) {
            offsets[botId] = offset
            updates[botId]?.removeAll { it.updateId < offset }
            saveSnapshot()
        }
    }

    fun dropPending(botId: String) = synchronized(lock) {
        updates[botId]?.clear()
        offsets.remove(botId)
        saveSnapshot()
    }

    fun hasUpdates(botId: String, offset: Long): Boolean = synchronized(lock) {
        updates[botId]?.any { it.updateId >= offset } == true
    }

    // --------------------------------------------------------------- messages

    fun putMessage(message: BotMessage): BotMessage = synchronized(lock) {
        val withSeq = message.copy(seq = nextSeq++)
        val perBot = messages.getOrPut(message.botId) { LinkedHashMap() }
        perBot[message.messageId] = withSeq
        trimMessages(message.botId)
        appendChatEvent(BotChatEvent(type = "upsert", message = withSeq))
        withSeq
    }

    fun message(botId: String, chatId: String, messageId: String): BotMessage? = synchronized(lock) {
        messages[botId]?.get(messageId)?.takeIf { it.chatId == chatId }
    }

    fun messageById(botId: String, messageId: String): BotMessage? = synchronized(lock) {
        messages[botId]?.get(messageId)
    }

    fun messagesInChat(botId: String, chatId: String): List<BotMessage> = synchronized(lock) {
        messages[botId]?.values?.filter { it.chatId == chatId } ?: emptyList()
    }

    fun updateMessage(message: BotMessage): BotMessage? = synchronized(lock) {
        val perBot = messages[message.botId] ?: return@synchronized null
        if (!perBot.containsKey(message.messageId)) return@synchronized null
        val withSeq = message.copy(seq = nextSeq++, editedAt = System.currentTimeMillis())
        perBot[message.messageId] = withSeq
        appendChatEvent(BotChatEvent(type = "upsert", message = withSeq))
        withSeq
    }

    fun markDeleted(botId: String, chatId: String, messageId: String): BotMessage? = synchronized(lock) {
        val perBot = messages[botId] ?: return@synchronized null
        val current = perBot[messageId]?.takeIf { it.chatId == chatId } ?: return@synchronized null
        val withSeq = current.copy(seq = nextSeq++, deletedAt = System.currentTimeMillis())
        perBot[messageId] = withSeq
        appendChatEvent(BotChatEvent(type = "delete", message = withSeq))
        withSeq
    }

    fun inbox(
        user: String,
        since: Long,
        limit: Int,
        roomAccess: Map<String, Long> = emptyMap(),
    ): List<InboxEntry> = synchronized(lock) {
        val clean = user.lowercase()
        val chatId = "u:$clean"
        val result = ArrayList<InboxEntry>()
        for ((botId, perBot) in messages) {
            val bot = bots[botId] ?: continue
            for (message in perBot.values) {
                if (message.direction != "out" || message.seq <= since) continue
                val roomId = when {
                    message.chatId.startsWith("g:") || message.chatId.startsWith("c:") ->
                        message.chatId.substringAfter(':')
                    else -> null
                }
                val visible = if (roomId == null) {
                    message.chatId == chatId
                } else {
                    val joinedAt = roomAccess[roomId]
                    joinedAt != null && message.createdAt >= joinedAt
                }
                if (!visible) continue
                val entry = inboxEntry(bot, message)
                result.add(InboxEntry(seq = message.seq, event = entry))
            }
        }
        for (answer in answers) {
            if (!answer.user.equals(clean, true) || answer.seq <= since) continue
            if (bots[answer.botId] == null) continue
            result.add(
                InboxEntry(
                    seq = answer.seq,
                    event = InboxEventDto(
                        eventId = answer.seq,
                        type = "callback_answer",
                        chatId = chatId,
                        callbackQueryId = answer.callbackQueryId,
                        text = answer.text,
                        showAlert = answer.showAlert,
                    ),
                ),
            )
        }
        result.sortBy { it.seq }
        if (result.size > limit) result.take(limit) else result
    }

    fun addCallbackAnswer(
        botId: String,
        user: String,
        callbackQueryId: String,
        text: String,
        showAlert: Boolean,
    ): BotCallbackAnswer = synchronized(lock) {
        val answer = BotCallbackAnswer(
            callbackQueryId = callbackQueryId,
            botId = botId,
            user = user.lowercase(),
            text = text.take(200),
            showAlert = showAlert,
            createdAt = System.currentTimeMillis(),
            seq = nextSeq++,
        )
        answers.add(answer)
        while (answers.size > MAX_ANSWERS) answers.removeAt(0)
        appendChatEvent(BotChatEvent(type = "answer", answer = answer))
        answer
    }

    fun audit(botId: String, action: String) = synchronized(lock) {
        auditLog.add(BotAudit(botId = botId, action = action, at = System.currentTimeMillis()))
        while (auditLog.size > MAX_AUDIT) auditLog.removeAt(0)
        saveSnapshot()
    }

    // ----------------------------------------------------------- persistence

    private fun key(botId: String, username: String) = "$botId|${username.lowercase()}"

    /** Каталог BotStore (null — режим только в памяти). */
    fun rootDir(): File? = root

    private fun inboxEntry(bot: Bot, message: BotMessage): InboxEventDto {
        val type = when {
            message.deletedAt > 0 -> "bot_messages_deleted"
            message.editedAt > 0 -> "bot_message_edited"
            else -> "bot_message"
        }
        return InboxEventDto(
            eventId = message.seq,
            type = type,
            chatId = message.chatId,
            message = InboxMessageDto(
                messageId = message.messageId,
                chatId = message.chatId,
                botUsername = bot.username,
                from = if (message.direction == "out") bot.username else message.author,
                isBot = message.direction == "out",
                text = message.text,
                entities = message.entities,
                replyMarkup = message.replyMarkup,
                replyTo = message.replyToMessageId,
                date = message.createdAt / 1000,
                editedAt = message.editedAt,
                deletedAt = message.deletedAt,
                attachment = if (message.fileId.isBlank()) {
                    null
                } else {
                    InboxAttachment(
                        fileId = message.fileId,
                        fileUniqueId = message.fileUniqueId,
                        kind = message.kind.ifBlank { "document" },
                        fileName = message.fileName,
                        mimeType = message.mimeType,
                        fileSize = message.fileSize,
                    )
                },
            ),
        )
    }

    private fun trimMessages(botId: String) {
        val perBot = messages[botId] ?: return
        val cutoff = System.currentTimeMillis() - CHAT_TTL_MS
        perBot.entries.removeAll { it.value.createdAt in 1..cutoff && it.value.deletedAt == 0L }
        while (perBot.size > MAX_MESSAGES_PER_BOT) {
            val oldest = perBot.keys.firstOrNull() ?: break
            perBot.remove(oldest)
        }
    }

    private fun load() {
        val dir = root ?: return
        runCatching { dir.mkdirs() }
        val snapshotFile = File(dir, "bots.json")
        if (snapshotFile.exists()) {
            runCatching {
                val snapshot = json.decodeFromString(BotSnapshot.serializer(), snapshotFile.readText())
                nextNumericId = maxOf(snapshot.nextNumericId, 1)
                nextSeq = maxOf(snapshot.nextSeq, 1)
                updateCounters.putAll(snapshot.counters)
                snapshot.bots.forEach { bot ->
                    bots[bot.id] = bot
                    botIdsByNumeric[bot.numericId] = bot.id
                    if (bot.status == "active") botIdsByUsername[bot.username] = bot.id
                    nextNumericId = maxOf(nextNumericId, bot.numericId + 1)
                }
                tokens.addAll(snapshot.tokens)
                snapshot.userStates.forEach { userStates[key(it.botId, it.username)] = it }
                snapshot.sessions.forEach { sessions[it.owner.lowercase()] = it }
                offsets.putAll(snapshot.offsets)
                answers.addAll(snapshot.answers)
                answers.forEach { nextSeq = maxOf(nextSeq, it.seq + 1) }
                auditLog.addAll(snapshot.audit)
                snapshot.webhooks.forEach { webhooks[it.botId] = it }
            }.onFailure { log.warn("bot snapshot ignored: {}", it.javaClass.simpleName) }
        }
        loadUpdates(File(dir, "bot-updates.jsonl"))
        loadChats(File(dir, "bot-chats.jsonl"))
    }

    private fun loadUpdates(file: File) {
        if (!file.exists()) return
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val update = runCatching {
                    json.decodeFromString(BotUpdate.serializer(), line)
                }.getOrNull() ?: return@forEachLine
                val queue = updates.getOrPut(update.botId) { ArrayDeque() }
                if (queue.none { it.updateId == update.updateId }) queue.addLast(update)
                val counter = updateCounters[update.botId] ?: 1L
                updateCounters[update.botId] = maxOf(counter, update.updateId + 1)
                updatesWritten++
            }
        }.onFailure { log.warn("bot updates log ignored: {}", it.javaClass.simpleName) }
        val now = System.currentTimeMillis()
        for ((botId, queue) in updates) {
            queue.removeAll { it.expiresAt in 1..now }
            while (queue.size > MAX_UPDATES_PER_BOT) queue.removeFirst()
            updates[botId] = queue
        }
    }

    private fun loadChats(file: File) {
        if (!file.exists()) return
        val now = System.currentTimeMillis()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val event = runCatching {
                    json.decodeFromString(BotChatEvent.serializer(), line)
                }.getOrNull() ?: return@forEachLine
                chatsWritten++
                event.message?.let { message ->
                    if (message.createdAt in 1..(now - CHAT_TTL_MS) && message.deletedAt == 0L) {
                        return@let
                    }
                    messages.getOrPut(message.botId) { LinkedHashMap() }[message.messageId] = message
                    nextSeq = maxOf(nextSeq, message.seq + 1)
                }
                event.answer?.let { answer ->
                    answers.add(answer)
                    nextSeq = maxOf(nextSeq, answer.seq + 1)
                }
            }
        }.onFailure { log.warn("bot chats log ignored: {}", it.javaClass.simpleName) }
        while (answers.size > MAX_ANSWERS) answers.removeAt(0)
        messages.keys.toList().forEach { trimMessages(it) }
    }

    private fun saveSnapshot() {
        val dir = root ?: return
        runCatching {
            dir.mkdirs()
            val snapshot = BotSnapshot(
                nextNumericId = nextNumericId,
                nextSeq = nextSeq,
                counters = updateCounters.toMap(),
                bots = bots.values.toList(),
                tokens = tokens.toList(),
                userStates = userStates.values.toList(),
                sessions = sessions.values.toList(),
                offsets = offsets.toMap(),
                answers = answers.toList(),
                audit = auditLog.toList(),
                webhooks = webhooks.values.toList(),
            )
            writeAtomic(File(dir, "bots.json"), json.encodeToString(BotSnapshot.serializer(), snapshot))
        }.onFailure { log.warn("bot snapshot write failed: {}", it.javaClass.simpleName) }
    }

    private fun appendUpdateLine(update: BotUpdate) {
        val dir = root ?: return
        val file = File(dir, "bot-updates.jsonl")
        appendLine(file, json.encodeToString(BotUpdate.serializer(), update))
        updatesWritten++
        if (updatesWritten > COMPACT_AFTER_LINES) {
            val lines = updates.values.flatten()
            writeAtomic(file, lines.joinToString("\n") { json.encodeToString(BotUpdate.serializer(), it) } + "\n")
            updatesWritten = lines.size
        }
    }

    private fun appendChatEvent(event: BotChatEvent) {
        val dir = root ?: return
        val file = File(dir, "bot-chats.jsonl")
        appendLine(file, json.encodeToString(BotChatEvent.serializer(), event))
        chatsWritten++
        if (chatsWritten > COMPACT_AFTER_LINES) {
            val lines = ArrayList<String>()
            messages.values.forEach { perBot ->
                perBot.values.forEach { lines.add(json.encodeToString(BotChatEvent.serializer(), BotChatEvent(type = "upsert", message = it))) }
            }
            answers.forEach { lines.add(json.encodeToString(BotChatEvent.serializer(), BotChatEvent(type = "answer", answer = it))) }
            writeAtomic(file, lines.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" })
            chatsWritten = lines.size
        }
    }

    private fun writeAtomic(file: File, text: String) {
        val parent = file.parentFile ?: File(".")
        parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(text)
        setPermissions(temp)
        runCatching {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        setPermissions(file)
    }

    private fun appendLine(file: File, line: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write((line + "\n").toByteArray())
            out.flush()
            runCatching { out.fd.sync() }
        }
        setPermissions(file)
    }

    private fun setPermissions(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                PosixFilePermissions.fromString("rw-------"),
            )
        }
    }

    companion object {
        const val UPDATE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val CHAT_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_UPDATES_PER_BOT = 10_000
        const val MAX_MESSAGES_PER_BOT = 5_000
        const val MAX_ANSWERS = 1_000
        const val MAX_AUDIT = 1_000
        private const val COMPACT_AFTER_LINES = 20_000

        /**
         * Каталог ботов: TAGASAY_BOT_DATA, иначе рядом с пользовательским
         * снапшотом TAGASAY_DATA (не внутри него). Без env — только память.
         */
        fun defaultRoot(): File? {
            System.getenv("TAGASAY_BOT_DATA")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val data = System.getenv("TAGASAY_DATA")?.takeIf { it.isNotBlank() } ?: return null
            val parent = File(data).parentFile ?: File(".")
            return File(parent, "bots")
        }
    }
}
