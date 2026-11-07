package com.tagasay.server.routes

import com.tagasay.server.RateLimits
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.data.MediaStore
import com.tagasay.server.model.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class MediaUploadResponse(
    val id: String,
    val sha256: String,
    val size: Long,
    val expiresAt: Long,
)

/**
 * Транспорт шифрованных вложений.
 *
 * Клиент сам шифрует файл AES-256-GCM и загружает только ciphertext.
 * Ключ и метаданные файла идут отдельным E2EE-конвертом, поэтому relay
 * не может связать содержимое с отправителем или получателем.
 */
fun Route.mediaRoutes(store: InMemoryStore, media: MediaStore) {
    post("/v1/media") {
        call.requireDevice(store) ?: return@post
        if (!RateLimits.allow(call, "media-upload", limit = 20)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
            return@post
        }

        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > media.maxBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("media too large", "TOO_LARGE"))
            return@post
        }

        val bytes = call.receive<ByteArray>()
        if (bytes.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("empty media", "BAD_REQUEST"))
            return@post
        }
        if (bytes.size.toLong() > media.maxBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("media too large", "TOO_LARGE"))
            return@post
        }

        val digest = sha256Hex(bytes)
        val expected = call.request.headers["X-TAGa-Say-Sha256"]
        if (!expected.isNullOrBlank() && !expected.equals(digest, ignoreCase = true)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("sha256 mismatch", "BAD_REQUEST"))
            return@post
        }

        val meta = media.put(bytes, digest)
        call.respond(
            HttpStatusCode.Created,
            MediaUploadResponse(meta.id, meta.sha256, meta.size, meta.expiresAt),
        )
    }

    get("/v1/media/{id}") {
        call.requireDevice(store) ?: return@get
        if (!RateLimits.allow(call, "media-download", limit = 120)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
            return@get
        }
        val id = call.parameters["id"].orEmpty()
        val found = media.get(id)
        if (found == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("media not found", "NOT_FOUND"))
            return@get
        }
        val (meta, bytes) = found
        call.response.header("X-TAGa-Say-Sha256", meta.sha256)
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }

    delete("/v1/media/{id}") {
        call.requireDevice(store) ?: return@delete
        val id = call.parameters["id"].orEmpty()
        if (!media.delete(id)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("media not found", "NOT_FOUND"))
            return@delete
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
