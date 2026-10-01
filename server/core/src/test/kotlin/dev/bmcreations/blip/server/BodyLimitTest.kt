package dev.bmcreations.blip.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.*

class BodyLimitTest {

    private val secret = "s3cret"
    private var handlerCalls = 0

    private fun ApplicationTestBuilder.setup() {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
            install(RequestBodyLimit) { workerSecret = secret }
            routing {
                post("/v1/inboxes/{address}/emails") { handlerCalls++; call.respond(HttpStatusCode.OK) }
                post("/other") { handlerCalls++; call.respond(HttpStatusCode.OK) }
            }
        }
    }

    private fun HttpRequestBuilder.sized(bytes: Long, withSecret: Boolean) {
        if (withSecret) header("X-Worker-Secret", secret)
        setBody(ByteArray(bytes.toInt()))
    }

    @Test
    fun `ingress route with worker secret accepts up to 16 MB`() = testApplication {
        setup()
        val ok = client.post("/v1/inboxes/a@b.c/emails") { sized(12L shl 20, true) }
        assertEquals(HttpStatusCode.OK, ok.status)
        val tooBig = client.post("/v1/inboxes/a@b.c/emails") { sized(17L shl 20, true) }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
    }

    @Test
    fun `other routes and ingress without secret stay at 10 MB and handler is not run`() = testApplication {
        setup()
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/other") { sized(12L shl 20, true) }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/v1/inboxes/a@b.c/emails") { sized(12L shl 20, false) }.status)
        assertEquals(0, handlerCalls, "handler must not run after the limit response")
    }
}
