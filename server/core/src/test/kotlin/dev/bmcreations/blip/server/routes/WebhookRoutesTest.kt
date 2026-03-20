package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.*
import dev.bmcreations.blip.server.*
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

class WebhookRoutesTest {

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

    private val testWebhook = Webhook(
        id = "wh-1",
        url = "https://example.com/hook",
        secret = "secret-abc",
        inboxId = null,
        createdAt = "2025-01-01T00:00:00Z",
        enabled = true,
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

    // --- POST /v1/webhooks ---

    @Test
    fun `POST v1 webhooks creates webhook for PRO user`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val webhookService = mockk<WebhookService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { webhookService.createWebhook("s2", any()) } returns testWebhook

        val inboxService = mockk<InboxService>(relaxed = true)
        setup { webhookRoutes(webhookService, sessionService, inboxService) }

        val response = client.post("/v1/webhooks") {
            header("Authorization", "Bearer pro-token")
            contentType(ContentType.Application.Json)
            setBody("""{"url": "https://example.com/hook"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"wh-1\""), "Response should contain webhook id")
        assertTrue(body.contains("\"https://example.com/hook\""), "Response should contain webhook url")
        assertTrue(body.contains("\"secret\""), "Response should contain secret field")
    }

    @Test
    fun `POST v1 webhooks returns 402 for FREE user`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val webhookService = mockk<WebhookService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer free-token") } returns freeSession

        val inboxService = mockk<InboxService>(relaxed = true)
        setup { webhookRoutes(webhookService, sessionService, inboxService) }

        val response = client.post("/v1/webhooks") {
            header("Authorization", "Bearer free-token")
            contentType(ContentType.Application.Json)
            setBody("""{"url": "https://example.com/hook"}""")
        }

        assertEquals(HttpStatusCode.PaymentRequired, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"tier_limit\""), "Response should contain tier_limit error")
    }

    @Test
    fun `POST v1 webhooks returns 401 without auth`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val webhookService = mockk<WebhookService>(relaxed = true)
        coEvery { sessionService.extractSession(null) } throws UnauthorizedException("Missing authorization header")

        val inboxService = mockk<InboxService>(relaxed = true)
        setup { webhookRoutes(webhookService, sessionService, inboxService) }

        val response = client.post("/v1/webhooks") {
            contentType(ContentType.Application.Json)
            setBody("""{"url": "https://example.com/hook"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // --- GET /v1/webhooks ---

    @Test
    fun `GET v1 webhooks returns list`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val webhookService = mockk<WebhookService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { webhookService.listWebhooks("s2") } returns listOf(testWebhook)

        val inboxService = mockk<InboxService>(relaxed = true)
        setup { webhookRoutes(webhookService, sessionService, inboxService) }

        val response = client.get("/v1/webhooks") {
            header("Authorization", "Bearer pro-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"wh-1\""), "Response should contain webhook id")
        assertTrue(body.contains("\"https://example.com/hook\""), "Response should contain webhook url")
    }

    // --- DELETE /v1/webhooks/{id} ---

    @Test
    fun `DELETE v1 webhooks id returns 204`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val webhookService = mockk<WebhookService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coJustRun { webhookService.deleteWebhook("wh-1", "s2") }

        val inboxService = mockk<InboxService>(relaxed = true)
        setup { webhookRoutes(webhookService, sessionService, inboxService) }

        val response = client.delete("/v1/webhooks/wh-1") {
            header("Authorization", "Bearer pro-token")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify { webhookService.deleteWebhook("wh-1", "s2") }
    }

    @Test
    fun `DELETE v1 webhooks id returns 404 for missing`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val webhookService = mockk<WebhookService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { webhookService.deleteWebhook("wh-missing", "s2") } throws NotFoundException("Webhook not found")

        val inboxService = mockk<InboxService>(relaxed = true)
        setup { webhookRoutes(webhookService, sessionService, inboxService) }

        val response = client.delete("/v1/webhooks/wh-missing") {
            header("Authorization", "Bearer pro-token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // --- PATCH /v1/webhooks/{id} ---

    @Test
    fun `PATCH v1 webhooks id toggles enabled`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val webhookService = mockk<WebhookService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coJustRun { webhookService.toggleWebhook("wh-1", "s2", false) }

        val inboxService = mockk<InboxService>(relaxed = true)
        setup { webhookRoutes(webhookService, sessionService, inboxService) }

        val response = client.patch("/v1/webhooks/wh-1") {
            header("Authorization", "Bearer pro-token")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": false}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("false"), "Response should contain enabled=false")
        coVerify { webhookService.toggleWebhook("wh-1", "s2", false) }
    }

    // --- GET /v1/webhooks/{id}/deliveries ---

    @Test
    fun `GET v1 webhooks id deliveries returns delivery log`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        val webhookService = mockk<WebhookService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer pro-token") } returns proSession
        coEvery { webhookService.getDeliveryLog("wh-1") } returns listOf(
            WebhookDelivery(
                id = "del-1",
                webhookId = "wh-1",
                emailId = "email-1",
                statusCode = 200,
                attempts = 1,
                status = DeliveryStatus.SUCCESS,
            )
        )

        val inboxService = mockk<InboxService>(relaxed = true)
        setup { webhookRoutes(webhookService, sessionService, inboxService) }

        val response = client.get("/v1/webhooks/wh-1/deliveries") {
            header("Authorization", "Bearer pro-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"del-1\""), "Response should contain delivery id")
        assertTrue(body.contains("\"SUCCESS\""), "Response should contain delivery status")
    }
}
