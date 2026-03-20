package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.ErrorResponse
import dev.bmcreations.blip.models.SessionDTO
import dev.bmcreations.blip.models.Tier
import dev.bmcreations.blip.server.*
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

class SessionRoutesTest {

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
    fun `POST v1 sessions creates session and returns 200 with token`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        coEvery { sessionService.createSession() } returns testSession

        setup {
            sessionRoutes(sessionService)
        }

        val response = client.post("/v1/sessions")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"token\""), "Response should contain token field")
        assertTrue(body.contains("\"test-token\""), "Response should contain the token value")
        assertTrue(body.contains("\"session\""), "Response should contain session object")
    }

    @Test
    fun `GET v1 sessions me returns session for valid token`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        coEvery { sessionService.extractSession("Bearer test-token") } returns testSession

        setup {
            sessionRoutes(sessionService)
        }

        val response = client.get("/v1/sessions/me") {
            header("Authorization", "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"s1\""), "Response should contain session id")
        assertTrue(body.contains("\"test-token\""), "Response should contain token")
    }

    @Test
    fun `GET v1 sessions me returns 401 without auth header`() = testApplication {
        val sessionService = mockk<SessionService>(relaxed = true)
        coEvery { sessionService.extractSession(null) } throws UnauthorizedException("Missing authorization header")

        setup {
            sessionRoutes(sessionService)
        }

        val response = client.get("/v1/sessions/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"unauthorized\""), "Response should contain unauthorized error")
    }
}
