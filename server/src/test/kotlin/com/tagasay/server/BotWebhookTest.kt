package com.tagasay.server

import com.sun.net.httpserver.Headers as SunHeaders
import com.sun.net.httpserver.HttpServer
import com.tagasay.server.bot.Bot
import com.tagasay.server.bot.BotAuth
import com.tagasay.server.bot.BotMediaStore
import com.tagasay.server.bot.BotSecrets
import com.tagasay.server.bot.BotService
import com.tagasay.server.bot.BotStore
import com.tagasay.server.bot.BotUpdates
import com.tagasay.server.bot.BotWebhookDelivery
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.Device
import com.tagasay.server.model.UserProfile
import io.ktor.client.HttpClient
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
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotWebhookTest {

    private val json = Json { encodeDefaults = true }

    private class FakeWebhookServer(var status: Int = 200) {
        data class Captured(val headers: SunHeaders, val body: String)

        val requests = CopyOnWriteArrayList<Captured>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/hook") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                requests.add(Captured(exchange.requestHeaders, body))
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            server.start()
        }

        fun url(): String = "http://127.0.0.1:${server.address.port}/hook"

        fun stop() = server.stop(0)
    }

    private class Fixture(
        allowPrivateHosts: Boolean = true,
        retryDelaysMs: List<Long> = listOf(0, 10, 10),
    ) {
        val store = InMemoryStore()
        val bots = BotStore(null)
        val secrets = BotSecrets(ByteArray(32) { 6 })
        val auth = BotAuth(bots, secrets)
        val updates = BotUpdates(bots)
        val media = BotMediaStore(createTempDirectory("taga-webhook-files").toFile(), secrets)
        val webhook = BotWebhookDelivery(
            bots,
            secrets,
            allowPrivateHosts = allowPrivateHosts,
            retryDelaysMs = retryDelaysMs,
            timeoutMs = 2_000,
            autoStart = false,
        )
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

        fun createBot(username: String = "hook_bot"): Pair<Bot, String> {
            val outcome = service.createBot("alice", "Webhook Bot", username)
            return assertNotNull(outcome.bot) to assertNotNull(outcome.token)
        }
    }

    private suspend fun HttpClient.sendToBot(token: String, text: String) =
        post("/v1/bots/hook_bot/send") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"text":${json.encodeToString(text)}}""")
        }

    private suspend fun HttpClient.setWebhook(
        botToken: String,
        url: String,
        secret: String? = null,
        allowed: List<String> = listOf("message"),
        drop: Boolean = false,
    ): HttpResponse = post("/bot/v1/setWebhook") {
        header("Authorization", "Bearer $botToken")
        contentType(ContentType.Application.Json)
        setBody(
            buildString {
                append("{\"url\":").append(json.encodeToString(url))
                if (secret != null) append(",\"secret_token\":").append(json.encodeToString(secret))
                append(",\"allowed_updates\":").append(json.encodeToString(allowed))
                append(",\"drop_pending_updates\":").append(drop)
                append("}")
            },
        )
    }

    private suspend fun HttpClient.deleteWebhook(botToken: String, drop: Boolean): HttpResponse =
        post("/bot/v1/deleteWebhook") {
            header("Authorization", "Bearer $botToken")
            contentType(ContentType.Application.Json)
            setBody("{\"drop_pending_updates\":$drop}")
        }

    @Test
    fun deliveryHmac409AndDeleteWebhook() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val (bot, token) = f.createBot()
        val fake = FakeWebhookServer()
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                botMedia = f.media,
                botWebhook = f.webhook,
                publicBaseUrl = "https://relay.test",
                updateStore = UpdateStore("missing", null, null),
            )
        }

        assertEquals(HttpStatusCode.OK, client.sendToBot(alice, "/start").status)
        val set = client.setWebhook(token, fake.url(), secret = "s3cret", drop = true)
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())

        val conflict = client.get("/bot/v1/getUpdates?offset=0&timeout=0") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.Conflict, conflict.status)

        assertEquals(HttpStatusCode.OK, client.sendToBot(alice, "hello webhook").status)
        val info = client.get("/bot/v1/getWebhookInfo") { header("Authorization", "Bearer $token") }
        assertTrue(info.bodyAsText().contains("\"pending_update_count\":1"))

        val report = f.webhook.deliverPending(bot.id)
        assertEquals(1, report.delivered)
        assertEquals(1, fake.requests.size)
        val captured = fake.requests.first()
        val timestamp = captured.headers.getFirst("X-TAGa-Say-Timestamp")
        val signature = captured.headers.getFirst("X-TAGa-Say-Signature")
        assertEquals("s3cret", captured.headers.getFirst("X-TAGa-Say-Bot-Api-Secret-Token"))
        assertEquals("sha256=" + f.webhook.sign("s3cret", timestamp, captured.body), signature)
        assertTrue(captured.body.contains("hello webhook"))

        val after = client.get("/bot/v1/getWebhookInfo") { header("Authorization", "Bearer $token") }
        assertTrue(after.bodyAsText().contains("\"pending_update_count\":0"))

        val deleted = client.deleteWebhook(token, drop = true)
        assertEquals(HttpStatusCode.OK, deleted.status)
        val polling = client.get("/bot/v1/getUpdates?offset=0&timeout=0") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, polling.status)
        fake.stop()
    }

    @Test
    fun retries5xxStoreLastErrorAndRecover() = testApplication {
        val f = Fixture(retryDelaysMs = listOf(0, 10, 10))
        val alice = f.register("alice")
        val (bot, token) = f.createBot()
        val fake = FakeWebhookServer(status = 500)
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                botMedia = f.media,
                botWebhook = f.webhook,
                publicBaseUrl = "https://relay.test",
                updateStore = UpdateStore("missing", null, null),
            )
        }

        client.sendToBot(alice, "/start")
        client.setWebhook(token, fake.url(), secret = "s3cret", drop = true)
        client.sendToBot(alice, "retry me")
        val failed = f.webhook.deliverPending(bot.id)
        assertEquals(1, failed.failed)
        assertEquals(3, fake.requests.size)
        val hook = assertNotNull(f.bots.webhook(bot.id))
        assertTrue(hook.lastError.contains("500"))
        assertEquals(1, hook.failureCount)
        assertEquals(1, f.bots.pendingUpdateCount(bot.id))

        fake.status = 200
        f.bots.updateWebhook(bot.id) { it.copy(nextAttemptAt = 0) }
        val recovered = f.webhook.deliverPending(bot.id)
        assertEquals(1, recovered.delivered)
        assertEquals(0, f.bots.pendingUpdateCount(bot.id))
        fake.stop()
    }

    @Test
    fun redirectIsRejected() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val (bot, token) = f.createBot()
        val fake = FakeWebhookServer(status = 302)
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                botMedia = f.media,
                botWebhook = f.webhook,
                publicBaseUrl = "https://relay.test",
                updateStore = UpdateStore("missing", null, null),
            )
        }
        client.sendToBot(alice, "/start")
        client.setWebhook(token, fake.url(), drop = true)
        client.sendToBot(alice, "redirect")
        val report = f.webhook.deliverPending(bot.id)
        assertEquals(1, report.failed)
        assertTrue(report.lastError.contains("redirect"))
        assertEquals(3, fake.requests.size)
        fake.stop()
    }

    @Test
    fun ssrfRejectsPrivateAndHttpHosts() = testApplication {
        val f = Fixture(allowPrivateHosts = false)
        val alice = f.register("alice")
        val (_, token) = f.createBot()
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                botMedia = f.media,
                botWebhook = f.webhook,
                publicBaseUrl = "https://relay.test",
                updateStore = UpdateStore("missing", null, null),
            )
        }
        assertEquals(HttpStatusCode.OK, client.sendToBot(alice, "/start").status)
        val urls = listOf(
            "https://127.0.0.1/hook",
            "https://localhost/hook",
            "https://169.254.169.254/hook",
            "https://10.0.0.1/hook",
            "https://[::1]/hook",
            "http://example.com/hook",
        )
        for (url in urls) {
            val response = client.setWebhook(token, url)
            assertEquals(HttpStatusCode.BadRequest, response.status, "url=$url body=${response.bodyAsText()}")
        }
    }

    @Test
    fun dropPendingOnDelete() = testApplication {
        val f = Fixture()
        val alice = f.register("alice")
        val (_, token) = f.createBot()
        val fake = FakeWebhookServer()
        application {
            module(
                store = f.store,
                botStore = f.bots,
                botSecrets = f.secrets,
                botMedia = f.media,
                botWebhook = f.webhook,
                publicBaseUrl = "https://relay.test",
                updateStore = UpdateStore("missing", null, null),
            )
        }
        client.sendToBot(alice, "/start")
        client.sendToBot(alice, "queued")
        client.setWebhook(token, fake.url())
        val deleted = client.deleteWebhook(token, drop = true)
        assertEquals(HttpStatusCode.OK, deleted.status)
        val polling = client.get("/bot/v1/getUpdates?offset=0&timeout=0") {
            header("Authorization", "Bearer $token")
        }
        val result = Json.parseToJsonElement(polling.bodyAsText()).jsonObject["result"]!!.jsonArray
        assertEquals(0, result.size)
        fake.stop()
    }
}
