package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.server.db.TursoClient
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

fun Route.healthRoutes(turso: TursoClient) {
    // Liveness: no dependencies, so a database outage doesn't get the process restarted.
    get("/livez") {
        call.respond(HttpStatusCode.OK, HealthResponse("ok"))
    }

    // Readiness: one trivial query. Public, so it returns no counts or timings.
    get("/health") {
        val dbConnected = try {
            turso.execute("SELECT 1", emptyList())
            true
        } catch (_: Exception) {
            false
        }
        if (dbConnected) {
            call.respond(HttpStatusCode.OK, HealthResponse("ok"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("degraded"))
        }
    }
}
