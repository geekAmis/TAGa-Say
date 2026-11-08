package com.tagasay.server

import com.tagasay.server.bot.Bot
import com.tagasay.server.bot.BotAuth
import com.tagasay.server.bot.BotFatherSession
import com.tagasay.server.bot.BotSecrets
import com.tagasay.server.bot.BotService
import com.tagasay.server.bot.BotStore
import com.tagasay.server.bot.BotUpdates
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.Device
import com.tagasay.server.model.UserProfile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotFatherTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private class Fixture {
        val store = InMemoryStore()
        val bots = BotStore(null)
        val secrets = BotSecrets(ByteArray(32) { 9 })
        val auth = BotAuth(bots, secrets)
        val service = BotService(bots, store, auth, BotUpdates(bots))

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
    }

    private suspend fun HttpClient.sendToBotFather(token: String, text: String) =
        post("/v1/bots/BotFather/send") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"text":${json.encodeToString(text)}}""")
        }

    private suspend fun HttpClient.inbox(token: String): JsonArray {
        val response = get("/v1/bots/inbox?since=0&timeout=0&limit=100") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonArray
    }

    private fun messageObjects(events: JsonArray): List<JsonObject> = events.mapNotNull { element ->
        val event = element.jsonObject
        if (event["type"]?.jsonPrimitive?.content != "bot_message") return@mapNotNull null
        event["message"]?.jsonObject
    }

    private fun lastText(events: JsonArray): String =
        messageObjects(events).lastOrNull()?.get("text")?.jsonPrimitive?.content.orEmpty()

    private data class Button(val messageId: String, val data: String)

    private fun lastButton(events: JsonArray): Button? {
        for (message in messageObjects(events).reversed()) {
            val markup = message["reply_markup"] as? JsonObject ?: continue
            val rows = markup["inline_keyboard"]?.jsonArray ?: continue
            for (row in rows) {
                for (button in row.jsonArray) {
                    val data = (button as? JsonObject)?.get("callback_data")?.jsonPrimitive?.content
                    if (!data.isNullOrBlank()) {
                        return Button(
                            message["message_id"]!!.jsonPrimitive.content,
                            data,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun extractToken(text: String): String? =
        Regex("taga_bot_\\d+\\.[A-Za-z0-9_-]{20,}").find(text)?.value

    @Test
    fun happyPathCreateTokenRevokeDelete() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }

        assertEquals(HttpStatusCode.OK, client.sendToBotFather(alice, "/start").status)
        assertTrue(lastText(client.inbox(alice)).contains("BotFather"))

        client.sendToBotFather(alice, "/newbot")
        assertTrue(lastText(client.inbox(alice)).contains("Как назовём"))

        client.sendToBotFather(alice, "My Bot")
        assertTrue(lastText(client.inbox(alice)).contains("username"))

        val created = client.sendToBotFather(alice, "my_new_bot")
        assertEquals(HttpStatusCode.OK, created.status)
        val token = assertNotNull(extractToken(lastText(client.inbox(alice))))

        val me = client.get("/bot/v1/getMe") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("\"username\":\"my_new_bot\""))

        client.sendToBotFather(alice, "/setcommands\nhello - Say hello")
        val commandsReply = lastText(client.inbox(alice))
        assertTrue(commandsReply.contains("Сохранено команд"), "LAST=[$commandsReply]")

        val commands = client.get("/bot/v1/getMyCommands") { header("Authorization", "Bearer $token") }
        assertTrue(commands.bodyAsText().contains("\"command\":\"hello\""))

        client.sendToBotFather(alice, "/setprivacy")
        val privacyReply = lastText(client.inbox(alice))
        assertTrue(privacyReply.contains("Приватность"), "LAST=[$privacyReply]")

        client.sendToBotFather(alice, "/token")
        assertTrue(lastText(client.inbox(alice)).contains(token))

        // /revoke через подтверждение inline-кнопкой.
        client.sendToBotFather(alice, "/revoke")
        val revokeButton = assertNotNull(lastButton(client.inbox(alice)))
        val callback = client.post("/v1/bots/BotFather/callback") {
            header("Authorization", "Bearer $alice")
            contentType(ContentType.Application.Json)
            setBody(
                """{"message_id":"${revokeButton.messageId}","data":${json.encodeToString(revokeButton.data)}}""",
            )
        }
        assertEquals(HttpStatusCode.OK, callback.status)
        val newToken = assertNotNull(extractToken(lastText(client.inbox(alice))))
        assertTrue(newToken != token)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/bot/v1/getMe") { header("Authorization", "Bearer $token") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/bot/v1/getMe") { header("Authorization", "Bearer $newToken") }.status,
        )

        // /deletebot: подтверждение username, после удаления токен мёртв.
        client.sendToBotFather(alice, "/deletebot")
        assertTrue(lastText(client.inbox(alice)).contains("Чтобы удалить"))
        client.sendToBotFather(alice, "@my_new_bot")
        assertTrue(lastText(client.inbox(alice)).contains("удалён"))
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/bot/v1/getMe") { header("Authorization", "Bearer $newToken") }.status,
        )

        client.sendToBotFather(alice, "/mybots")
        assertTrue(lastText(client.inbox(alice)).contains("нет ботов"))
    }

    @Test
    fun invalidAndBusyUsernameKeepDialog() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val bob = f.register("bob")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }

        client.sendToBotFather(alice, "/start")
        client.sendToBotFather(alice, "/newbot")
        client.sendToBotFather(alice, "Second Bot")
        client.sendToBotFather(alice, "no_suffix")
        val invalidReply = lastText(client.inbox(alice))
        assertTrue(invalidReply.contains("username"), "LAST=[$invalidReply]")

        client.sendToBotFather(bob, "/start")
        client.sendToBotFather(bob, "/newbot")
        client.sendToBotFather(bob, "Bob Bot")
        client.sendToBotFather(bob, "shared_bot")
        assertTrue(lastText(client.inbox(bob)).contains("taga_bot_"))

        client.sendToBotFather(alice, "shared_bot")
        assertTrue(lastText(client.inbox(alice)).contains("занят"))

        client.sendToBotFather(alice, "alice_ok_bot")
        assertTrue(lastText(client.inbox(alice)).contains("taga_bot_"))
    }

    @Test
    fun sessionTimeoutResetsDialog() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }
        client.sendToBotFather(alice, "/start")
        f.bots.saveSession(
            BotFatherSession(
                owner = "alice",
                step = "await_new_name",
                updatedAt = System.currentTimeMillis() - BotFatherTestSupport.SESSION_TTL - 1_000,
            ),
        )
        client.sendToBotFather(alice, "Too Late")
        val text = lastText(client.inbox(alice))
        assertTrue(text.contains("/help"), "LAST=[$text]")
        assertTrue(text.contains("сброшен") || text.contains("Откройте"))
    }

    @Test
    fun cancelResetsDialog() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }
        client.sendToBotFather(alice, "/start")
        client.sendToBotFather(alice, "/newbot")
        client.sendToBotFather(alice, "/cancel")
        assertTrue(lastText(client.inbox(alice)).contains("отменён"))
        client.sendToBotFather(alice, "After Cancel")
        val text = lastText(client.inbox(alice))
        assertTrue(text.contains("/help"), "LAST=[$text]")
    }

    @Test
    fun foreignBotFatherCardIsDenied() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val bob = f.register("bob")
        val outcome = f.service.createBot("alice", "Alice Bot", "alice_only_bot")
        val bot = assertNotNull(outcome.bot)
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                updateStore = UpdateStore("missing", null, null),
            )
        }
        // Боб не видит бота Алисы в своём списке, но видит публичную карточку.
        val mine = client.get("/v1/bots/mine") { header("Authorization", "Bearer $bob") }
        assertFalse(mine.bodyAsText().contains("alice_only_bot"))
        val card = client.get("/v1/bots/alice_only_bot") { header("Authorization", "Bearer $bob") }
        assertEquals(HttpStatusCode.OK, card.status)
        assertFalse(card.bodyAsText().contains("\"owner\""))

        // Подделанный callback_data не проходит проверку подписи BotFather.
        client.sendToBotFather(bob, "/start")
        client.sendToBotFather(bob, "/mybots")
        val forged = client.post("/v1/bots/BotFather/callback") {
            header("Authorization", "Bearer $bob")
            contentType(ContentType.Application.Json)
            setBody("""{"message_id":"m_unknown","data":"bf:token:${bot.id}:deadbeef"}""")
        }
        assertEquals(HttpStatusCode.NotFound, forged.status)
    }
}

private object BotFatherTestSupport {
    const val SESSION_TTL = 20 * 60 * 1000L
}
