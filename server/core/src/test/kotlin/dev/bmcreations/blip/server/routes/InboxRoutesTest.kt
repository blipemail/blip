package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.*
import dev.bmcreations.blip.server.*
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

class InboxRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val testSession = Session(
        id = "s1",
        token = "test-token",
        tier = Tier.FREE,
        expiresAt = "2099-01-01T00:00:00Z"
    )

    private val proSession = Session(
        id = "s2",
        token = "pro-token",
        tier = Tier.PRO,
        expiresAt = "2099-01-01T00:00:00Z"
    )

    private val testInbox = Inbox(
        id = "inbox-1",
        address = "swift-fox-42@useblip.email",
        domain = "useblip.email",
        createdAt = "2025-01-01T00:00:00Z",
        expiresAt = "2099-01-01T00:00:00Z",
        emailCount = 0
    )

    private val testInboxDetail = InboxDetail(
        inbox = testInbox,
        emails = listOf(
            EmailSummary(
                id = "email-1",
                from = "sender@example.com",
                subject = "Hello",
                receivedAt = "2025-01-01T12:00:00Z",
                preview = "Hello world"
            )
        )
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

    @Test
    fun `POST v1 inboxes creates inbox with auth and returns 201`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession
        coEvery { inboxService.createInbox("s1", Tier.FREE, any()) } returns testInbox

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.post("/v1/inboxes") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"inbox\""), "Response should contain inbox object")
        assertTrue(body.contains("\"inbox-1\""), "Response should contain inbox id")
        assertTrue(body.contains("\"swift-fox-42@useblip.email\""), "Response should contain inbox address")
    }

    @Test
    fun `POST v1 inboxes returns 401 without auth`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        coEvery { sessionService.extractSession(null) } throws UnauthorizedException("Missing authorization header")

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.post("/v1/inboxes") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"unauthorized\""), "Response should contain unauthorized error")
    }

    @Test
    fun `POST v1 inboxes returns 402 when tier limit exceeded`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession
        coEvery { inboxService.createInbox("s1", Tier.FREE, any()) } throws TierLimitException("Inbox limit reached (3 for FREE tier)")

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.post("/v1/inboxes") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.PaymentRequired, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"tier_limit\""), "Response should contain tier_limit error")
        assertTrue(body.contains("Inbox limit reached"), "Response should contain limit message")
    }

    @Test
    fun `GET v1 inboxes returns list of inboxes`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession
        coEvery { inboxService.listInboxes("s1") } returns listOf(testInbox)

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.get("/v1/inboxes") {
            header("Authorization", "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"inbox-1\""), "Response should contain inbox id")
        assertTrue(body.contains("\"swift-fox-42@useblip.email\""), "Response should contain inbox address")
    }

    @Test
    fun `GET v1 inboxes returns 401 without auth`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        coEvery { sessionService.extractSession(null) } throws UnauthorizedException("Missing authorization header")

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.get("/v1/inboxes")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET v1 inboxes id returns inbox detail`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession
        coEvery { inboxService.getInbox("inbox-1", "s1") } returns testInboxDetail

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.get("/v1/inboxes/inbox-1") {
            header("Authorization", "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"inbox\""), "Response should contain inbox object")
        assertTrue(body.contains("\"emails\""), "Response should contain emails list")
        assertTrue(body.contains("\"email-1\""), "Response should contain email id")
        assertTrue(body.contains("\"Hello\""), "Response should contain email subject")
    }

    @Test
    fun `GET v1 inboxes id returns 404 for missing inbox`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession
        coEvery { inboxService.getInbox("missing-id", "s1") } throws NotFoundException("Inbox not found")

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.get("/v1/inboxes/missing-id") {
            header("Authorization", "Bearer test-token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"not_found\""), "Response should contain not_found error")
    }

    @Test
    fun `GET v1 inboxes id returns 403 for wrong session`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession
        coEvery { inboxService.getInbox("inbox-1", "s1") } throws ForbiddenException("Access denied")

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.get("/v1/inboxes/inbox-1") {
            header("Authorization", "Bearer test-token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"forbidden\""), "Response should contain forbidden error")
    }

    @Test
    fun `DELETE v1 inboxes id returns 204`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession
        coJustRun { inboxService.deleteInbox("inbox-1", "s1") }

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.delete("/v1/inboxes/inbox-1") {
            header("Authorization", "Bearer test-token")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify { inboxService.deleteInbox("inbox-1", "s1") }
    }

    @Test
    fun `DELETE v1 inboxes id returns 404 for missing inbox`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession
        coEvery { inboxService.deleteInbox("missing-id", "s1") } throws NotFoundException("Inbox not found")

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.delete("/v1/inboxes/missing-id") {
            header("Authorization", "Bearer test-token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"not_found\""), "Response should contain not_found error")
    }

    // --- TDD tests for PRO features ---

    @Test
    fun `POST v1 inboxes with custom slug returns 201 for PRO`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        val customInbox = testInbox.copy(
            id = "inbox-custom",
            address = "my-custom-slug@useblip.email"
        )
        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { inboxService.createInbox("s2", Tier.PRO, any()) } returns customInbox

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.post("/v1/inboxes") {
            header("Authorization", "Bearer pro-token")
            contentType(ContentType.Application.Json)
            setBody("""{"slug": "my-custom-slug"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"inbox-custom\""), "Response should contain custom inbox id")
        assertTrue(body.contains("\"my-custom-slug@useblip.email\""), "Response should contain custom address")
    }

    @Test
    fun `POST v1 inboxes with sniper window returns 201 for PRO`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        val sniperInbox = testInbox.copy(
            id = "inbox-sniper",
            sniperWindow = SniperWindow(
                opensAt = "2025-01-01T00:00:00Z",
                closesAt = "2025-01-01T00:30:00Z",
                sealed = false
            )
        )
        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { inboxService.createInbox("s2", Tier.PRO, any()) } returns sniperInbox

        setup {
            inboxRoutes(inboxService, sessionService)
        }

        val response = client.post("/v1/inboxes") {
            header("Authorization", "Bearer pro-token")
            contentType(ContentType.Application.Json)
            setBody("""{"windowMinutes": 30}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"inbox-sniper\""), "Response should contain sniper inbox id")
        assertTrue(body.contains("\"sniperWindow\""), "Response should contain sniperWindow field")
        assertTrue(body.contains("\"opensAt\""), "Response should contain opensAt field")
        assertTrue(body.contains("\"closesAt\""), "Response should contain closesAt field")
    }
}
