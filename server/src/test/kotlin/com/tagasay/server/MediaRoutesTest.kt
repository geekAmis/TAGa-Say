package com.tagasay.server

import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.data.MediaStore
import com.tagasay.server.model.Device
import com.tagasay.server.model.UserProfile
import com.tagasay.server.routes.MediaUploadResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MediaRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun mediaUploadAndDownloadRoundTrip() = testApplication {
        val dir = createTempDirectory("taga-media").toFile()
        val store = InMemoryStore()
        val media = MediaStore(dir.path)
        val token = registerDevice(store, "alice")
        application { module(store, media) }

        val payload = ByteArray(16 * 1024) { (it % 251).toByte() }
        val digest = sha256Hex(payload)

        val upload = client.post("/v1/media") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-TAGa-Say-Sha256", digest)
            contentType(ContentType.Application.OctetStream)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Created, upload.status)
        val dto = json.decodeFromString(MediaUploadResponse.serializer(), upload.bodyAsText())
        assertEquals(payload.size.toLong(), dto.size)
        assertEquals(digest, dto.sha256)

        val download = client.get("/v1/media/${dto.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals(digest, download.headers["X-TAGa-Say-Sha256"])
        assertContentEquals(payload, download.bodyAsBytes())

        dir.deleteRecursively()
    }

    @Test
    fun mediaRequiresAuth() = testApplication {
        val dir = createTempDirectory("taga-media").toFile()
        application { module(InMemoryStore(), MediaStore(dir.path)) }

        val response = client.post("/v1/media") {
            contentType(ContentType.Application.OctetStream)
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)

        dir.deleteRecursively()
    }

    @Test
    fun mediaRejectsSha256Mismatch() = testApplication {
        val dir = createTempDirectory("taga-media").toFile()
        val store = InMemoryStore()
        val token = registerDevice(store, "bob")
        application { module(store, MediaStore(dir.path)) }

        val response = client.post("/v1/media") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-TAGa-Say-Sha256", "00")
            contentType(ContentType.Application.OctetStream)
            setBody(byteArrayOf(9, 9, 9))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)

        dir.deleteRecursively()
    }

    private suspend fun registerDevice(store: InMemoryStore, username: String): String {
        val now = System.currentTimeMillis()
        val device = Device(
            deviceId = "device-$username",
            username = username,
            identityKey = "identity-$username",
            signingKey = "signing-$username",
            signedPrekey = "prekey-$username",
            signedPrekeySignature = "signature-$username",
            oneTimePrekeys = emptyList(),
            createdAt = now,
        )
        return store.register(
            device,
            UserProfile(username, username, device.identityKey),
            null,
        ).first
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
