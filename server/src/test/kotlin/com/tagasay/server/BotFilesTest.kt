package com.tagasay.server

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
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotFilesTest {

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )

    private val pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF\n".toByteArray()

    private class Fixture(
        val dir: File,
        val clock: () -> Long,
    ) {
        val store = InMemoryStore()
        val bots = BotStore(null)
        val secrets = BotSecrets(ByteArray(32) { 4 })
        val auth = BotAuth(bots, secrets)
        val updates = BotUpdates(bots)
        val media = BotMediaStore(
            dir,
            secrets,
            maxFileBytes = 4096,
            botQuotaBytes = 100_000,
            dailyQuotaBytes = 100_000,
            now = clock,
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

        fun createBot(username: String = "file_bot"): Pair<Bot, String> {
            val outcome = service.createBot("alice", "File Bot", username)
            return assertNotNull(outcome.bot) to assertNotNull(outcome.token)
        }
    }

    private fun newFixture(): Pair<Fixture, LongArray> {
        val dir = createTempDirectory("taga-bot-files").toFile()
        val clock = longArrayOf(System.currentTimeMillis())
        return Fixture(dir, { clock[0] }) to clock
    }

    private suspend fun HttpClient.upload(
        token: String,
        method: String,
        field: String,
        bytes: ByteArray,
        fileName: String,
        mime: String,
        chatId: String,
        caption: String = "",
    ) = post("/bot/v1/$method") {
        header("Authorization", "Bearer $token")
        setBody(
            MultiPartFormDataContent(
                formData {
                    append("chat_id", chatId)
                    if (caption.isNotBlank()) append("caption", caption)
                    append(
                        field,
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, mime)
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        },
                    )
                },
            ),
        )
    }

    @Test
    fun photoUploadGetFileAndSignedDownload() = testApplication {
        val (f, _) = newFixture()
        val alice = f.register("alice")
        val (bot, token) = f.createBot()
        f.service.ingestUserMessage("alice", bot, com.tagasay.server.bot.UserSendToBotRequest(text = "/start"))
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

        val upload = client.upload(token, "sendPhoto", "photo", png, "photo.png", "image/png", "u:alice", "подпись")
        assertEquals(HttpStatusCode.OK, upload.status, upload.bodyAsText())
        val body = Json.parseToJsonElement(upload.bodyAsText()).jsonObject
        val result = body["result"]!!.jsonObject
        val fileId = result["photo"]!!.jsonArray[0].jsonObject["file_id"]!!.jsonPrimitive.content
        assertTrue(result["caption"]!!.jsonPrimitive.content.contains("подпись"))

        val info = client.get("/bot/v1/getFile?file_id=$fileId") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, info.status)
        val infoBody = Json.parseToJsonElement(info.bodyAsText()).jsonObject["result"]!!.jsonObject
        assertEquals("image/png", infoBody["mime_type"]!!.jsonPrimitive.content)
        val url = infoBody["file_url"]!!.jsonPrimitive.content
        assertTrue(url.contains("expires="))
        assertTrue(url.contains("sig="))

        val download = client.get(url.removePrefix("https://relay.test"))
        assertEquals(HttpStatusCode.OK, download.status)
        assertContentEquals(png, download.bodyAsBytes())
        assertEquals("nosniff", download.headers["X-Content-Type-Options"])

        val bad = client.get(url.removePrefix("https://relay.test").replace("sig=", "sig=bad"))
        assertEquals(HttpStatusCode.Forbidden, bad.status)

        assertNotNull(alice)
    }

    @Test
    fun foreignFileIdExpiredAndTraversal() = testApplication {
        val (f, clock) = newFixture()
        val alice = f.register("alice")
        val (bot, token) = f.createBot("owner_one_bot")
        val (other, otherToken) = f.createBot("owner_two_bot")
        f.service.ingestUserMessage("alice", bot, com.tagasay.server.bot.UserSendToBotRequest(text = "/start"))
        f.service.ingestUserMessage("alice", other, com.tagasay.server.bot.UserSendToBotRequest(text = "/start"))
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

        val upload = client.upload(
            token,
            "sendDocument",
            "document",
            pdf,
            "../../etc/passwd",
            "application/pdf",
            "u:alice",
        )
        assertEquals(HttpStatusCode.OK, upload.status, upload.bodyAsText())
        val fileId = Json.parseToJsonElement(upload.bodyAsText()).jsonObject["result"]!!
            .jsonObject["document"]!!.jsonObject["file_id"]!!.jsonPrimitive.content

        // Чужой бот не видит file_id.
        val foreign = client.get("/bot/v1/getFile?file_id=$fileId") {
            header("Authorization", "Bearer $otherToken")
        }
        assertEquals(HttpStatusCode.NotFound, foreign.status)

        // Имя не используется как путь и не содержит traversal.
        val stored = assertNotNull(f.media.get(bot.id, fileId))
        assertFalse(stored.fileName.contains(".."))
        val path = assertNotNull(f.media.open(bot.id, fileId)).first.canonicalFile
        val root = File(f.dir, "bot-files").canonicalFile
        assertTrue(path.path.startsWith(root.path + File.separator))

        // Истёк TTL 30 дней.
        clock[0] += BotMediaStore.TTL_MS + 1_000
        val expired = client.get("/bot/v1/getFile?file_id=$fileId") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, expired.status)
        assertNotNull(alice)
    }

    @Test
    fun oversizedAndWrongMimeRejected() = testApplication {
        val (f, _) = newFixture()
        val alice = f.register("alice")
        val (bot, token) = f.createBot()
        f.service.ingestUserMessage("alice", bot, com.tagasay.server.bot.UserSendToBotRequest(text = "/start"))
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

        val oversized = ByteArray(5000).also {
            png.copyInto(it)
        }
        val tooBig = client.upload(token, "sendPhoto", "photo", oversized, "big.png", "image/png", "u:alice")
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)

        val wrongMime = client.upload(token, "sendPhoto", "photo", pdf, "fake.png", "image/png", "u:alice")
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongMime.status)

        val noStart = client.upload(token, "sendDocument", "document", pdf, "doc.pdf", "application/pdf", "u:bob")
        assertEquals(HttpStatusCode.NotFound, noStart.status)
        assertNotNull(alice)
    }
}
