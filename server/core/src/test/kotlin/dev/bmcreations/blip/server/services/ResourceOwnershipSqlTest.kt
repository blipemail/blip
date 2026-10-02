package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.CreateWebhookRequest
import dev.bmcreations.blip.server.SqliteTurso
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Checks that webhooks and forwarding rules record the creating user, on in-memory SQLite. */
class ResourceOwnershipSqlTest {

    private lateinit var db: SqliteTurso
    private lateinit var webhooks: WebhookService
    private lateinit var forwarding: ForwardingService

    @BeforeTest
    fun setup() {
        db = SqliteTurso()
        webhooks = WebhookService(db.client)
        forwarding = ForwardingService(db.client, "test-resend-key")
        db.run("INSERT INTO users (id, email) VALUES ('u1', 'u1@example.com')")
        db.run("INSERT INTO sessions (id, token, tier, expires_at, user_id) VALUES ('signed', 't1', 'PRO', '2099-01-01T00:00:00Z', 'u1')")
        db.run("INSERT INTO sessions (id, token, tier, expires_at) VALUES ('anon', 't2', 'PRO', '2099-01-01T00:00:00Z')")
        for (s in listOf("signed", "anon")) {
            db.run("INSERT INTO inboxes (id, address, domain, session_id, created_at, expires_at) VALUES ('i-$s', '$s@useblip.email', 'useblip.email', '$s', '2026-01-01T00:00:00Z', '2099-01-01T00:00:00Z')")
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun userId(table: String, id: String) = db.run("SELECT user_id FROM $table WHERE id = '$id'").rows.single()[0]

    @Test
    fun `webhook created by a signed-in user records the user`() = runTest {
        val hook = webhooks.createWebhook("signed", CreateWebhookRequest(url = "https://example.com/hook"), userId = "u1")
        assertEquals("u1", userId("webhooks", hook.id))
    }

    @Test
    fun `webhook created anonymously has no user`() = runTest {
        val hook = webhooks.createWebhook("anon", CreateWebhookRequest(url = "https://example.com/hook"))
        assertNull(userId("webhooks", hook.id))
    }

    @Test
    fun `forwarding rule created by a signed-in user records the user`() = runTest {
        val rule = forwarding.createRule("i-signed", "signed", "a@example.com", 1, userId = "u1")
        assertEquals("u1", userId("forwarding_rules", rule.id))
    }

    @Test
    fun `forwarding rule created anonymously has no user`() = runTest {
        val rule = forwarding.createRule("i-anon", "anon", "a@example.com", 1)
        assertNull(userId("forwarding_rules", rule.id))
    }
}
