package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.CreateSessionResponse
import dev.bmcreations.blip.server.services.SessionService
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.sessionRoutes(sessionService: SessionService) {
    route("/v1/sessions") {
        post {
            val clientIp = call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                ?: call.request.origin.remoteAddress
            val session = sessionService.createSession(clientIp)
            call.respond(CreateSessionResponse(token = session.token, session = session))
        }

        get("/me") {
            val session = sessionService.extractSession(call.request.headers["Authorization"])
            call.respond(session)
        }
    }
}
