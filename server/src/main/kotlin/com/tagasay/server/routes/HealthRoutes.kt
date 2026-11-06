package com.tagasay.server.routes

import com.tagasay.server.BuildInfo
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val time: String,
    val protocol: String = "taga/1",
)

fun Route.healthRoutes() {
    get("/health") {
        call.respond(
            HealthResponse(
                status = "ok",
                version = BuildInfo.VERSION,
                time = Instant.now().toString(),
            ),
        )
    }
    get("/") {
        call.respond(mapOf("name" to "TAGa-Say relay", "version" to BuildInfo.VERSION))
    }
}
