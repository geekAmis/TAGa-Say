package com.tagasay.server.routes

import com.tagasay.server.RateLimits
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.CreateRoomRequest
import com.tagasay.server.model.ErrorResponse
import com.tagasay.server.model.Room
import com.tagasay.server.model.RoomMemberRequest
import com.tagasay.server.model.UpdateRoomRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

/**
 * Группы и каналы. Сервер хранит только метаданные комнаты (название,
 * участники, роли). Содержимое сообщений остаётся E2EE: клиент шифрует
 * отдельный конверт каждому участнику.
 */
fun Route.roomRoutes(store: InMemoryStore) {
    post("/v1/rooms") {
        val authed = call.requireDevice(store) ?: return@post
        if (!RateLimits.allow(call, "room-create", limit = 20)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
            return@post
        }
        val request = call.receive<CreateRoomRequest>()
        val title = request.title.trim()
        if (title.length !in 1..80) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid title", "BAD_REQUEST"))
            return@post
        }
        val username = request.username
            .trimStart('@')
            .lowercase()
            .ifBlank { "room_${UUID.randomUUID().toString().take(8)}" }
        if (!username.matches(Regex("[a-z0-9_]{3,32}"))) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid username", "BAD_REQUEST"))
            return@post
        }
        val members = request.members
            .map { it.trimStart('@').lowercase() }
            .filter { it.isNotBlank() && store.deviceByUsername(it) != null }
            .distinct()
        val room = store.createRoom(
            Room(
                id = "room_${UUID.randomUUID()}",
                username = username,
                title = title,
                description = request.description.trim().take(300),
                channel = request.channel,
                owner = authed.device.username.lowercase(),
                admins = emptyList(),
                members = members,
            ),
        )
        if (room == null) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("username busy", "CONFLICT"))
            return@post
        }
        call.respond(HttpStatusCode.Created, room)
    }

    get("/v1/rooms") {
        val authed = call.requireDevice(store) ?: return@get
        if (!RateLimits.allow(call, "room-list", limit = 120)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
            return@get
        }
        val query = call.request.queryParameters["query"].orEmpty().trim()
        val rooms = if (query.isBlank()) {
            store.roomsFor(authed.device.username)
        } else {
            (store.roomsFor(authed.device.username) + store.searchRooms(query))
                .distinctBy { it.id }
                .take(50)
        }
        call.respond(rooms)
    }

    get("/v1/rooms/{id}") {
        val authed = call.requireDevice(store) ?: return@get
        val id = call.parameters["id"].orEmpty()
        val room = store.room(id)
        if (room == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("room not found", "NOT_FOUND"))
            return@get
        }
        if (!room.hasMember(authed.device.username) && !room.channel) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("not a member", "FORBIDDEN"))
            return@get
        }
        call.respond(room)
    }

    post("/v1/rooms/{id}/join") {
        val authed = call.requireDevice(store) ?: return@post
        if (!RateLimits.allow(call, "room-join", limit = 40)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited", "RATE_LIMITED"))
            return@post
        }
        val id = call.parameters["id"].orEmpty()
        val room = store.room(id)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("room not found", "NOT_FOUND"))
        if (!room.channel) {
            // Вступления в группы — только по явному добавлению админом.
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("invite required", "FORBIDDEN"))
            return@post
        }
        val updated = store.joinRoom(id, authed.device.username)
        call.respond(updated ?: room)
    }

    post("/v1/rooms/{id}/leave") {
        val authed = call.requireDevice(store) ?: return@post
        val id = call.parameters["id"].orEmpty()
        store.leaveRoom(id, authed.device.username)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/v1/rooms/{id}/members") {
        val authed = call.requireDevice(store) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<RoomMemberRequest>()
        val target = request.username.trimStart('@').lowercase()
        if (target.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("empty username", "BAD_REQUEST"))
            return@post
        }
        val updated = store.changeRoomMember(id, authed.device.username, target, request.remove)
        if (updated == null) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("not allowed", "FORBIDDEN"))
            return@post
        }
        call.respond(updated)
    }

    post("/v1/rooms/{id}/update") {
        val authed = call.requireDevice(store) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<UpdateRoomRequest>()
        val updated = store.updateRoom(
            id,
            authed.device.username,
            request.title,
            request.description,
        )
        if (updated == null) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("not allowed", "FORBIDDEN"))
            return@post
        }
        call.respond(updated)
    }
}

private fun Room.hasMember(username: String): Boolean =
    owner.equals(username, true) || members.any { it.equals(username, true) }
