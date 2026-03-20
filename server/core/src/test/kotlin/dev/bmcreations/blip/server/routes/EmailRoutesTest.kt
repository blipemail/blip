package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.*
import dev.bmcreations.blip.server.*
import dev.bmcreations.blip.server.services.EmailService
import dev.bmcreations.blip.server.services.InboxService
import dev.bmcreations.blip.server.services.SessionService
import dev.bmcreations.blip.server.services.WebhookService
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

class EmailRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val workerSecret = "test-worker-secret"

    private val testSession = Session(
        id = "s1",
        token = "test-token",
        tier = Tier.FREE,
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

    private val testEmailSummary = EmailSummary(
        id = "email-1",
        from = "sender@example.com",
        subject = "Test Subject",
        receivedAt = "2025-01-01T12:00:00Z",
        preview = "Hello world"
    )

    private val testEmailDetail = EmailDetail(
        id = "email-1",
        inboxId = "inbox-1",
        from = "sender@example.com",
        to = "swift-fox-42@useblip.email",
        subject = "Test Subject",
        textBody = "Hello world",
        htmlBody = "<p>Hello world</p>",
        headers = mapOf("From" to "sender@example.com"),
        receivedAt = "2025-01-01T12:00:00Z",
        attachments = emptyList()
    )

    private val ingressRequest = """
        {
            "from": "sender@example.com",
            "to": "swift-fox-42@useblip.email",
            "subject": "Test Subject",
            "textBody": "Hello world",
            "htmlBody": "<p>Hello world</p>",
            "headers": {},
            "attachments": []
        }
    """.trimIndent()

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

    // --- Email ingress (POST /v1/inboxes/{address}/emails) ---

    @Test
    fun `POST v1 inboxes address emails with valid worker secret returns 201`() = testApplication {
        val emailService = mockk<EmailService>(relaxed = true)
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { inboxService.getInboxByAddress("swift-fox-42@useblip.email") } returns testInbox
        every { inboxService.isSniperWindowOpen(any()) } returns true
        coEvery { inboxService.getSessionIdForInbox("inbox-1") } returns "s1"
        coEvery { sessionService.getSessionById("s1") } returns testSession
        coEvery { emailService.ingestEmail("inbox-1", any(), any()) } returns testEmailSummary

        setup {
            emailRoutes(emailService, sessionService, inboxService, workerSecret)
        }

        val response = client.post("/v1/inboxes/swift-fox-42@useblip.email/emails") {
            header("X-Worker-Secret", workerSecret)
            contentType(ContentType.Application.Json)
            setBody(ingressRequest)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"email-1\""), "Response should contain email id")
        assertTrue(body.contains("\"Test Subject\""), "Response should contain email subject")
        assertTrue(body.contains("\"sender@example.com\""), "Response should contain from address")
    }

    @Test
    fun `POST v1 inboxes address emails without worker secret returns 403`() = testApplication {
        val emailService = mockk<EmailService>(relaxed = true)
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        setup {
            emailRoutes(emailService, sessionService, inboxService, workerSecret)
        }

        val response = client.post("/v1/inboxes/swift-fox-42@useblip.email/emails") {
            contentType(ContentType.Application.Json)
            setBody(ingressRequest)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"forbidden\""), "Response should contain forbidden error")
        assertTrue(body.contains("Missing worker secret"), "Response should mention missing secret")
    }

    @Test
    fun `POST v1 inboxes address emails with wrong secret returns 403`() = testApplication {
        val emailService = mockk<EmailService>(relaxed = true)
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        setup {
            emailRoutes(emailService, sessionService, inboxService, workerSecret)
        }

        val response = client.post("/v1/inboxes/swift-fox-42@useblip.email/emails") {
            header("X-Worker-Secret", "wrong-secret")
            contentType(ContentType.Application.Json)
            setBody(ingressRequest)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"forbidden\""), "Response should contain forbidden error")
        assertTrue(body.contains("Invalid worker secret"), "Response should mention invalid secret")
    }

    @Test
    fun `POST v1 inboxes address emails for missing inbox returns 404`() = testApplication {
        val emailService = mockk<EmailService>(relaxed = true)
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { inboxService.getInboxByAddress("nonexistent@useblip.email") } returns null

        setup {
            emailRoutes(emailService, sessionService, inboxService, workerSecret)
        }

        val response = client.post("/v1/inboxes/nonexistent@useblip.email/emails") {
            header("X-Worker-Secret", workerSecret)
            contentType(ContentType.Application.Json)
            setBody(ingressRequest)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"not_found\""), "Response should contain not_found error")
        assertTrue(body.contains("Inbox not found"), "Response should mention inbox not found")
    }

    // --- Email detail (GET /v1/emails/{id}) ---

    @Test
    fun `GET v1 emails id returns email detail`() = testApplication {
        val emailService = mockk<EmailService>(relaxed = true)
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession
        coEvery { emailService.getEmailInboxId("email-1") } returns "inbox-1"
        coEvery { inboxService.ownsInbox("inbox-1", "s1") } returns true
        coEvery { emailService.getEmail("email-1") } returns testEmailDetail

        setup {
            emailRoutes(emailService, sessionService, inboxService, workerSecret)
        }

        val response = client.get("/v1/emails/email-1") {
            header("Authorization", "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"email-1\""), "Response should contain email id")
        assertTrue(body.contains("\"inbox-1\""), "Response should contain inbox id")
        assertTrue(body.contains("\"Test Subject\""), "Response should contain subject")
        assertTrue(body.contains("\"Hello world\""), "Response should contain text body")
        assertTrue(body.contains("\"sender@example.com\""), "Response should contain from address")
    }

    @Test
    fun `GET v1 emails id returns 401 without auth`() = testApplication {
        val emailService = mockk<EmailService>(relaxed = true)
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession(null) } throws UnauthorizedException("Missing authorization header")

        setup {
            emailRoutes(emailService, sessionService, inboxService, workerSecret)
        }

        val response = client.get("/v1/emails/email-1")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"unauthorized\""), "Response should contain unauthorized error")
    }

    @Test
    fun `GET v1 emails id returns 404 for missing email`() = testApplication {
        val emailService = mockk<EmailService>(relaxed = true)
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)

        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession
        coEvery { emailService.getEmailInboxId("missing-email") } throws NotFoundException("Email not found")

        setup {
            emailRoutes(emailService, sessionService, inboxService, workerSecret)
        }

        val response = client.get("/v1/emails/missing-email") {
            header("Authorization", "Bearer test-token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"not_found\""), "Response should contain not_found error")
        assertTrue(body.contains("Email not found"), "Response should mention email not found")
    }

    // --- Webhook delivery on ingress ---

    @Test
    fun `POST v1 inboxes address emails triggers webhook delivery`() = testApplication {
        val emailService = mockk<EmailService>(relaxed = true)
        val sessionService = mockk<SessionService>(relaxed = true)
        val inboxService = mockk<InboxService>(relaxed = true)
        val webhookService = mockk<WebhookService>(relaxed = true)

        coEvery { inboxService.getInboxByAddress("swift-fox-42@useblip.email") } returns testInbox
        every { inboxService.isSniperWindowOpen(any()) } returns true
        coEvery { inboxService.getSessionIdForInbox("inbox-1") } returns "s1"
        coEvery { sessionService.getSessionById("s1") } returns testSession
        coEvery { emailService.ingestEmail("inbox-1", any(), any()) } returns testEmailSummary

        setup {
            emailRoutes(emailService, sessionService, inboxService, workerSecret, webhookService)
        }

        val response = client.post("/v1/inboxes/swift-fox-42@useblip.email/emails") {
            header("X-Worker-Secret", workerSecret)
            contentType(ContentType.Application.Json)
            setBody(ingressRequest)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        coVerify {
            webhookService.deliverWebhooks("inbox-1", "s1", "email-1", "swift-fox-42@useblip.email", any())
        }
    }
}
