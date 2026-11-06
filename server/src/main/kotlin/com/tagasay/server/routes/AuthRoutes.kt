package com.tagasay.server.routes

import com.tagasay.server.BuildInfo
import com.tagasay.server.DeviceCrypto
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.AuthedDevice
import com.tagasay.server.model.ChallengeRequest
import com.tagasay.server.model.ChallengeResponse
import com.tagasay.server.model.Device
import com.tagasay.server.model.ErrorResponse
import com.tagasay.server.model.RegisterRequest
import com.tagasay.server.model.RegisterResponse
import com.tagasay.server.model.UserProfile
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.authRoutes(store: InMemoryStore) {
    post("/v1/auth/challenge") {
        val request = call.receive<ChallengeRequest>()
        if (!DeviceCrypto.isValidBase64Key(request.deviceKey, 32)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid device key", "BAD_REQUEST"))
            return@post
        }
        val challenge = store.newChallenge(request.deviceKey)
        call.respond(
            ChallengeResponse(
                challenge = challenge,
                expiresAt = System.currentTimeMillis() + 5 * 60 * 1000,
            ),
        )
    }

    post("/v1/auth/register") {
        val request = call.receive<RegisterRequest>()
        val username = request.username.lowercase()

        if (!DeviceCrypto.isValidUsername(username)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid username", "BAD_REQUEST"))
            return@post
        }
        if (!DeviceCrypto.isValidBase64Key(request.identityKey, 32) ||
            !DeviceCrypto.isValidBase64Key(request.signedPrekey, 32) ||
            !DeviceCrypto.isValidBase64Key(request.signedPrekeySignature, 64)
        ) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid keys", "BAD_REQUEST"))
            return@post
        }
        if (!store.consumeChallenge(request.identityKey, request.challenge)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("challenge expired", "UNAUTHORIZED"))
            return@post
        }
        if (!DeviceCrypto.verify(
                request.identityKey,
                request.challenge.toByteArray(),
                request.challengeSignature,
            )
        ) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("bad signature", "UNAUTHORIZED"))
            return@post
        }

        val device = Device(
            deviceId = request.deviceId,
            username = username,
            identityKey = request.identityKey,
            signedPrekey = request.signedPrekey,
            signedPrekeySignature = request.signedPrekeySignature,
            oneTimePrekeys = request.oneTimePrekeys,
            createdAt = System.currentTimeMillis(),
        )
        val profile = UserProfile(
            username = username,
            displayName = request.displayName.take(64),
            identityKey = request.identityKey,
        )
        val (access, delivery) = store.register(device, profile, request.phoneHash)
        call.respond(
            RegisterResponse(
                accessToken = access,
                deliveryToken = delivery,
                serverSalt = BuildInfo.SERVER_SALT,
                version = BuildInfo.VERSION,
            ),
        )
    }

    post("/v1/auth/refresh") {
        val authed = call.requireDevice(store) ?: return@post
        val delivery = store.rotateDeliveryToken(authed.device.deviceId)
            ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("unknown delivery token", "UNAUTHORIZED"),
            )
        call.respond(mapOf("deliveryToken" to delivery))
    }
}

/**
 * Проверка bearer-токена. Возвращает null и уже отправил 401, если токен неверный.
 */
suspend fun ApplicationCall.requireDevice(store: InMemoryStore): AuthedDevice? {
    val header = request.headers[HttpHeaders.Authorization]
        ?: run {
            respond(HttpStatusCode.Unauthorized, ErrorResponse("missing token", "UNAUTHORIZED"))
            return null
        }
    val token = header.removePrefix("Bearer ").trim()
    val device = store.deviceByToken(token)
    if (device == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid token", "UNAUTHORIZED"))
        return null
    }
    return AuthedDevice(device, token)
}
