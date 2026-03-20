package dev.bmcreations.blip.server.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String = "ok")

fun Route.healthRoutes() {
    get("/health") {
        call.respond(HealthResponse())
    }
}
