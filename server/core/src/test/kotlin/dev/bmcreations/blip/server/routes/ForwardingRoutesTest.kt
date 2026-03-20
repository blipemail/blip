package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.*
import dev.bmcreations.blip.server.*
import dev.bmcreations.blip.server.services.ForwardingService
import dev.bmcreations.blip.server.services.InboxService
import dev.bmcreations.blip.server.services.SessionService
import io.ktor.client.request.*
import io.ktor.client.statement.*
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

class ForwardingRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val freeSession = Session(
        id = "s1",
        token = "free-token",
        tier = Tier.FREE,
        expiresAt = "2099-01-01T00:00:00Z"
    )

    private val proSession = Session(
        id = "s2",
        token = "pro-token",
        tier = Tier.PRO,
        expiresAt = "2099-01-01T00:00:00Z"
    )

    private val testRule = ForwardingRule(
        id = "rule-1",
        inboxId = "inbox-1",
        forwardToEmail = "forward@example.com",
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun ApplicationTestBuilder.setup(block: Route.() -> Unit) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", cause.message ?: ""))
            }
            exception<NotFoundException> { call, cause ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", cause.message ?: ""))
            }
            exception<ForbiddenException> { call, cause ->
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", cause.message ?: ""))
            }
            exception<UnauthorizedException> { call, cause ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", cause.message ?: ""))
            }
            exception<TierLimitException> { call, cause ->
                call.respond(HttpStatusCode.PaymentRequired, ErrorResponse("tier_limit", cause.message ?: ""))
            }
        }
        routing(block)
    }

    // --- POST /v1/inboxes/{id}/forwarding ---

    @Test
    fun `POST creates forwarding rule for PRO user`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val forwardingService = mockk<ForwardingService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { inboxService.ownsInbox("inbox-1", "s2", any()) } returns true
        coEvery { forwardingService.createRule("inbox-1", "s2", "forward@example.com", 1) } returns testRule

        setup { forwardingRoutes(forwardingService, sessionService, inboxService) }

        val response = client.post("/v1/inboxes/inbox-1/forwarding") {
            header("Authorization", "Bearer pro-token")
            contentType(ContentType.Application.Json)
            setBody("""{"forwardToEmail": "forward@example.com"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"rule-1\""), "Response should contain rule id")
        assertTrue(body.contains("\"forward@example.com\""), "Response should contain forward email")
    }

    @Test
    fun `POST returns 402 for FREE user`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val forwardingService = mockk<ForwardingService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession("Bearer free-token") } returns freeSession

        setup { forwardingRoutes(forwardingService, sessionService, inboxService) }

        val response = client.post("/v1/inboxes/inbox-1/forwarding") {
            header("Authorization", "Bearer free-token")
            contentType(ContentType.Application.Json)
            setBody("""{"forwardToEmail": "forward@example.com"}""")
        }

        assertEquals(HttpStatusCode.PaymentRequired, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"tier_limit\""), "Response should contain tier_limit error")
    }

    @Test
    fun `POST returns 401 without auth`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val forwardingService = mockk<ForwardingService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession(null) } throws UnauthorizedException("Missing authorization header")

        setup { forwardingRoutes(forwardingService, sessionService, inboxService) }

        val response = client.post("/v1/inboxes/inbox-1/forwarding") {
            contentType(ContentType.Application.Json)
            setBody("""{"forwardToEmail": "forward@example.com"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST returns 400 for non-owned inbox`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val forwardingService = mockk<ForwardingService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { inboxService.ownsInbox("inbox-1", "s2", any()) } returns false

        setup { forwardingRoutes(forwardingService, sessionService, inboxService) }

        val response = client.post("/v1/inboxes/inbox-1/forwarding") {
            header("Authorization", "Bearer pro-token")
            contentType(ContentType.Application.Json)
            setBody("""{"forwardToEmail": "forward@example.com"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // --- GET /v1/inboxes/{id}/forwarding ---

    @Test
    fun `GET returns forwarding rules list`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val forwardingService = mockk<ForwardingService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { inboxService.ownsInbox("inbox-1", "s2", any()) } returns true
        coEvery { forwardingService.listRules("inbox-1") } returns listOf(testRule)

        setup { forwardingRoutes(forwardingService, sessionService, inboxService) }

        val response = client.get("/v1/inboxes/inbox-1/forwarding") {
            header("Authorization", "Bearer pro-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"rule-1\""), "Response should contain rule id")
        assertTrue(body.contains("\"forward@example.com\""), "Response should contain forward email")
    }

    @Test
    fun `GET returns empty list when no rules`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val forwardingService = mockk<ForwardingService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { inboxService.ownsInbox("inbox-1", "s2", any()) } returns true
        coEvery { forwardingService.listRules("inbox-1") } returns emptyList()

        setup { forwardingRoutes(forwardingService, sessionService, inboxService) }

        val response = client.get("/v1/inboxes/inbox-1/forwarding") {
            header("Authorization", "Bearer pro-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("[]"), "Response should be empty array")
    }

    // --- DELETE /v1/forwarding/{id} ---

    @Test
    fun `DELETE removes forwarding rule and returns 204`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val forwardingService = mockk<ForwardingService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coJustRun { forwardingService.deleteRule("rule-1", "s2") }

        setup { forwardingRoutes(forwardingService, sessionService, inboxService) }

        val response = client.delete("/v1/forwarding/rule-1") {
            header("Authorization", "Bearer pro-token")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify { forwardingService.deleteRule("rule-1", "s2") }
    }

    @Test
    fun `DELETE returns 404 for missing rule`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val forwardingService = mockk<ForwardingService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { forwardingService.deleteRule("rule-missing", "s2") } throws NotFoundException("Forwarding rule not found")

        setup { forwardingRoutes(forwardingService, sessionService, inboxService) }

        val response = client.delete("/v1/forwarding/rule-missing") {
            header("Authorization", "Bearer pro-token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE returns 403 for non-owner`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val forwardingService = mockk<ForwardingService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { forwardingService.deleteRule("rule-1", "s2") } throws ForbiddenException("Access denied")

        setup { forwardingRoutes(forwardingService, sessionService, inboxService) }

        val response = client.delete("/v1/forwarding/rule-1") {
            header("Authorization", "Bearer pro-token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
