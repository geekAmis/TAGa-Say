package com.tagasay.server.routes

import com.tagasay.server.RateLimits
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.DiscoveryRequest
import com.tagasay.server.model.DiscoveryResponse
import com.tagasay.server.model.ErrorResponse
import com.tagasay.server.model.KeyBundleResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.directoryRoutes(store: InMemoryStore) {
    get("/v1/users/{username}") {
        if (!RateLimits.allow(call, "directory-user", limit = 60)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
            return@get
        }
        val username = call.parameters["username"].orEmpty().trimStart('@').lowercase()
        val profile = store.user(username)
        if (profile == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found", "NOT_FOUND"))
        } else {
            call.respond(profile)
        }
    }

    post("/v1/discovery/phones") {
        call.requireDevice(store) ?: return@post
        val request = call.receive<DiscoveryRequest>()
        if (request.hashes.size > 10_000) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too many hashes", "PAYLOAD_TOO_LARGE"))
            return@post
        }
        call.respond(DiscoveryResponse(store.usersByPhoneHashes(request.hashes)))
    }

    get("/v1/keys/{username}") {
        if (!RateLimits.allow(call, "directory-keys", limit = 120)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
            return@get
        }
        val username = call.parameters["username"].orEmpty().trimStart('@').lowercase()
        val device = store.deviceByUsername(username)
        if (device == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("keys not found", "NOT_FOUND"))
        } else {
            call.respond(
                KeyBundleResponse(
                    username = device.username,
                    registrationId = device.registrationId,
                    deviceId = device.signalDeviceId,
                    identityKey = device.identityKey,
                    signedPrekeyId = device.signedPrekeyId,
                    signedPrekey = device.signedPrekey,
                    signedPrekeySignature = device.signedPrekeySignature,
                    kyberPrekeyId = device.kyberPrekeyId,
                    kyberPrekey = device.kyberPrekey,
                    kyberPrekeySignature = device.kyberPrekeySignature,
                ),
            )
        }
    }

    get("/v1/server/info") {
        call.respond(
            mapOf(
                "version" to com.tagasay.server.BuildInfo.VERSION,
                "salt" to com.tagasay.server.BuildInfo.SERVER_SALT,
            ),
        )
    }
}
