package dev.bmcreations.blip.server.db

import dev.bmcreations.blip.server.SqliteTurso
import kotlin.test.*

/** Runs the real migrations on in-memory SQLite. */
class MigrationsSqlTest {

    private lateinit var db: SqliteTurso

    @BeforeTest
    fun setup() {
        db = SqliteTurso()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun columns(table: String) = db.run("SELECT name FROM pragma_table_info('$table')").rows.map { it[0] }

    private fun indexes(table: String) = db.run("SELECT name FROM pragma_index_list('$table')").rows.map { it[0] }

    @Test
    fun `webhooks and forwarding rules have an indexed user_id`() {
        assertContains(columns("webhooks"), "user_id")
        assertContains(columns("forwarding_rules"), "user_id")
        assertContains(indexes("webhooks"), "idx_webhooks_user")
        assertContains(indexes("forwarding_rules"), "idx_forwarding_rules_user")
    }

    private fun rerunMigrations() {
        // Production runs every migration on each boot; SqliteTurso has already run them once.
        Migrations.migrations.forEach { sql ->
            try {
                db.run(sql)
            } catch (e: java.sql.SQLException) {
                if ("duplicate column" !in (e.message ?: "")) throw e
            }
        }
    }

    @Test
    fun `migrations can run again on an existing database`() {
        rerunMigrations()
    }

    @Test
    fun `backfill copies the session's user_id onto its webhooks and forwarding rules`() {
        db.run("INSERT INTO users (id, email) VALUES ('u1', 'u1@example.com')")
        db.run("INSERT INTO sessions (id, token, tier, expires_at, user_id) VALUES ('signed', 't1', 'FREE', '2099-01-01T00:00:00Z', 'u1')")
        db.run("INSERT INTO sessions (id, token, tier, expires_at) VALUES ('anon', 't2', 'FREE', '2099-01-01T00:00:00Z')")
        for (s in listOf("signed", "anon")) {
            db.run("INSERT INTO inboxes (id, address, domain, session_id, created_at, expires_at) VALUES ('i-$s', '$s@useblip.email', 'useblip.email', '$s', '2026-01-01T00:00:00Z', '2099-01-01T00:00:00Z')")
            db.run("INSERT INTO webhooks (id, session_id, url, secret) VALUES ('w-$s', '$s', 'https://example.com', 'x')")
            db.run("INSERT INTO forwarding_rules (id, inbox_id, session_id, forward_to_email) VALUES ('f-$s', 'i-$s', '$s', 'a@example.com')")
        }

        rerunMigrations()

        fun userId(table: String, id: String) = db.run("SELECT user_id FROM $table WHERE id = '$id'").rows.single()[0]
        assertEquals("u1", userId("webhooks", "w-signed"))
        assertEquals("u1", userId("forwarding_rules", "f-signed"))
        assertNull(userId("webhooks", "w-anon"))
        assertNull(userId("forwarding_rules", "f-anon"))
    }

    @Test
    fun `backfill leaves an existing user_id alone`() {
        db.run("INSERT INTO sessions (id, token, tier, expires_at, user_id) VALUES ('s', 't', 'FREE', '2099-01-01T00:00:00Z', 'u-session')")
        db.run("INSERT INTO webhooks (id, session_id, url, secret, user_id) VALUES ('w', 's', 'https://example.com', 'x', 'u-set')")

        rerunMigrations()

        assertEquals("u-set", db.run("SELECT user_id FROM webhooks WHERE id = 'w'").rows.single()[0])
    }
}
