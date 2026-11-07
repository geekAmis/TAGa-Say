package com.tagasay.server.routes

import com.tagasay.server.BuildInfo
import com.tagasay.server.DeviceCrypto
import com.tagasay.server.Totp
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.AuthedDevice
import com.tagasay.server.model.ChallengeRequest
import com.tagasay.server.model.ChallengeResponse
import com.tagasay.server.model.Device
import com.tagasay.server.model.ErrorResponse
import com.tagasay.server.model.RegisterRequest
import com.tagasay.server.model.RegisterResponse
import com.tagasay.server.model.TwoFactorCodeRequest
import com.tagasay.server.model.TwoFactorSetupResponse
import com.tagasay.server.model.TwoFactorStatusResponse
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

        if (request.passwordVerifier.length < 32 || request.deviceHash.length < 32) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("password and device hash required", "BAD_REQUEST"))
            return@post
        }
        val existing = store.deviceByUsername(username)
        if (existing != null && !DeviceCrypto.constantTimeEquals(existing.passwordVerifier, request.passwordVerifier)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid credentials", "UNAUTHORIZED"))
            return@post
        }
        // 2FA требуется для нового устройства; то же устройство может
        // перерегистрироваться после рестарта сервера по паролю + device hash.
        if (existing?.totpEnabled == true &&
            existing.deviceHash != request.deviceHash &&
            !Totp.verify(existing.totpSecret, request.totpCode)
        ) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("2FA code required", "TWO_FACTOR_REQUIRED"))
            return@post
        }

        if (!DeviceCrypto.isValidUsername(username)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid username", "BAD_REQUEST"))
            return@post
        }
        val signingKey = request.signingKey.ifBlank { request.identityKey }
        // libsignal ECPublicKey/IdentityKey сериализуются с type-байтом (33 байта),
        // Ed25519 signing key — 32 байта.
        if (!DeviceCrypto.isValidSignalKey(request.identityKey) ||
            !DeviceCrypto.isValidBase64Key(signingKey, 32) ||
            !DeviceCrypto.isValidSignalKey(request.signedPrekey) ||
            !DeviceCrypto.isValidBase64Key(request.signedPrekeySignature, 64)
        ) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid keys", "BAD_REQUEST"))
            return@post
        }
        if (!store.consumeChallenge(signingKey, request.challenge)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("challenge expired", "UNAUTHORIZED"))
            return@post
        }
        if (!DeviceCrypto.verify(
                signingKey,
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
            signingKey = signingKey,
            signedPrekey = request.signedPrekey,
            signedPrekeySignature = request.signedPrekeySignature,
            signedPrekeyId = request.signedPrekeyId,
            registrationId = request.registrationId,
            signalDeviceId = request.signalDeviceId,
            kyberPrekey = request.kyberPrekey,
            kyberPrekeySignature = request.kyberPrekeySignature,
            kyberPrekeyId = request.kyberPrekeyId,
            passwordVerifier = request.passwordVerifier,
            deviceHash = request.deviceHash,
            totpSecret = existing?.totpSecret.orEmpty(),
            totpEnabled = existing?.totpEnabled ?: false,
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

    // ------------------------------------------------------------------- 2FA

    post("/v1/auth/2fa/setup") {
        val authed = call.requireDevice(store) ?: return@post
        val secret = Totp.randomSecret()
        store.updateDevice(authed.device.copy(totpSecret = secret, totpEnabled = false))
        call.respond(
            TwoFactorSetupResponse(
                secret = secret,
                otpauthUri = Totp.otpauthUri("TAGa-Say", authed.device.username, secret),
            ),
        )
    }

    post("/v1/auth/2fa/enable") {
        val authed = call.requireDevice(store) ?: return@post
        val request = call.receive<TwoFactorCodeRequest>()
        if (authed.device.totpSecret.isBlank() || !Totp.verify(authed.device.totpSecret, request.code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid 2FA code", "BAD_REQUEST"))
            return@post
        }
        store.updateDevice(authed.device.copy(totpEnabled = true))
        call.respond(TwoFactorStatusResponse(enabled = true))
    }

    post("/v1/auth/2fa/disable") {
        val authed = call.requireDevice(store) ?: return@post
        val request = call.receive<TwoFactorCodeRequest>()
        if (authed.device.totpEnabled && !Totp.verify(authed.device.totpSecret, request.code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid 2FA code", "BAD_REQUEST"))
            return@post
        }
        store.updateDevice(authed.device.copy(totpSecret = "", totpEnabled = false))
        call.respond(TwoFactorStatusResponse(enabled = false))
    }

    get("/v1/auth/2fa/status") {
        val authed = call.requireDevice(store) ?: return@get
        call.respond(TwoFactorStatusResponse(enabled = authed.device.totpEnabled))
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
