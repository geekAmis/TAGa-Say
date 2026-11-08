package com.tagasay.server.routes

import com.tagasay.server.RateLimits
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class ProfileUpdateRequest(
    val displayName: String = "",
    val bio: String = "",
)

/** Обновление публичного профиля (имя и «О себе»), видимого в directory. */
fun Route.profileRoutes(store: InMemoryStore) {
    post("/v1/profile") {
        val authed = call.requireDevice(store) ?: return@post
        if (!RateLimits.allow(call, "profile-update", limit = 30)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
            return@post
        }
        val request = call.receive<ProfileUpdateRequest>()
        val updated = store.updateUserProfile(
            username = authed.device.username,
            displayName = request.displayName,
            bio = request.bio,
        ) ?: run {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown user", "NOT_FOUND"))
            return@post
        }
        call.respond(updated)
    }
}
