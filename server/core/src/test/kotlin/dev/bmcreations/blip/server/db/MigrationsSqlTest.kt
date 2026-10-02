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

    @Test
    fun `migrations can run again on an existing database`() {
        // Production runs every migration on each boot; SqliteTurso has already run them once.
        Migrations.migrations.forEach { sql ->
            try {
                db.run(sql)
            } catch (e: java.sql.SQLException) {
                if ("duplicate column" !in (e.message ?: "")) throw e
            }
        }
    }
}
