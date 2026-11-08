package com.tagasay.server.routes

import com.tagasay.server.bot.AddRoomBotRequest
import com.tagasay.server.bot.BotService
import com.tagasay.server.bot.BotStore
import com.tagasay.server.bot.SetRoomBotRightsRequest
import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.ErrorResponse
import com.tagasay.server.model.Room
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Управление ботами в комнатах (группа/канал).
 *
 * Добавлять/удалять/менять права может только owner/admin комнаты. Клиентские
 * проверки не считаются истиной. Бот получает `my_chat_member`, а в канале
 * публикует только с правом `post_messages`.
 */
fun Route.roomBotRoutes(
    store: InMemoryStore,
    bots: BotStore,
    service: BotService,
) {
    get("/v1/rooms/{id}/bots") {
        val authed = call.requireDevice(store) ?: return@get
        val room = store.room(call.parameters["id"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("room not found", "NOT_FOUND"))
        if (!room.channel && !room.hasMember(authed.device.username)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("not a member", "FORBIDDEN"))
        }
        call.respond(room.bots)
    }

    post("/v1/rooms/{id}/bots") {
        val authed = call.requireDevice(store) ?: return@post
        val room = store.room(call.parameters["id"].orEmpty())
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("room not found", "NOT_FOUND"))
        if (!store.isRoomAdmin(room.id, authed.device.username)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("only owner/admin can add bots", "FORBIDDEN"))
        }
        val request = runCatching { call.receive<AddRoomBotRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid JSON", "BAD_REQUEST"))
        val bot = bots.botByUsername(request.username)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
        if (!room.channel && !bot.canJoinGroups) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bot does not allow groups", "FORBIDDEN"))
        }
        if (room.bots.any { it.username.equals(bot.username, true) }) {
            return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("bot is already in this room", "CONFLICT"))
        }
        val updated = store.addRoomBot(room.id, authed.device.username, bot.username, request.rights)
            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("not allowed", "FORBIDDEN"))
        service.onRoomBotAdded(bot, updated, authed.device.username)
        call.respond(updated)
    }

    delete("/v1/rooms/{id}/bots/{username}") {
        val authed = call.requireDevice(store) ?: return@delete
        val room = store.room(call.parameters["id"].orEmpty())
            ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("room not found", "NOT_FOUND"))
        if (!store.isRoomAdmin(room.id, authed.device.username)) {
            return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("only owner/admin can remove bots", "FORBIDDEN"))
        }
        val username = call.parameters["username"].orEmpty()
        val bot = bots.botByUsername(username)
        val updated = store.removeRoomBot(room.id, authed.device.username, username)
            ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("bot is not in this room", "NOT_FOUND"))
        if (bot != null) service.onRoomBotRemoved(bot, updated, authed.device.username)
        call.respond(updated)
    }

    post("/v1/rooms/{id}/bots/{username}/rights") {
        val authed = call.requireDevice(store) ?: return@post
        val room = store.room(call.parameters["id"].orEmpty())
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("room not found", "NOT_FOUND"))
        if (!store.isRoomAdmin(room.id, authed.device.username)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("only owner/admin can change rights", "FORBIDDEN"))
        }
        val username = call.parameters["username"].orEmpty()
        val bot = bots.botByUsername(username)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found", "NOT_FOUND"))
        val request = runCatching { call.receive<SetRoomBotRightsRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid JSON", "BAD_REQUEST"))
        val updated = store.setRoomBotRights(room.id, authed.device.username, bot.username, request.rights)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot is not in this room", "NOT_FOUND"))
        service.onRoomBotRightsChanged(bot, updated, authed.device.username, request.rights)
        call.respond(updated)
    }
}

private fun Room.hasMember(username: String): Boolean =
    owner.equals(username, true) || members.any { it.equals(username, true) }
