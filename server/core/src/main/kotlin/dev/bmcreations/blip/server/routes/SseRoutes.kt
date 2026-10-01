package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.EmailSummary
import dev.bmcreations.blip.server.UnauthorizedException
import dev.bmcreations.blip.server.services.InboxService
import dev.bmcreations.blip.server.services.SessionService
import dev.bmcreations.blip.server.sse.SseManager
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SSE_HEARTBEAT_MILLIS = 25_000L

private sealed interface SseEvent {
    data class Email(val summary: EmailSummary) : SseEvent
    data object Heartbeat : SseEvent
}

fun Route.sseRoutes(
    sseManager: SseManager,
    sessionService: SessionService,
    inboxService: InboxService,
    heartbeatMillis: Long = SSE_HEARTBEAT_MILLIS,
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

            val events = sseManager.subscribe(inboxId).map<EmailSummary, SseEvent> { SseEvent.Email(it) }
            val heartbeats = flow {
                while (true) {
                    delay(heartbeatMillis)
                    emit(SseEvent.Heartbeat)
                }
            }

            // A single collector writes both streams, so writes never interleave.
            merge(events, heartbeats).collect { event ->
                when (event) {
                    is SseEvent.Email -> {
                        val data = Json.encodeToString(event.summary)
                        writeStringUtf8("event: email\ndata: $data\n\n")
                    }
                    SseEvent.Heartbeat -> {
                        // Re-check expiry so an expired session doesn't keep a stream open.
                        // Failures other than an auth rejection (e.g. a transient DB error) keep the stream.
                        val stillValid = try {
                            sessionService.getSessionByToken(token ?: "")
                            true
                        } catch (_: UnauthorizedException) {
                            false
                        } catch (_: Exception) {
                            true
                        }
                        if (!stillValid) throw CancellationException("Session expired")
                        // Comment line keeps idle connections open through proxies.
                        writeStringUtf8(": keepalive\n\n")
                    }
                }
                flush()
            }
        }
    }
}
