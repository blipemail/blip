package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.CreateInboxRequest
import dev.bmcreations.blip.models.CreateInboxResponse
import dev.bmcreations.blip.server.services.InboxService
import dev.bmcreations.blip.server.services.SessionService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.inboxRoutes(inboxService: InboxService, sessionService: SessionService) {
    route("/v1/inboxes") {
        post {
            val session = sessionService.extractSession(call.request.headers["Authorization"])
            val request = try {
                call.receive<CreateInboxRequest>()
            } catch (_: Exception) {
                CreateInboxRequest()
            }
            val inbox = inboxService.createInbox(session.id, session.tier, request, session.userId)
            call.respond(HttpStatusCode.Created, CreateInboxResponse(inbox))
        }

        get {
            val session = sessionService.extractSession(call.request.headers["Authorization"])
            val inboxes = inboxService.listInboxes(session.id, session.userId)
            call.respond(inboxes)
        }

        get("/{id}") {
            val session = sessionService.extractSession(call.request.headers["Authorization"])
            val id = call.parameters["id"]!!
            val detail = inboxService.getInbox(id, session.id, session.userId)
            call.respond(detail)
        }

        delete("/{id}") {
            val session = sessionService.extractSession(call.request.headers["Authorization"])
            val id = call.parameters["id"]!!
            inboxService.deleteInbox(id, session.id, session.userId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
