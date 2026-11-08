package com.tagasay.server

import com.tagasay.server.bot.Bot
import com.tagasay.server.bot.BotAuth
import com.tagasay.server.bot.BotCommand
import com.tagasay.server.bot.BotMessage
import com.tagasay.server.bot.BotSecrets
import com.tagasay.server.bot.BotService
import com.tagasay.server.bot.BotStore
import com.tagasay.server.bot.BotUpdates
import com.tagasay.server.bot.SendMessageRequest
import com.tagasay.server.bot.SetMyCommandsRequest
import com.tagasay.server.bot.UserSendToBotRequest
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.Device
import com.tagasay.server.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotStoreTest {

    private val secrets = BotSecrets(ByteArray(32) { 3 })

    private fun register(store: InMemoryStore, username: String) = runBlocking {
        val device = Device(
            deviceId = "$username-device",
            username = username,
            identityKey = "identity",
            signingKey = "signing",
            passwordVerifier = "verifier",
            deviceHash = "hash",
            signedPrekey = "prekey",
            signedPrekeySignature = "signature",
            oneTimePrekeys = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        store.register(device, UserProfile(username, username, "identity"), null).first
    }

    @Test
    fun storeSurvivesRestart() {
        val dir = createTempDirectory("taga-bots").toFile()
        val users = InMemoryStore()
        register(users, "alice")
        val store = BotStore(dir)
        val auth = BotAuth(store, secrets)
        val service = BotService(store, users, auth, BotUpdates(store))

        val outcome = service.createBot("alice", "Persist Bot", "persist_bot")
        val bot = assertNotNull(outcome.bot)
        val token = assertNotNull(outcome.token)
        service.setMyCommands(bot, SetMyCommandsRequest(listOf(BotCommand("ping", "Ping"))))
        service.ingestUserMessage("alice", bot, UserSendToBotRequest(text = "/start"))
        val sent = service.sendMessage(bot, SendMessageRequest(chatId = "u:alice", text = "hello"))
        val pendingBefore = store.pendingUpdates(bot.id, 0, 10, emptySet())
        assertTrue(pendingBefore.isNotEmpty())

        val reopened = BotStore(dir)
        val reopenedAuth = BotAuth(reopened, secrets)
        val result = reopenedAuth.authenticate("Bearer $token")
        assertTrue(result is BotAuth.Result.Ok, "token must survive restart")

        val loaded = assertNotNull(reopened.botByUsername("persist_bot"))
        assertEquals(bot.id, loaded.id)
        assertEquals(listOf("ping"), loaded.commands.map { it.command })
        assertTrue(reopened.pendingUpdates(loaded.id, 0, 10, emptySet()).isNotEmpty())
        assertTrue(reopened.inbox("alice", 0, 10).any { it.event.type == "bot_message" })
        assertNotNull(reopened.messageById(loaded.id, sent.messageId))

        assertTrue(File(dir, "bots.json").exists())
        assertTrue(File(dir, "bot-updates.jsonl").exists())
        assertTrue(File(dir, "bot-chats.jsonl").exists())

        dir.deleteRecursively()
    }

    @Test
    fun corruptTempAndJsonLinesAreIgnored() {
        val dir = createTempDirectory("taga-bots-crash").toFile()
        val store = BotStore(dir)
        val bot = assertNotNull(store.createBot("alice", "crash_bot", "Crash Bot"))
        store.appendUpdate(bot.id, "message", message = message(bot, "m_1"))

        File(dir, "bots.json.tmp").writeText("{ this is a half-written file")
        File(dir, "bot-updates.jsonl").appendText("not a json line\n")

        val reopened = BotStore(dir)
        val debug = "files=" + dir.list()?.joinToString()
        assertNotNull(reopened.botByUsername("crash_bot"), debug)
        assertTrue(reopened.pendingUpdates(bot.id, 0, 10, emptySet()).isNotEmpty())

        dir.deleteRecursively()
    }

    @Test
    fun concurrentMessagesAllPersist() {
        val dir = createTempDirectory("taga-bots-concurrent").toFile()
        val store = BotStore(dir)
        runBlocking {
            (0 until 40).map { index ->
                launch(Dispatchers.Default) {
                    store.putMessage(
                        BotMessage(
                            botId = "bot_99",
                            chatId = "u:alice",
                            messageId = "m_$index",
                            direction = "out",
                            author = "concurrent_bot",
                            text = "message $index",
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }.joinAll()
        }
        val reopened = BotStore(dir)
        assertEquals(40, reopened.messagesInChat("bot_99", "u:alice").size)
        dir.deleteRecursively()
    }

    private fun message(bot: Bot, id: String) = BotMessage(
        botId = bot.id,
        chatId = "u:alice",
        messageId = id,
        direction = "out",
        author = bot.username,
        text = "text",
        createdAt = System.currentTimeMillis(),
    )
}
