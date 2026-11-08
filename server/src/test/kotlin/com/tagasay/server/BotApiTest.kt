package com.tagasay.server

import com.tagasay.server.bot.Bot
import com.tagasay.server.bot.BotAuth
import com.tagasay.server.bot.BotSecrets
import com.tagasay.server.bot.BotService
import com.tagasay.server.bot.BotStore
import com.tagasay.server.bot.BotUpdates
import com.tagasay.server.bot.EditBotRequest
import com.tagasay.server.bot.SendMessageRequest
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.Device
import com.tagasay.server.model.UserProfile
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotApiTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private class Fixture {
        val store = InMemoryStore()
        val bots = BotStore(null)
        val secrets = BotSecrets(ByteArray(32) { 7 })
        val auth = BotAuth(bots, secrets)
        val updates = BotUpdates(bots)
        val service = BotService(bots, store, auth, updates)

        fun register(username: String): String = runBlocking {
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

        fun createBot(owner: String, name: String, username: String): Pair<Bot, String> {
            val outcome = service.createBot(owner, name, username)
            return assertNotNull(outcome.bot) to assertNotNull(outcome.token)
        }
    }

    private suspend fun HttpClient.sendAsBot(token: String, chatId: String, text: String): HttpResponse =
        post("/bot/v1/sendMessage") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SendMessageRequest(chatId = chatId, text = text)))
        }

    private suspend fun HttpClient.sendAsUser(token: String, bot: String, text: String): HttpResponse =
        post("/v1/bots/$bot/send") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"text":${json.encodeToString(text)}}""")
        }

    @Test
    fun tokenValidBrokenRevoked() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val (bot, token) = f.createBot("alice", "My Bot", "my_test_bot")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }

        val me = client.get("/bot/v1/getMe") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("\"ok\":true"))
        assertTrue(me.bodyAsText().contains("\"username\":\"my_test_bot\""))

        val broken = client.get("/bot/v1/getMe") { header("Authorization", "Bearer taga_bot_1.broken-secret-value-1234567890") }
        assertEquals(HttpStatusCode.Unauthorized, broken.status)
        assertTrue(broken.bodyAsText().contains("\"ok\":false"))

        val missing = client.get("/bot/v1/getMe")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)

        val next = f.service.rotateToken("alice", bot)
        val old = client.get("/bot/v1/getMe") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.Unauthorized, old.status)
        assertTrue(old.bodyAsText().contains("revoked"))
        val fresh = client.get("/bot/v1/getMe") { header("Authorization", "Bearer $next") }
        assertEquals(HttpStatusCode.OK, fresh.status)
    }

    @Test
    fun scopeDenialReturns403() = testApplication {
        val f = Fixture()
        f.register("alice")
        val (bot, token) = f.createBot("alice", "Scoped Bot", "scoped_bot")
        f.bots.updateBot(bot.copy(scopes = emptyList()))
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }

        val denied = client.sendAsBot(token, "u:alice", "hello")
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertTrue(denied.bodyAsText().contains("not enough rights"))
    }

    @Test
    fun botCannotInitiateAndStopBlocks() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val (bot, token) = f.createBot("alice", "Gate Bot", "gate_bot")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }

        val before = client.sendAsBot(token, "u:alice", "too early")
        assertEquals(HttpStatusCode.Forbidden, before.status)
        assertTrue(before.bodyAsText().contains("initiate"))

        val start = client.sendAsUser(alice, "gate_bot", "/start")
        assertEquals(HttpStatusCode.OK, start.status)

        val after = client.sendAsBot(token, "u:alice", "hello")
        assertEquals(HttpStatusCode.OK, after.status)
        assertTrue(after.bodyAsText().contains("\"ok\":true"))

        val stop = client.sendAsUser(alice, "gate_bot", "/stop")
        assertEquals(HttpStatusCode.OK, stop.status)
        delay(1_100)
        val blocked = client.sendAsBot(token, "u:alice", "after stop")
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertTrue(blocked.bodyAsText().contains("blocked"))

        val restarted = client.sendAsUser(alice, "gate_bot", "/start")
        assertEquals(HttpStatusCode.OK, restarted.status)
        delay(1_100)
        val again = client.sendAsBot(token, "u:alice", "back")
        assertEquals(HttpStatusCode.OK, again.status)
        assertNotNull(bot)
    }

    @Test
    fun foreignBotAndOwnerIsolation() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val bob = f.register("bob")
        val (bobBot, bobToken) = f.createBot("bob", "Bob Bot", "bob_helper_bot")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }

        val mine = client.get("/v1/bots/mine") { header("Authorization", "Bearer $alice") }
        assertEquals(HttpStatusCode.OK, mine.status)
        assertFalse(mine.bodyAsText().contains("bob_helper_bot"))

        val deleteForeign = client.delete("/v1/bots/bob_helper_bot") {
            header("Authorization", "Bearer $alice")
        }
        assertEquals(HttpStatusCode.Forbidden, deleteForeign.status)

        val revokeForeign = client.post("/v1/bots/bob_helper_bot/revoke") {
            header("Authorization", "Bearer $alice")
        }
        assertEquals(HttpStatusCode.Forbidden, revokeForeign.status)

        val tokenForeign = client.post("/v1/bots/bob_helper_bot/token") {
            header("Authorization", "Bearer $alice")
        }
        assertEquals(HttpStatusCode.Forbidden, tokenForeign.status)

        // Токен Боба не даёт доступа к боту Алисы.
        val (_, aliceToken) = f.createBot("alice", "Alice Bot", "alice_helper_bot")
        val bobUsesAlice = client.sendAsBot(bobToken, "u:alice", "hi")
        assertEquals(HttpStatusCode.Forbidden, bobUsesAlice.status)
        val aliceMe = client.get("/bot/v1/getMe") { header("Authorization", "Bearer $aliceToken") }
        assertEquals(HttpStatusCode.OK, aliceMe.status)
        assertNotNull(bobBot)
    }

    @Test
    fun usernameBusyAndLimitTwenty() {
        val f = Fixture()
        f.register("alice")
        f.register("takenbot")
        val first = f.service.createBot("alice", "First", "first_bot")
        assertNotNull(first.bot)
        val duplicate = f.service.createBot("alice", "Second", "first_bot")
        assertEquals("username is busy", duplicate.error)
        val userBusy = f.service.createBot("alice", "Third", "takenbot")
        assertEquals("username is busy", userBusy.error)
        val reserved = f.service.createBot("alice", "Fourth", "botfather")
        assertNotNull(reserved.error)

        // Лимит 20 на владельца: уже создан 1, добиваем до 20 и проверяем 21-го.
        var created = 1
        var index = 0
        while (created < 20) {
            val outcome = f.service.createBot("alice", "Bot $index", "limit_${index}_bot")
            if (outcome.bot != null) created++
            index++
        }
        assertEquals(20, f.bots.countOwned("alice"))
        val overflow = f.service.createBot("alice", "Overflow", "overflow_bot")
        assertTrue(overflow.error?.contains("limit") == true)
    }

    @Test
    fun getUpdatesOffsetAcknowledgementAndNoDuplicates() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val (_, token) = f.createBot("alice", "Updates Bot", "updates_bot")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }

        client.sendAsUser(alice, "updates_bot", "/start")
        client.sendAsUser(alice, "updates_bot", "hello")

        val first = client.get("/bot/v1/getUpdates?offset=0&timeout=0") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = first.bodyAsText()
        assertTrue(firstBody.contains("\"update_id\":1"))
        assertTrue(firstBody.contains("\"update_id\":2"))
        assertTrue(firstBody.contains("\"text\":\"hello\""))

        val acked = client.get("/bot/v1/getUpdates?offset=3&timeout=0") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, acked.status)
        assertTrue(acked.bodyAsText().contains("\"result\":[]"))

        client.sendAsUser(alice, "updates_bot", "third")
        val next = client.get("/bot/v1/getUpdates?offset=3&timeout=0") {
            header("Authorization", "Bearer $token")
        }
        assertTrue(next.bodyAsText().contains("\"update_id\":3"))
        assertFalse(next.bodyAsText().contains("\"text\":\"hello\""))
    }

    @Test
    fun unsupportedMethodIsHonest400() = testApplication {
        val f = Fixture()
        f.register("alice")
        val (_, token) = f.createBot("alice", "Honest Bot", "honest_bot")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }

        val webhook = client.post("/bot/v1/getChat") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"chat_id":"u:alice"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, webhook.status)
        assertTrue(webhook.bodyAsText().contains("not supported"))
    }

    @Test
    fun editAndDeleteOwnMessagesOnly() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val (bot, token) = f.createBot("alice", "Edit Bot", "edit_bot")
        f.service.ingestUserMessage("alice", bot, com.tagasay.server.bot.UserSendToBotRequest(text = "/start"))
        val sent = f.service.sendMessage(bot, SendMessageRequest(chatId = "u:alice", text = "original"))
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }

        val edit = client.post("/bot/v1/editMessageText") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"chat_id":"u:alice","message_id":"${sent.messageId}","text":"edited"}""")
        }
        assertEquals(HttpStatusCode.OK, edit.status)
        assertTrue(edit.bodyAsText().contains("\"text\":\"edited\""))

        val delete = client.post("/bot/v1/deleteMessage") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"chat_id":"u:alice","message_id":"${sent.messageId}"}""")
        }
        assertEquals(HttpStatusCode.OK, delete.status)
        assertTrue(delete.bodyAsText().contains("\"result\":true"))

        // Чужое/несуществующее сообщение — 400.
        val missing = client.post("/bot/v1/deleteMessage") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"chat_id":"u:alice","message_id":"m_missing"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, missing.status)

        val update = f.service.updateBot("alice", bot, EditBotRequest(description = "desc"))
        assertEquals("desc", update.description)
    }
}
