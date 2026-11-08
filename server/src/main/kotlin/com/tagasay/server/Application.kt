package com.tagasay.server

import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.data.MediaStore
import com.tagasay.server.routes.authRoutes
import com.tagasay.server.routes.directoryRoutes
import com.tagasay.server.routes.envelopeRoutes
import com.tagasay.server.routes.healthRoutes
import com.tagasay.server.routes.mediaRoutes
import com.tagasay.server.routes.profileRoutes
import com.tagasay.server.routes.roomRoutes
import com.tagasay.server.routes.stateRoutes
import com.tagasay.server.routes.versionRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    val port = System.getenv("TAGASAY_PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module(
    store: InMemoryStore = InMemoryStore(System.getenv("TAGASAY_DATA")),
    media: MediaStore = MediaStore(MediaStore.defaultRoot()),
    aeza: AezaStatus = AezaStatus(),
    updateStore: UpdateStore = UpdateStore(),
) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }
    install(CallLogging) {
        // Не логируем пути: в них бывают @username и id медиа. Только ошибки.
        level = Level.WARN
        filter { call -> (call.response.status()?.value ?: 0) >= 400 }
        format { call -> "${call.request.local.method.value} -> ${call.response.status()?.value ?: 0}" }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "internal error")),
            )
        }
    }

    routing {
        healthRoutes()
        authRoutes(store)
        envelopeRoutes(store)
        directoryRoutes(store, aeza)
        mediaRoutes(store, media)
        profileRoutes(store)
        roomRoutes(store)
        stateRoutes(store)
        versionRoutes(updateStore)
    }
}
