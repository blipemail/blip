package dev.bmcreations.blip.server

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.*

class SecurityTest {

    @Test
    fun `client IP is the last XFF hop, not the spoofable first`() {
        assertEquals("203.0.113.9", ClientIp.resolve("1.2.3.4, 203.0.113.9", "10.0.0.1", 1))
    }

    @Test
    fun `single entry and trailing whitespace`() {
        assertEquals("203.0.113.9", ClientIp.resolve(" 203.0.113.9 ", "10.0.0.1", 1))
    }

    @Test
    fun `two trusted hops take the second from the right`() {
        assertEquals("1.2.3.4", ClientIp.resolve("9.9.9.9, 1.2.3.4, 10.1.1.1", "10.0.0.1", 2))
    }

    @Test
    fun `falls back to remote address without header or with zero hops`() {
        assertEquals("10.0.0.1", ClientIp.resolve(null, "10.0.0.1", 1))
        assertEquals("10.0.0.1", ClientIp.resolve("", "10.0.0.1", 1))
        assertEquals("10.0.0.1", ClientIp.resolve("1.2.3.4", "10.0.0.1", 0))
    }

    @Test
    fun `more hops than entries uses the first entry`() {
        assertEquals("1.2.3.4", ClientIp.resolve("1.2.3.4", "10.0.0.1", 3))
    }

    @Test
    fun `secretsMatch compares values`() {
        assertTrue(secretsMatch("abc", "abc"))
        assertFalse(secretsMatch("abd", "abc"))
        assertFalse(secretsMatch("abcd", "abc"))
        assertFalse(secretsMatch(null, "abc"))
    }
}

class RateLimitExemptionTest {
    @Test
    fun `zero request weight exempts a path from the limiter`() = testApplication {
        val exempt = setOf("/hook")
        application {
            install(RateLimit) {
                register(RateLimitName("public")) {
                    rateLimiter(limit = 2, refillPeriod = kotlin.time.Duration.parse("1m"))
                    requestKey { "same-ip" }
                    requestWeight { call, _ -> if (call.request.path() in exempt) 0 else 1 }
                }
            }
            routing {
                rateLimit(RateLimitName("public")) {
                    get("/hook") { call.respondText("ok") }
                    get("/other") { call.respondText("ok") }
                }
            }
        }
        repeat(5) { assertEquals(HttpStatusCode.OK, client.get("/hook").status) }
        assertEquals(HttpStatusCode.OK, client.get("/other").status)
        assertEquals(HttpStatusCode.OK, client.get("/other").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/other").status)
    }

    @Test
    fun `prefers CF-Connecting-IP over X-Forwarded-For`() {
        assertEquals("2605:59ca::1", ClientIp.resolve("2605:59ca::1", "2605:59ca::1, 152.233.40.1", "10.0.0.1", 1))
    }

    @Test
    fun `falls back to X-Forwarded-For when CF-Connecting-IP is blank`() {
        assertEquals("203.0.113.9", ClientIp.resolve(" ", "1.2.3.4, 203.0.113.9", "10.0.0.1", 1))
        assertEquals("203.0.113.9", ClientIp.resolve(null, "1.2.3.4, 203.0.113.9", "10.0.0.1", 1))
    }
}
