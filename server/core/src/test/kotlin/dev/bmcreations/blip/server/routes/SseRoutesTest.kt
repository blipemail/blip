package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.ErrorResponse
import dev.bmcreations.blip.models.SessionDTO
import dev.bmcreations.blip.models.Tier
import dev.bmcreations.blip.server.services.InboxService
import dev.bmcreations.blip.server.services.SessionService
import dev.bmcreations.blip.server.sse.SseManager
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class SseRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val testSession = SessionDTO(
        id = "s1",
        token = "test-token",
        tier = Tier.FREE,
        expiresAt = "2099-01-01T00:00:00Z"
    )

    private fun ApplicationTestBuilder.setup(block: Route.() -> Unit) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", cause.message ?: ""))
            }
        }
        routing(block)
    }

    @Test
    fun `GET v1 inboxes id sse rejects non-owner with 400`() = testApplication {
        val sseManager = SseManager()
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.getSessionByToken("test-token") } returns testSession
        coEvery { inboxService.ownsInbox("inbox-1", "s1") } returns false

        setup {
            sseRoutes(sseManager, sessionService, inboxService)
        }

        val response = client.get("/v1/inboxes/inbox-1/sse?token=test-token")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET v1 inboxes id sse without token fails`() = testApplication {
        val sseManager = SseManager()
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.getSessionByToken("") } throws RuntimeException("Invalid token")

        setup {
            sseRoutes(sseManager, sessionService, inboxService)
        }

        val response = client.get("/v1/inboxes/inbox-1/sse")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }
}
