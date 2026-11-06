package com.tagasay.server

import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.routes.authRoutes
import com.tagasay.server.routes.directoryRoutes
import com.tagasay.server.routes.envelopeRoutes
import com.tagasay.server.routes.healthRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
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

fun Application.module(store: InMemoryStore = InMemoryStore()) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }
    install(CallLogging) {
        level = Level.INFO
    }
    install(CORS) {
        anyHost()
        allowHeader("Authorization")
        allowHeader("X-TAGa-Say-Signature")
        allowHeader("X-TAGa-Say-Timestamp")
        allowHeader("X-TAGa-Say-Nonce")
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
        directoryRoutes(store)
    }
}
