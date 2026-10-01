package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoResult
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class HealthRoutesTest {

    @Test
    fun `GET health returns 200 with status ok when db is up`() = testApplication {
        val turso = mockk<TursoClient>()
        coEvery { turso.execute(any(), any()) } returns
            TursoResult(listOf("cnt"), listOf(listOf("0")), 0, 0)

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        routing {
            healthRoutes(turso)
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"ok\""), "Should report ok status")
        assertFalse(body.contains("stats"), "Public health must not expose counts")
        coVerify(exactly = 1) { turso.execute("SELECT 1", emptyList()) }
    }

    @Test
    fun `GET livez returns 200 without touching the db`() = testApplication {
        val turso = mockk<TursoClient>()

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        routing {
            healthRoutes(turso)
        }

        val response = client.get("/livez")

        assertEquals(HttpStatusCode.OK, response.status)
        confirmVerified(turso)
    }

    @Test
    fun `GET health returns 503 when db is down`() = testApplication {
        val turso = mockk<TursoClient>()
        coEvery { turso.execute(any(), any()) } throws RuntimeException("Connection refused")

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        routing {
            healthRoutes(turso)
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"degraded\""), "Should report degraded status")
    }
}
