package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.Tier
import dev.bmcreations.blip.server.UnauthorizedException
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoResult
import dev.bmcreations.blip.server.db.TursoValue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

class SessionServiceTest {

    private lateinit var turso: TursoClient
    private lateinit var service: SessionService

    @BeforeTest
    fun setup() {
        turso = mockk()
        service = SessionService(turso)
    }

    // -- createSession --

    @Test
    fun `createSession returns valid DTO with FREE tier, token, and future expiresAt`() = runTest {
        coEvery { turso.execute(any(), any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)

        val session = service.createSession()

        assertEquals(Tier.FREE, session.tier)
        assertNotNull(session.token)
        assertTrue(session.token.isNotBlank())
        assertNotNull(session.id)
        assertTrue(session.id.isNotBlank())

        val expiresAt = Instant.parse(session.expiresAt)
        assertTrue(expiresAt.isAfter(Instant.now()))
        // Should expire roughly 24 hours from now (within a 1-minute tolerance)
        val expectedExpiry = Instant.now().plus(24, ChronoUnit.HOURS)
        assertTrue(expiresAt.isAfter(expectedExpiry.minus(1, ChronoUnit.MINUTES)))
        assertTrue(expiresAt.isBefore(expectedExpiry.plus(1, ChronoUnit.MINUTES)))
    }

    @Test
    fun `createSession inserts into turso with correct SQL`() = runTest {
        val sqlSlot = slot<String>()
        val argsSlot = slot<List<TursoValue>>()

        coEvery { turso.execute(capture(sqlSlot), capture(argsSlot)) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)

        val session = service.createSession()

        assertTrue(sqlSlot.captured.contains("INSERT INTO sessions"))

        val args = argsSlot.captured
        assertEquals(5, args.size)
        assertEquals(session.id, (args[0] as TursoValue.Text).value)
        assertEquals(session.token, (args[1] as TursoValue.Text).value)
        assertEquals("FREE", (args[2] as TursoValue.Text).value)
        assertEquals(session.expiresAt, (args[3] as TursoValue.Text).value)
        assertTrue(args[4] is TursoValue.Null) // no fingerprint without IP
    }

    // -- getSessionByToken --

    @Test
    fun `getSessionByToken returns session when valid`() = runTest {
        val futureExpiry = Instant.now().plus(1, ChronoUnit.HOURS).toString()

        coEvery { turso.execute(match { it.contains("SELECT") && it.contains("sessions") }, any()) } returns
            TursoResult(
                columns = listOf("id", "token", "tier", "user_id", "expires_at"),
                rows = listOf(listOf("sess-1", "tok-abc", "FREE", null, futureExpiry)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val session = service.getSessionByToken("tok-abc")

        assertEquals("sess-1", session.id)
        assertEquals("tok-abc", session.token)
        assertEquals(Tier.FREE, session.tier)
        assertNull(session.userId)
        assertEquals(futureExpiry, session.expiresAt)
    }

    @Test
    fun `getSessionByToken throws UnauthorizedException when token not found`() = runTest {
        coEvery { turso.execute(any(), any()) } returns
            TursoResult(
                columns = listOf("id", "token", "tier", "user_id", "expires_at"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val exception = assertFailsWith<UnauthorizedException> {
            service.getSessionByToken("nonexistent-token")
        }
        assertTrue(exception.message!!.contains("Invalid session token"))
    }

    @Test
    fun `getSessionByToken throws UnauthorizedException when session expired`() = runTest {
        val pastExpiry = Instant.now().minus(1, ChronoUnit.HOURS).toString()

        coEvery { turso.execute(any(), any()) } returns
            TursoResult(
                columns = listOf("id", "token", "tier", "user_id", "expires_at"),
                rows = listOf(listOf("sess-1", "tok-expired", "FREE", null, pastExpiry)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val exception = assertFailsWith<UnauthorizedException> {
            service.getSessionByToken("tok-expired")
        }
        assertTrue(exception.message!!.contains("Session expired"))
    }

    // -- extractSession --

    @Test
    fun `extractSession extracts Bearer token from header`() = runTest {
        val futureExpiry = Instant.now().plus(1, ChronoUnit.HOURS).toString()

        coEvery { turso.execute(any(), any()) } returns
            TursoResult(
                columns = listOf("id", "token", "tier", "user_id", "expires_at"),
                rows = listOf(listOf("sess-1", "my-token", "FREE", null, futureExpiry)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val session = service.extractSession("Bearer my-token")

        assertEquals("my-token", session.token)

        coVerify {
            turso.execute(any(), match { args ->
                args.any { it is TursoValue.Text && it.value == "my-token" }
            })
        }
    }

    @Test
    fun `extractSession throws UnauthorizedException when header missing`() = runTest {
        val exception = assertFailsWith<UnauthorizedException> {
            service.extractSession(null)
        }
        assertTrue(exception.message!!.contains("Missing authorization header"))
    }

    @Test
    fun `extractSession with no Bearer prefix still passes raw value to getSessionByToken`() = runTest {
        // extractSession strips "Bearer " prefix; if header has no such prefix,
        // the raw string is passed to getSessionByToken
        val futureExpiry = Instant.now().plus(1, ChronoUnit.HOURS).toString()

        coEvery { turso.execute(any(), any()) } returns
            TursoResult(
                columns = listOf("id", "token", "tier", "user_id", "expires_at"),
                rows = listOf(listOf("sess-1", "raw-token-value", "FREE", null, futureExpiry)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        // When header doesn't have "Bearer " prefix, removePrefix is a no-op,
        // so the full string is passed as the token
        val session = service.extractSession("raw-token-value")

        assertEquals("raw-token-value", session.token)
    }
}
