package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.CreateWebhookRequest
import dev.bmcreations.blip.server.SqliteTurso
import kotlinx.coroutines.test.runTest
import dev.bmcreations.blip.server.ForbiddenException
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
        // u1 is also signed in on a second device; u2 is someone else.
        db.run("INSERT INTO users (id, email) VALUES ('u2', 'u2@example.com')")
        db.run("INSERT INTO sessions (id, token, tier, expires_at, user_id) VALUES ('signed2', 't3', 'PRO', '2099-01-01T00:00:00Z', 'u1')")
        db.run("INSERT INTO sessions (id, token, tier, expires_at, user_id) VALUES ('other', 't4', 'PRO', '2099-01-01T00:00:00Z', 'u2')")
        for (s in listOf("signed", "anon", "signed2")) {
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

    // --- Reading from another of the user's sessions ---

    @Test
    fun `webhook created on one device can be listed, toggled and deleted from another`() = runTest {
        val hook = webhooks.createWebhook("signed", CreateWebhookRequest(url = "https://example.com/hook"), userId = "u1")

        assertEquals(listOf(hook.id), webhooks.listWebhooks("signed2", "u1").map { it.id })
        webhooks.assertOwnsWebhook(hook.id, "signed2", "u1")
        webhooks.toggleWebhook(hook.id, "signed2", false, "u1")
        assertEquals("0", db.run("SELECT enabled FROM webhooks WHERE id = '${hook.id}'").rows.single()[0])
        webhooks.deleteWebhook(hook.id, "signed2", "u1")
        assertEquals(0, db.count("webhooks"))
    }

    @Test
    fun `another user can't reach the webhook`() = runTest {
        val hook = webhooks.createWebhook("signed", CreateWebhookRequest(url = "https://example.com/hook"), userId = "u1")

        assertTrue(webhooks.listWebhooks("other", "u2").isEmpty())
        assertFailsWith<ForbiddenException> { webhooks.assertOwnsWebhook(hook.id, "other", "u2") }
        assertFailsWith<ForbiddenException> { webhooks.toggleWebhook(hook.id, "other", false, "u2") }
        assertFailsWith<ForbiddenException> { webhooks.deleteWebhook(hook.id, "other", "u2") }
    }

    @Test
    fun `anonymous webhook is only reachable from its own session`() = runTest {
        val hook = webhooks.createWebhook("anon", CreateWebhookRequest(url = "https://example.com/hook"))

        assertEquals(listOf(hook.id), webhooks.listWebhooks("anon").map { it.id })
        assertTrue(webhooks.listWebhooks("signed", "u1").isEmpty())
        assertFailsWith<ForbiddenException> { webhooks.deleteWebhook(hook.id, "signed", "u1") }
    }

    @Test
    fun `forwarding rule created on one device can be deleted from another, but not by another user`() = runTest {
        val rule = forwarding.createRule("i-signed", "signed", "a@example.com", 1, userId = "u1")

        assertFailsWith<ForbiddenException> { forwarding.deleteRule(rule.id, "other", "u2") }
        forwarding.deleteRule(rule.id, "signed2", "u1")
        assertEquals(0, db.count("forwarding_rules"))
    }

    // --- Delivery ---

    @Test
    fun `webhook set on an inbox fires even when the inbox came from another session`() = runTest {
        val hook = webhooks.createWebhook("signed", CreateWebhookRequest(url = "https://example.com/hook", inboxId = "i-signed2"), userId = "u1")

        assertEquals(listOf(hook.id), webhooks.getWebhooksForInbox("i-signed2", "signed2").map { it.id })
    }

    @Test
    fun `webhook with no inbox only fires for its own session's inboxes`() = runTest {
        val sessionWide = webhooks.createWebhook("signed", CreateWebhookRequest(url = "https://example.com/hook"), userId = "u1")

        assertEquals(listOf(sessionWide.id), webhooks.getWebhooksForInbox("i-signed", "signed").map { it.id })
        assertTrue(webhooks.getWebhooksForInbox("i-signed2", "signed2").isEmpty())
    }

    @Test
    fun `disabled webhook doesn't fire`() = runTest {
        val hook = webhooks.createWebhook("signed", CreateWebhookRequest(url = "https://example.com/hook", inboxId = "i-signed"), userId = "u1")
        webhooks.toggleWebhook(hook.id, "signed", false, "u1")

        assertTrue(webhooks.getWebhooksForInbox("i-signed", "signed").isEmpty())
    }
}
