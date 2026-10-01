package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.Tier
import dev.bmcreations.blip.server.NotFoundException
import dev.bmcreations.blip.server.SqliteTurso
import dev.bmcreations.blip.server.db.TursoValue
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

/** Runs the real migrations on in-memory SQLite with foreign keys on. */
class InboxLifetimeSqlTest {

    private lateinit var db: SqliteTurso
    private lateinit var inboxService: InboxService
    private lateinit var cleanup: CleanupService

    @BeforeTest
    fun setup() {
        db = SqliteTurso()
        val domains = mockk<DomainService>()
        coEvery { domains.listActiveDomains() } returns listOf("useblip.email")
        inboxService = InboxService(db.client, SessionService(db.client), domains)
        cleanup = CleanupService(db.client)
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun iso(offset: Long, unit: ChronoUnit) = Instant.now().plus(offset, unit).toString()

    private fun session(id: String, tier: String = "FREE", expiresAt: String, userId: String? = null) {
        db.run(
            "INSERT INTO sessions (id, token, tier, expires_at, user_id) VALUES (?, ?, ?, ?, ?)",
            listOf(
                TursoValue.Text(id), TursoValue.Text("tok-$id"), TursoValue.Text(tier), TursoValue.Text(expiresAt),
                userId?.let { TursoValue.Text(it) } ?: TursoValue.Null,
            ),
        )
    }

    private fun user(id: String, pro: Int = 0, agent: Int = 0) {
        db.run(
            "INSERT INTO users (id, email, has_pro, has_agent) VALUES (?, ?, ?, ?)",
            listOf(TursoValue.Text(id), TursoValue.Text("$id@example.com"), TursoValue.Integer(pro.toLong()), TursoValue.Integer(agent.toLong())),
        )
    }

    private fun inbox(id: String, sessionId: String, expiresAt: String, userId: String? = null) {
        db.run(
            "INSERT INTO inboxes (id, address, domain, session_id, created_at, expires_at, user_id) VALUES (?, ?, 'useblip.email', ?, ?, ?, ?)",
            listOf(
                TursoValue.Text(id), TursoValue.Text("$id@useblip.email"), TursoValue.Text(sessionId),
                TursoValue.Text(Instant.now().toString()), TursoValue.Text(expiresAt),
                userId?.let { TursoValue.Text(it) } ?: TursoValue.Null,
            ),
        )
    }

    private fun email(id: String, inboxId: String, receivedAt: String) {
        db.run(
            "INSERT INTO emails (id, inbox_id, from_addr, to_addr, subject, headers, received_at, preview) VALUES (?, ?, 'a@b.c', 'x', 's', '{}', ?, '')",
            listOf(TursoValue.Text(id), TursoValue.Text(inboxId), TursoValue.Text(receivedAt)),
        )
    }

    @Test
    fun `expired session that owns a live inbox is kept with its inbox and emails`() = runTest {
        session("s1", tier = "PRO", expiresAt = iso(-2, ChronoUnit.HOURS))
        inbox("i1", "s1", expiresAt = iso(30, ChronoUnit.DAYS))
        email("e1", "i1", Instant.now().toString())

        cleanup.cleanup()

        assertEquals(1, db.count("sessions"))
        assertEquals(1, db.count("inboxes"))
        assertEquals(1, db.count("emails"))
    }

    @Test
    fun `expired session with only expired inboxes is deleted`() = runTest {
        session("s1", expiresAt = iso(-2, ChronoUnit.HOURS))
        inbox("i1", "s1", expiresAt = iso(-1, ChronoUnit.HOURS))

        cleanup.cleanup()

        assertEquals(0, db.count("sessions"))
        assertEquals(0, db.count("inboxes"))
    }

    @Test
    fun `unexpired session is never deleted`() = runTest {
        session("s1", expiresAt = iso(1, ChronoUnit.HOURS))

        cleanup.cleanup()

        assertEquals(1, db.count("sessions"))
    }

    @Test
    fun `expired inbox from earlier the same day is deleted`() = runTest {
        // datetime('now') is "YYYY-MM-DD HH:MM:SS"; the old comparison kept same-day ISO values.
        session("s1", expiresAt = iso(1, ChronoUnit.DAYS))
        inbox("i1", "s1", expiresAt = iso(-1, ChronoUnit.HOURS))

        cleanup.cleanup()

        assertEquals(0, db.count("inboxes"))
    }

    @Test
    fun `datetime('now') comparison misses an ISO timestamp an hour in the past`() {
        // Documents the original bug; fails if SQLite semantics ever change.
        val oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS)
        // Only meaningful when the hour-ago instant is still the same UTC day.
        if (oneHourAgo.atZone(java.time.ZoneOffset.UTC).toLocalDate() != Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate()) return
        val r = db.run("SELECT ? < datetime('now') AS expired", listOf(TursoValue.Text(oneHourAgo.toString())))
        assertEquals("0", r.firstOrNull()!!["expired"])
        val fixed = db.run("SELECT ? < ? AS expired", listOf(TursoValue.Text(oneHourAgo.toString()), TursoValue.Text(Instant.now().toString())))
        assertEquals("1", fixed.firstOrNull()!!["expired"])
    }

    @Test
    fun `retention uses the user's tier, not the expired creating session`() = runTest {
        user("u1", pro = 1)
        session("s1", tier = "FREE", expiresAt = iso(-1, ChronoUnit.HOURS))
        inbox("i1", "s1", expiresAt = iso(30, ChronoUnit.DAYS), userId = "u1")
        email("e1", "i1", iso(-3, ChronoUnit.DAYS)) // past FREE retention, within PRO

        cleanup.cleanup()

        assertEquals(1, db.count("emails"))
    }

    @Test
    fun `retention deletes FREE emails past 24h and keeps PRO emails inside 30d`() = runTest {
        session("free", tier = "FREE", expiresAt = iso(1, ChronoUnit.DAYS))
        session("pro", tier = "PRO", expiresAt = iso(1, ChronoUnit.DAYS))
        inbox("i-free", "free", iso(1, ChronoUnit.DAYS))
        inbox("i-pro", "pro", iso(30, ChronoUnit.DAYS))
        email("e-free", "i-free", iso(-25, ChronoUnit.HOURS))
        email("e-pro", "i-pro", iso(-25, ChronoUnit.HOURS))

        cleanup.cleanup()

        val ids = db.run("SELECT id FROM emails").toMaps().map { it["id"] }
        assertEquals(listOf("e-pro"), ids)
    }

    @Test
    fun `tierForInbox prefers users has_pro over the session tier`() = runTest {
        user("u1", pro = 1)
        session("s1", tier = "FREE", expiresAt = iso(1, ChronoUnit.DAYS))
        inbox("i1", "s1", iso(1, ChronoUnit.DAYS), userId = "u1")

        assertEquals(Tier.PRO, inboxService.tierForInbox("i1"))
    }

    @Test
    fun `tierForInbox maps has_agent to AGENT and flagless user to FREE`() = runTest {
        user("agent", agent = 1)
        user("plain")
        session("s1", tier = "PRO", expiresAt = iso(1, ChronoUnit.DAYS))
        inbox("i-agent", "s1", iso(1, ChronoUnit.DAYS), userId = "agent")
        inbox("i-plain", "s1", iso(1, ChronoUnit.DAYS), userId = "plain")

        assertEquals(Tier.AGENT, inboxService.tierForInbox("i-agent"))
        assertEquals(Tier.FREE, inboxService.tierForInbox("i-plain"))
    }

    @Test
    fun `tierForInbox falls back to session tier then FREE`() = runTest {
        session("s1", tier = "PRO", expiresAt = iso(1, ChronoUnit.DAYS))
        inbox("i1", "s1", iso(1, ChronoUnit.DAYS))

        assertEquals(Tier.PRO, inboxService.tierForInbox("i1"))
        assertEquals(Tier.FREE, inboxService.tierForInbox("missing"))
    }

    @Test
    fun `getInboxByAddress rejects an inbox that expired earlier today`() = runTest {
        session("s1", expiresAt = iso(1, ChronoUnit.DAYS))
        inbox("gone", "s1", iso(-1, ChronoUnit.HOURS))
        inbox("live", "s1", iso(1, ChronoUnit.HOURS))

        assertNull(inboxService.getInboxByAddress("gone@useblip.email"))
        assertNotNull(inboxService.getInboxByAddress("live@useblip.email"))
    }

    @Test
    fun `getInbox treats an expired inbox as not found`() = runTest {
        session("s1", expiresAt = iso(1, ChronoUnit.DAYS))
        inbox("gone", "s1", iso(-1, ChronoUnit.HOURS))
        inbox("live", "s1", iso(1, ChronoUnit.HOURS))

        assertFailsWith<NotFoundException> { inboxService.getInbox("gone", "s1") }
        assertEquals("live", inboxService.getInbox("live", "s1").inbox.id)
    }

    @Test
    fun `listInboxes hides inboxes that expired earlier today`() = runTest {
        session("s1", expiresAt = iso(1, ChronoUnit.DAYS))
        inbox("gone", "s1", iso(-1, ChronoUnit.HOURS))
        inbox("live", "s1", iso(1, ChronoUnit.HOURS))

        assertEquals(listOf("live"), inboxService.listInboxes("s1").map { it.id })
    }
}
