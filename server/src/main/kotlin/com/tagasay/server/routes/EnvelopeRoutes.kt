package com.tagasay.server.routes

import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.AckRequest
import com.tagasay.server.model.Envelope
import com.tagasay.server.model.ErrorResponse
import com.tagasay.server.model.SendEnvelopeRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.Base64
import java.util.UUID

fun Route.envelopeRoutes(store: InMemoryStore) {
    post("/v1/envelope") {
        val authed = call.requireDevice(store) ?: return@post
        val request = call.receive<SendEnvelopeRequest>()

        if (request.recipient.isBlank() || request.ciphertext.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("empty envelope", "BAD_REQUEST"))
            return@post
        }
        if (request.sizeClass !in 0..7) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid size class", "BAD_REQUEST"))
            return@post
        }
        val ciphertext = runCatching { Base64.getDecoder().decode(request.ciphertext) }.getOrNull()
        if (ciphertext == null || ciphertext.size > MAX_ENVELOPE_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("envelope too large", "PAYLOAD_TOO_LARGE"))
            return@post
        }
        // MVP: адресация по @username или blinded delivery token. Позже — только token.
        val recipientToken = if (store.usernameByDeliveryToken(request.recipient) != null) {
            request.recipient
        } else {
            store.deviceByUsername(request.recipient.trimStart('@'))
                ?.let { store.deliveryTokenFor(it.deviceId) }
        }
        if (recipientToken == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown recipient", "NOT_FOUND"))
            return@post
        }

        val now = System.currentTimeMillis()
        val envelope = Envelope(
            id = request.id.ifBlank { UUID.randomUUID().toString() },
            sender = authed.device.username,
            ciphertext = request.ciphertext,
            sizeClass = request.sizeClass,
            createdAt = now,
            expiresAt = now + request.ttlSeconds.coerceIn(60, 30L * 24 * 60 * 60) * 1000,
            ackToken = request.ackToken,
        )
        store.putEnvelope(recipientToken, envelope)
        call.respond(HttpStatusCode.Accepted, mapOf("id" to envelope.id))
    }

    get("/v1/envelope") {
        val authed = call.requireDevice(store) ?: return@get
        val token = store.deliveryTokenFor(authed.device.deviceId)
            ?: return@get call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("no mailbox", "NOT_FOUND"),
            )
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100
        call.respond(store.envelopes(token, limit))
    }

    post("/v1/envelope/ack") {
        val authed = call.requireDevice(store) ?: return@post
        val token = store.deliveryTokenFor(authed.device.deviceId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("no mailbox", "NOT_FOUND"))
        val request = call.receive<AckRequest>()
        store.ack(token, request.ids.toSet())
        call.respond(mapOf("acked" to request.ids.size))
    }
}

private const val MAX_ENVELOPE_BYTES = 4 * 1024 * 1024
