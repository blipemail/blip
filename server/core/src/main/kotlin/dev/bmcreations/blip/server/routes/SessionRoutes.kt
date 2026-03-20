package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.CreateSessionResponse
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoValue
import dev.bmcreations.blip.server.services.SessionService
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.sessionCreateRoutes(sessionService: SessionService) {
    route("/v1/sessions") {
        post {
            val clientIp = call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                ?: call.request.origin.remoteAddress
            val session = sessionService.createSession(clientIp)
            call.respond(CreateSessionResponse(token = session.token, session = session))
        }
    }
}

fun Route.sessionMeRoutes(sessionService: SessionService, turso: TursoClient) {
    route("/v1/sessions") {
        get("/me") {
            val session = sessionService.extractSession(call.request.headers["Authorization"])
            val userId = session.userId
            val enriched = if (userId != null) {
                val row = turso.execute(
                    "SELECT has_pro, has_agent, stripe_customer_id FROM users WHERE id = ?",
                    listOf(TursoValue.Text(userId))
                ).firstOrNull()
                session.copy(
                    hasPro = row?.get("has_pro")?.toIntOrNull() == 1,
                    hasAgent = row?.get("has_agent")?.toIntOrNull() == 1,
                    stripeCustomerId = row?.get("stripe_customer_id"),
                )
            } else {
                session
            }
            call.respond(enriched)
        }
    }
}
