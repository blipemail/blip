package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.Tier
import dev.bmcreations.blip.server.SqliteTurso
import dev.bmcreations.blip.server.db.TursoValue
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.*

/** Runs the real migrations on in-memory SQLite. */
class FirstInboxAtSqlTest {

    private lateinit var db: SqliteTurso
    private lateinit var inboxService: InboxService

    @BeforeTest
    fun setup() {
        db = SqliteTurso()
        val domains = mockk<DomainService>()
        coEvery { domains.listActiveDomains() } returns listOf("useblip.email")
        inboxService = InboxService(db.client, SessionService(db.client), domains)
        db.run("INSERT INTO users (id, email) VALUES ('u1', 'u1@example.com')")
        db.run("INSERT INTO users (id, email) VALUES ('u2', 'u2@example.com')")
        val expires = Instant.now().plusSeconds(86_400).toString()
        db.run(
            "INSERT INTO sessions (id, token, tier, expires_at, user_id) VALUES ('s1', 'tok1', 'PRO', ?, 'u1')",
            listOf(TursoValue.Text(expires)),
        )
        db.run(
            "INSERT INTO sessions (id, token, tier, expires_at) VALUES ('anon', 'tok2', 'FREE', ?)",
            listOf(TursoValue.Text(expires)),
        )
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun firstInboxAt(userId: String): String? =
        db.run("SELECT first_inbox_at FROM users WHERE id = ?", listOf(TursoValue.Text(userId)))
            .firstOrNull()!!["first_inbox_at"]

    @Test
    fun `first inbox for a user sets first_inbox_at`() = runTest {
        assertNull(firstInboxAt("u1"))

        val created = inboxService.createInbox("s1", Tier.PRO, userId = "u1")

        assertEquals(created.createdAt, firstInboxAt("u1"))
    }

    @Test
    fun `second inbox leaves first_inbox_at unchanged`() = runTest {
        inboxService.createInbox("s1", Tier.PRO, userId = "u1")
        val first = firstInboxAt("u1")
        assertNotNull(first)

        inboxService.createInbox("s1", Tier.PRO, userId = "u1")

        assertEquals(first, firstInboxAt("u1"))
    }

    @Test
    fun `anonymous inbox does not touch users`() = runTest {
        inboxService.createInbox("anon", Tier.FREE)

        assertNull(firstInboxAt("u1"))
        assertNull(firstInboxAt("u2"))
    }
}
