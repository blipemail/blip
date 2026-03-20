package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.CreateSessionResponse
import dev.bmcreations.blip.server.services.SessionService
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.sessionRoutes(sessionService: SessionService) {
    route("/v1/sessions") {
        post {
            val session = sessionService.createSession()
            call.respond(CreateSessionResponse(token = session.token, session = session))
        }

        get("/me") {
            val session = sessionService.extractSession(call.request.headers["Authorization"])
            call.respond(session)
        }
    }
}
