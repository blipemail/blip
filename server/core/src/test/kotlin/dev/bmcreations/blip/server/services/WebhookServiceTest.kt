package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.*
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoResult
import dev.bmcreations.blip.server.db.TursoValue
import dev.bmcreations.blip.server.TierLimitException
import io.mockk.*
import kotlinx.coroutines.test.runTest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class WebhookServiceTest {

    private lateinit var turso: TursoClient
    private lateinit var webhookService: WebhookService

    @BeforeTest
    fun setup() {
        turso = mockk()
        webhookService = WebhookService(turso)
    }

    // --- Registration tests ---

    @Test
    fun `createWebhook stores webhook and returns DTO with generated secret`() = runTest {
        coEvery { turso.execute(match { it.contains("INSERT INTO webhooks") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)

        val webhook = webhookService.createWebhook("session-1", CreateWebhookRequest(url = "https://example.com/hook"))

        assertNotNull(webhook.id)
        assertEquals("https://example.com/hook", webhook.url)
        assertNotNull(webhook.secret)
        assertTrue(webhook.secret.length >= 32)
        assertNull(webhook.inboxId)
        assertTrue(webhook.enabled)

        coVerify { turso.execute(match { it.contains("INSERT INTO webhooks") }, any()) }
    }

    @Test
    fun `createWebhook scoped to specific inbox`() = runTest {
        coEvery { turso.execute(any(), any()) } returns TursoResult(emptyList(), emptyList(), 1, 1)

        val webhook = webhookService.createWebhook("session-1", CreateWebhookRequest(url = "https://example.com/hook", inboxId = "inbox-1"))

        assertEquals("inbox-1", webhook.inboxId)
    }

    @Test
    fun `createWebhook persists session_id in the row`() = runTest {
        val argsSlot = slot<List<TursoValue>>()

        coEvery { turso.execute(match { it.contains("INSERT INTO webhooks") }, capture(argsSlot)) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)

        webhookService.createWebhook("session-1", CreateWebhookRequest(url = "https://example.com/hook"))

        // One of the args should be the session id
        val textArgs = argsSlot.captured.filterIsInstance<TursoValue.Text>().map { it.value }
        assertTrue(textArgs.contains("session-1"), "Expected session-1 to be persisted as an argument")
    }

    @Test
    fun `listWebhooks returns all webhooks for session`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT") && it.contains("webhooks") }, any()) } returns
            TursoResult(
                columns = listOf("id", "url", "secret", "inbox_id", "created_at", "enabled"),
                rows = listOf(
                    listOf("wh-1", "https://a.com/hook", "secret1", null, "2026-01-01T00:00:00Z", "1"),
                    listOf("wh-2", "https://b.com/hook", "secret2", "inbox-1", "2026-01-01T00:00:00Z", "1"),
                ),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val webhooks = webhookService.listWebhooks("session-1")

        assertEquals(2, webhooks.size)
        assertEquals("wh-1", webhooks[0].id)
        assertEquals("https://a.com/hook", webhooks[0].url)
        assertNull(webhooks[0].inboxId)
        assertEquals("wh-2", webhooks[1].id)
        assertEquals("https://b.com/hook", webhooks[1].url)
        assertEquals("inbox-1", webhooks[1].inboxId)
    }

    @Test
    fun `listWebhooks returns empty list when no webhooks exist`() = runTest {
        coEvery { turso.execute(any(), any()) } returns
            TursoResult(
                columns = listOf("id", "url", "secret", "inbox_id", "created_at", "enabled"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val webhooks = webhookService.listWebhooks("session-1")

        assertTrue(webhooks.isEmpty())
    }

    @Test
    fun `deleteWebhook removes webhook owned by session`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT") }, any()) } returns
            TursoResult(
                columns = listOf("session_id"),
                rows = listOf(listOf("session-1")),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )
        coEvery { turso.execute(match { it.contains("DELETE") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 0)

        webhookService.deleteWebhook("wh-1", "session-1")

        coVerify { turso.execute(match { it.contains("DELETE FROM webhooks") }, any()) }
    }

    @Test
    fun `deleteWebhook rejects deletion by non-owner session`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT") }, any()) } returns
            TursoResult(
                columns = listOf("session_id"),
                rows = listOf(listOf("session-other")),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<Exception> {
            webhookService.deleteWebhook("wh-1", "session-1")
        }
    }

    @Test
    fun `toggleWebhook updates enabled status`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT") }, any()) } returns
            TursoResult(
                columns = listOf("session_id"),
                rows = listOf(listOf("session-1")),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )
        coEvery { turso.execute(match { it.contains("UPDATE") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 0)

        webhookService.toggleWebhook("wh-1", "session-1", enabled = false)

        coVerify { turso.execute(match { it.contains("UPDATE webhooks") && it.contains("enabled") }, any()) }
    }

    // --- Payload signing tests ---

    @Test
    fun `signPayload produces valid HMAC-SHA256 signature`() {
        val secret = "test-secret-key"
        val payload = """{"address":"test@useblip.email","from":"sender@test.com","subject":"Hello"}"""

        val signature = webhookService.signPayload(payload, secret)

        // Verify independently
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val expected = mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }

        assertEquals(expected, signature)
    }

    @Test
    fun `signPayload produces different signatures for different secrets`() {
        val payload = """{"test": true}"""
        val sig1 = webhookService.signPayload(payload, "secret-1")
        val sig2 = webhookService.signPayload(payload, "secret-2")
        assertNotEquals(sig1, sig2)
    }

    @Test
    fun `signPayload produces different signatures for different payloads`() {
        val secret = "same-secret"
        val sig1 = webhookService.signPayload("""{"a": 1}""", secret)
        val sig2 = webhookService.signPayload("""{"b": 2}""", secret)
        assertNotEquals(sig1, sig2)
    }

    @Test
    fun `signPayload is deterministic`() {
        val secret = "deterministic-secret"
        val payload = """{"address":"x@useblip.email"}"""
        val sig1 = webhookService.signPayload(payload, secret)
        val sig2 = webhookService.signPayload(payload, secret)
        assertEquals(sig1, sig2)
    }

    // --- Payload building tests ---

    @Test
    fun `buildPayload creates correct webhook payload from email`() {
        val email = IngressEmailRequest(
            from = "sender@test.com",
            to = "abc@useblip.email",
            subject = "Test Subject",
            textBody = "Hello world",
            htmlBody = "<p>Hello world</p>",
        )

        val payload = webhookService.buildPayload("abc@useblip.email", email)

        assertEquals("abc@useblip.email", payload.address)
        assertEquals("sender@test.com", payload.from)
        assertEquals("Test Subject", payload.subject)
        assertEquals("Hello world", payload.bodyText)
        assertEquals("<p>Hello world</p>", payload.bodyHtml)
        assertTrue(payload.attachments.isEmpty())
        assertNotNull(payload.receivedAt)
    }

    @Test
    fun `buildPayload handles null text and html bodies`() {
        val email = IngressEmailRequest(
            from = "sender@test.com",
            to = "abc@useblip.email",
            subject = "No body",
        )

        val payload = webhookService.buildPayload("abc@useblip.email", email)

        assertNull(payload.bodyText)
        assertNull(payload.bodyHtml)
    }

    @Test
    fun `buildPayload includes attachment metadata`() {
        val email = IngressEmailRequest(
            from = "sender@test.com",
            to = "abc@useblip.email",
            subject = "With attachment",
            attachments = listOf(
                IngressAttachment("file.pdf", "application/pdf", "dGVzdA==") // "test" base64 = 4 bytes
            )
        )

        val payload = webhookService.buildPayload("abc@useblip.email", email)

        assertEquals(1, payload.attachments.size)
        assertEquals("file.pdf", payload.attachments[0].name)
        assertEquals("application/pdf", payload.attachments[0].contentType)
        assertTrue(payload.attachments[0].size > 0)
    }

    @Test
    fun `buildPayload includes multiple attachments`() {
        val email = IngressEmailRequest(
            from = "sender@test.com",
            to = "abc@useblip.email",
            subject = "Multiple attachments",
            attachments = listOf(
                IngressAttachment("a.pdf", "application/pdf", "dGVzdA=="),
                IngressAttachment("b.png", "image/png", "aW1hZ2U="),
            )
        )

        val payload = webhookService.buildPayload("abc@useblip.email", email)

        assertEquals(2, payload.attachments.size)
        assertEquals("a.pdf", payload.attachments[0].name)
        assertEquals("b.png", payload.attachments[1].name)
    }

    // --- Delivery tracking tests ---

    @Test
    fun `recordDelivery stores successful delivery attempt`() = runTest {
        coEvery { turso.execute(match { it.contains("INSERT INTO webhook_deliveries") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)

        webhookService.recordDelivery("wh-1", "email-1", 200, DeliveryStatus.SUCCESS)

        coVerify { turso.execute(match { it.contains("INSERT INTO webhook_deliveries") }, any()) }
    }

    @Test
    fun `recordDelivery stores failed delivery with null status code`() = runTest {
        coEvery { turso.execute(match { it.contains("INSERT INTO webhook_deliveries") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)

        webhookService.recordDelivery("wh-1", "email-1", null, DeliveryStatus.FAILED)

        coVerify { turso.execute(match { it.contains("INSERT INTO webhook_deliveries") }, any()) }
    }

    @Test
    fun `getDeliveryLog returns deliveries ordered by recency`() = runTest {
        coEvery { turso.execute(match { it.contains("webhook_deliveries") && it.contains("LIMIT 50") }, any()) } returns
            TursoResult(
                columns = listOf("id", "webhook_id", "email_id", "status_code", "attempts", "next_retry_at", "completed_at", "status"),
                rows = listOf(
                    listOf("d-1", "wh-1", "e-1", "200", "1", null, "2026-01-01T00:00:00Z", "SUCCESS"),
                    listOf("d-2", "wh-1", "e-2", null, "3", null, "2026-01-01T01:00:00Z", "FAILED"),
                ),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val log = webhookService.getDeliveryLog("wh-1")

        assertEquals(2, log.size)
        assertEquals(200, log[0].statusCode)
        assertEquals(DeliveryStatus.SUCCESS, log[0].status)
        assertEquals(1, log[0].attempts)
        assertNotNull(log[0].completedAt)
        assertNull(log[1].statusCode)
        assertEquals(DeliveryStatus.FAILED, log[1].status)
        assertEquals(3, log[1].attempts)
    }

    @Test
    fun `getDeliveryLog returns empty list when no deliveries exist`() = runTest {
        coEvery { turso.execute(any(), any()) } returns
            TursoResult(
                columns = listOf("id", "webhook_id", "email_id", "status_code", "attempts", "next_retry_at", "completed_at", "status"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val log = webhookService.getDeliveryLog("wh-1")

        assertTrue(log.isEmpty())
    }

    // --- Webhook matching tests ---

    @Test
    fun `getWebhooksForInbox returns global and inbox-specific webhooks`() = runTest {
        coEvery { turso.execute(match { it.contains("webhooks") && it.contains("inbox_id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "url", "secret", "inbox_id", "created_at", "enabled"),
                rows = listOf(
                    listOf("wh-1", "https://global.com/hook", "s1", null, "2026-01-01T00:00:00Z", "1"),
                    listOf("wh-2", "https://specific.com/hook", "s2", "inbox-1", "2026-01-01T00:00:00Z", "1"),
                ),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val webhooks = webhookService.getWebhooksForInbox("inbox-1", "session-1")

        assertEquals(2, webhooks.size)
        assertNull(webhooks[0].inboxId)
        assertEquals("inbox-1", webhooks[1].inboxId)
    }

    @Test
    fun `getWebhooksForInbox only returns enabled webhooks`() = runTest {
        // SQL filters by enabled = 1, so with no enabled webhooks the DB returns empty
        coEvery { turso.execute(any(), any()) } returns
            TursoResult(
                columns = listOf("id", "url", "secret", "inbox_id", "created_at", "enabled"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val webhooks = webhookService.getWebhooksForInbox("inbox-1", "session-1")

        assertEquals(0, webhooks.size)
    }

    @Test
    fun `getWebhooksForInbox filters by session ownership`() = runTest {
        val sqlSlot = slot<String>()
        val argsSlot = slot<List<TursoValue>>()

        coEvery { turso.execute(capture(sqlSlot), capture(argsSlot)) } returns
            TursoResult(
                columns = listOf("id", "url", "secret", "inbox_id", "created_at", "enabled"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        webhookService.getWebhooksForInbox("inbox-1", "session-1")

        // The query should filter by session_id to ensure ownership
        val textArgs = argsSlot.captured.filterIsInstance<TursoValue.Text>().map { it.value }
        assertTrue(textArgs.contains("session-1"), "Expected query to filter by session-1")
    }

    // --- Retry logic tests ---

    @Test
    fun `shouldRetry returns true when attempts are below max`() {
        val delivery = WebhookDeliveryDTO(
            id = "d-1",
            webhookId = "wh-1",
            emailId = "e-1",
            statusCode = 500,
            attempts = 1,
            status = DeliveryStatus.PENDING,
        )

        assertTrue(webhookService.shouldRetry(delivery))
    }

    @Test
    fun `shouldRetry returns true at 2 attempts`() {
        val delivery = WebhookDeliveryDTO(
            id = "d-1",
            webhookId = "wh-1",
            emailId = "e-1",
            statusCode = 500,
            attempts = 2,
            status = DeliveryStatus.PENDING,
        )

        assertTrue(webhookService.shouldRetry(delivery))
    }

    @Test
    fun `shouldRetry returns false when max attempts reached`() {
        val delivery = WebhookDeliveryDTO(
            id = "d-1",
            webhookId = "wh-1",
            emailId = "e-1",
            statusCode = 500,
            attempts = 3,
            status = DeliveryStatus.PENDING,
        )

        assertFalse(webhookService.shouldRetry(delivery))
    }

    @Test
    fun `shouldRetry returns false for successful deliveries`() {
        val delivery = WebhookDeliveryDTO(
            id = "d-1",
            webhookId = "wh-1",
            emailId = "e-1",
            statusCode = 200,
            attempts = 1,
            status = DeliveryStatus.SUCCESS,
        )

        assertFalse(webhookService.shouldRetry(delivery))
    }

    @Test
    fun `calculateBackoffSeconds uses exponential backoff`() {
        val first = webhookService.calculateBackoffSeconds(1)
        val second = webhookService.calculateBackoffSeconds(2)
        val third = webhookService.calculateBackoffSeconds(3)

        // Each retry should wait longer than the previous
        assertTrue(second > first, "Second backoff ($second) should be greater than first ($first)")
        assertTrue(third > second, "Third backoff ($third) should be greater than second ($second)")
    }

    @Test
    fun `calculateBackoffSeconds returns reasonable durations`() {
        // First retry should be in the range of seconds, not minutes
        val first = webhookService.calculateBackoffSeconds(1)
        assertTrue(first in 1..60, "First backoff should be between 1 and 60 seconds, was $first")

        // Third retry should not exceed 15 minutes
        val third = webhookService.calculateBackoffSeconds(3)
        assertTrue(third <= 900, "Third backoff should not exceed 900 seconds, was $third")
    }
}
