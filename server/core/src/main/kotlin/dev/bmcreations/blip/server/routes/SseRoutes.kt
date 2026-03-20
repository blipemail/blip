package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.server.services.InboxService
import dev.bmcreations.blip.server.services.SessionService
import dev.bmcreations.blip.server.sse.SseManager
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.sseRoutes(
    sseManager: SseManager,
    sessionService: SessionService,
    inboxService: InboxService,
) {
    get("/v1/inboxes/{id}/sse") {
        // Support token via query param for EventSource (can't set headers)
        val token = call.request.queryParameters["token"]
            ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")

        val session = sessionService.getSessionByToken(token ?: "")
        val inboxId = call.parameters["id"]!!

        // Verify ownership
        require(inboxService.ownsInbox(inboxId, session.id, session.userId)) { "Access denied" }

        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            writeStringUtf8("event: connected\ndata: {}\n\n")
            flush()

            sseManager.subscribe(inboxId).collect { email ->
                val data = Json.encodeToString(email)
                writeStringUtf8("event: email\ndata: $data\n\n")
                flush()
            }
        }
    }
}
