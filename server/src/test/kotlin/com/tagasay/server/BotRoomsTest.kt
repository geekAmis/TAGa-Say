package com.tagasay.server

import com.tagasay.server.bot.Bot
import com.tagasay.server.bot.BotAuth
import com.tagasay.server.bot.BotMediaStore
import com.tagasay.server.bot.BotSecrets
import com.tagasay.server.bot.BotService
import com.tagasay.server.bot.BotStore
import com.tagasay.server.bot.BotUpdates
import com.tagasay.server.bot.BotWebhookDelivery
import com.tagasay.server.bot.EditBotRequest
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.Device
import com.tagasay.server.model.Room
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotRoomsTest {

    private val json = Json { encodeDefaults = true }

    private class Fixture {
        val store = InMemoryStore()
        val bots = BotStore(null)
        val secrets = BotSecrets(ByteArray(32) { 8 })
        val auth = BotAuth(bots, secrets)
        val updates = BotUpdates(bots)
        val media = BotMediaStore(createTempDirectory("taga-room-files").toFile(), secrets)
        val service = BotService(bots, store, auth, updates, media = media)

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

        fun createBot(username: String): Pair<Bot, String> {
            val outcome = service.createBot("alice", "Room Bot", username)
            return assertNotNull(outcome.bot) to assertNotNull(outcome.token)
        }

        fun createRoom(
            id: String,
            username: String,
            channel: Boolean,
            members: List<String>,
        ): Room = runBlocking {
            assertNotNull(
                store.createRoom(
                    Room(
                        id = id,
                        username = username,
                        title = username,
                        channel = channel,
                        owner = "alice",
                        members = members,
                    ),
                ),
            )
        }
    }

    private suspend fun HttpClient.addBot(
        userToken: String,
        roomId: String,
        bot: String,
        postMessages: Boolean = false,
    ): HttpResponse = post("/v1/rooms/$roomId/bots") {
        header("Authorization", "Bearer $userToken")
        contentType(ContentType.Application.Json)
        setBody(
            """{"username":"$bot","rights":{"post_messages":$postMessages}}""",
        )
    }

    private suspend fun HttpClient.sendAsBot(token: String, chatId: String, text: String): HttpResponse =
        post("/bot/v1/sendMessage") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"chat_id":"$chatId","text":${json.encodeToString(text)}}""")
        }

    private suspend fun HttpClient.sendAsUser(
        token: String,
        bot: String,
        chatId: String,
        text: String,
        replyTo: String = "",
    ): HttpResponse = post("/v1/bots/$bot/send") {
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(
            buildString {
                append("{\"chat_id\":").append(json.encodeToString(chatId))
                append(",\"text\":").append(json.encodeToString(text))
                if (replyTo.isNotBlank()) append(",\"reply_to\":").append(json.encodeToString(replyTo))
                append("}")
            },
        )
    }

    private suspend fun HttpClient.botUpdates(token: String): String =
        get("/bot/v1/getUpdates?offset=0&timeout=0&limit=100") {
            header("Authorization", "Bearer $token")
        }.bodyAsText()

    private suspend fun HttpClient.inbox(token: String): String =
        get("/v1/bots/inbox?since=0&timeout=0&limit=100") {
            header("Authorization", "Bearer $token")
        }.bodyAsText()

    @Test
    fun addRemoveOnlyOwnerOrAdminAndMyChatMember() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val bob = f.register("bob")
        f.register("carol")
        f.createRoom("room_team", "team", channel = false, members = listOf("bob"))
        val (bot, token) = f.createBot("team_bot")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                botMedia = f.media,
                botWebhook = BotWebhookDelivery(f.bots, f.secrets, autoStart = false),
                publicBaseUrl = "https://relay.test",
                updateStore = UpdateStore("missing", null, null),
            )
        }

        val byMember = client.addBot(bob, "room_team", "team_bot")
        assertEquals(HttpStatusCode.Forbidden, byMember.status)

        val added = client.addBot(alice, "room_team", "team_bot")
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())
        assertTrue(added.bodyAsText().contains("\"bots\""))

        val duplicate = client.addBot(alice, "room_team", "team_bot")
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        val updates = client.botUpdates(token)
        assertTrue(updates.contains("my_chat_member"))
        assertTrue(updates.contains("\"new_chat_member\""))

        val byMemberDelete = client.delete("/v1/rooms/room_team/bots/team_bot") {
            header("Authorization", "Bearer $bob")
        }
        assertEquals(HttpStatusCode.Forbidden, byMemberDelete.status)

        val removed = client.delete("/v1/rooms/room_team/bots/team_bot") {
            header("Authorization", "Bearer $alice")
        }
        assertEquals(HttpStatusCode.OK, removed.status)
        assertFalse(removed.bodyAsText().contains("team_bot"))

        val kicked = client.botUpdates(token)
        assertTrue(kicked.contains("kicked"))

        val noLongerMember = client.sendAsBot(token, "g:room_team", "hello")
        assertEquals(HttpStatusCode.Forbidden, noLongerMember.status)
        assertNotNull(bot)
    }

    @Test
    fun channelPostRequiresPostMessagesAndSubscriberSeesIt() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val bob = f.register("bob")
        val carol = f.register("carol")
        f.createRoom("room_news", "news", channel = true, members = listOf("bob"))
        val (_, token) = f.createBot("news_bot")
        val (_, weakToken) = f.createBot("weak_bot")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                botMedia = f.media,
                botWebhook = BotWebhookDelivery(f.bots, f.secrets, autoStart = false),
                publicBaseUrl = "https://relay.test",
                updateStore = UpdateStore("missing", null, null),
            )
        }

        val weakAdded = client.addBot(alice, "room_news", "weak_bot", postMessages = false)
        assertEquals(HttpStatusCode.OK, weakAdded.status)
        val weakPost = client.sendAsBot(weakToken, "c:room_news", "no rights")
        assertEquals(HttpStatusCode.Forbidden, weakPost.status)

        val added = client.addBot(alice, "room_news", "news_bot", postMessages = true)
        assertEquals(HttpStatusCode.OK, added.status)
        val post = client.sendAsBot(token, "c:room_news", "channel news")
        assertEquals(HttpStatusCode.OK, post.status, post.bodyAsText())

        val bobInbox = client.inbox(bob)
        assertTrue(bobInbox.contains("channel news"))
        assertTrue(bobInbox.contains("c:room_news"))

        val carolInbox = client.inbox(carol)
        assertFalse(carolInbox.contains("channel news"))
    }

    @Test
    fun privacyModeGatesGroupMessagesAndReplyUnlocks() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val bob = f.register("bob")
        f.createRoom("room_priv", "priv", channel = false, members = listOf("bob"))
        val (bot, token) = f.createBot("priv_bot")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                botMedia = f.media,
                botWebhook = BotWebhookDelivery(f.bots, f.secrets, autoStart = false),
                publicBaseUrl = "https://relay.test",
                updateStore = UpdateStore("missing", null, null),
            )
        }

        client.addBot(alice, "room_priv", "priv_bot")
        val silent = client.sendAsUser(bob, "priv_bot", "g:room_priv", "just chatting")
        assertEquals(HttpStatusCode.OK, silent.status)
        assertTrue(silent.bodyAsText().contains("\"delivered\":false"))
        assertFalse(client.botUpdates(token).contains("just chatting"))

        val command = client.sendAsUser(bob, "priv_bot", "g:room_priv", "/status")
        assertTrue(command.bodyAsText().contains("\"delivered\":true"))
        assertTrue(client.botUpdates(token).contains("/status"))

        val mention = client.sendAsUser(bob, "priv_bot", "g:room_priv", "@priv_bot help")
        assertTrue(mention.bodyAsText().contains("\"delivered\":true"))

        val botPost = client.sendAsBot(token, "g:room_priv", "bot message")
        assertEquals(HttpStatusCode.OK, botPost.status, botPost.bodyAsText())
        val botMessageId = Json.parseToJsonElement(botPost.bodyAsText()).jsonObject["result"]!!
            .jsonObject["message_id"]!!.jsonPrimitive.content

        val reply = client.sendAsUser(bob, "priv_bot", "g:room_priv", "reply text", replyTo = botMessageId)
        assertTrue(reply.bodyAsText().contains("\"delivered\":true"))
        assertTrue(client.botUpdates(token).contains("reply text"))

        f.service.updateBot("alice", bot, EditBotRequest(privacy_mode = false))
        val open = client.sendAsUser(bob, "priv_bot", "g:room_priv", "now everything")
        assertTrue(open.bodyAsText().contains("\"delivered\":true"))
    }

    @Test
    fun botWithGroupsDisabledCannotBeAdded() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        f.createRoom("room_closed", "closed", channel = false, members = emptyList())
        val (bot, _) = f.createBot("closed_bot")
        f.bots.updateBot(bot.copy(canJoinGroups = false))
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                botMedia = f.media,
                botWebhook = BotWebhookDelivery(f.bots, f.secrets, autoStart = false),
                publicBaseUrl = "https://relay.test",
                updateStore = UpdateStore("missing", null, null),
            )
        }
        val added = client.addBot(alice, "room_closed", "closed_bot")
        assertEquals(HttpStatusCode.BadRequest, added.status)
        assertTrue(added.bodyAsText().contains("groups"))
    }
}
