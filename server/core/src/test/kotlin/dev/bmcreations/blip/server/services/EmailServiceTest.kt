package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.EmailSummary
import dev.bmcreations.blip.models.IngressAttachment
import dev.bmcreations.blip.models.IngressEmailRequest
import dev.bmcreations.blip.server.NotFoundException
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoResult
import dev.bmcreations.blip.server.db.TursoValue
import dev.bmcreations.blip.server.sse.SseManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.*

class EmailServiceTest {

    private lateinit var turso: TursoClient
    private lateinit var sseManager: SseManager
    private lateinit var service: EmailService

    private val inboxId = "test-inbox-id"

    @BeforeTest
    fun setup() {
        turso = mockk()
        sseManager = mockk()
        service = EmailService(turso, sseManager)
    }

    private fun simpleEmailRequest(
        from: String = "sender@example.com",
        to: String = "swift-fox-42@useblip.email",
        subject: String = "Test Subject",
        textBody: String? = "Hello, world!",
        htmlBody: String? = "<p>Hello, world!</p>",
        headers: Map<String, String> = mapOf("Message-ID" to "<abc@example.com>"),
        attachments: List<IngressAttachment> = emptyList(),
    ) = IngressEmailRequest(
        from = from,
        to = to,
        subject = subject,
        textBody = textBody,
        htmlBody = htmlBody,
        headers = headers,
        attachments = attachments,
    )

    private fun stubInsertEmail() {
        coEvery { turso.execute(match { it.contains("INSERT INTO emails") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)
    }

    private fun stubInsertAttachment() {
        coEvery { turso.execute(match { it.contains("INSERT INTO attachments") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)
    }

    private fun stubRepliesQuery() {
        coEvery { turso.execute(match { it.contains("FROM replies WHERE email_id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "body", "status", "created_at"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )
    }

    private fun stubSsePublish() {
        coEvery { sseManager.publish(any(), any()) } just Runs
    }

    // -- ingestEmail --

    @Test
    fun `ingestEmail stores email and returns summary`() = runTest {
        stubInsertEmail()
        stubSsePublish()

        val request = simpleEmailRequest()
        val summary = service.ingestEmail(inboxId, request)

        assertNotNull(summary.id)
        assertTrue(summary.id.isNotBlank())
        assertEquals("sender@example.com", summary.from)
        assertEquals("Test Subject", summary.subject)
        assertEquals("Hello, world!", summary.preview)
        assertNotNull(summary.receivedAt)

        coVerify {
            turso.execute(match { it.contains("INSERT INTO emails") }, match { args ->
                args.size == 10 &&
                    (args[1] as TursoValue.Text).value == inboxId &&
                    (args[2] as TursoValue.Text).value == "sender@example.com" &&
                    (args[4] as TursoValue.Text).value == "Test Subject"
            })
        }
    }

    @Test
    fun `ingestEmail publishes to SSE manager`() = runTest {
        stubInsertEmail()
        stubSsePublish()

        val request = simpleEmailRequest()
        val summary = service.ingestEmail(inboxId, request)

        coVerify {
            sseManager.publish(inboxId, match<EmailSummary> { it.id == summary.id })
        }
    }

    @Test
    fun `ingestEmail stores attachments`() = runTest {
        stubInsertEmail()
        stubInsertAttachment()
        stubSsePublish()

        val fileContent = "hello attachment"
        val base64Content = Base64.getEncoder().encodeToString(fileContent.toByteArray())

        val request = simpleEmailRequest(
            attachments = listOf(
                IngressAttachment(
                    name = "test.txt",
                    contentType = "text/plain",
                    contentBase64 = base64Content,
                )
            )
        )

        service.ingestEmail(inboxId, request)

        coVerify {
            turso.execute(match { it.contains("INSERT INTO attachments") }, match { args ->
                args.size == 6 &&
                    (args[2] as TursoValue.Text).value == "test.txt" &&
                    (args[3] as TursoValue.Text).value == "text/plain" &&
                    (args[4] as TursoValue.Integer).value == fileContent.toByteArray().size.toLong() &&
                    (args[5] as TursoValue.Blob).base64 == base64Content
            })
        }
    }

    @Test
    fun `ingestEmail with stripAttachments skips attachment storage`() = runTest {
        stubInsertEmail()
        stubSsePublish()

        val base64Content = Base64.getEncoder().encodeToString("data".toByteArray())
        val request = simpleEmailRequest(
            attachments = listOf(
                IngressAttachment("file.pdf", "application/pdf", base64Content)
            )
        )

        val summary = service.ingestEmail(inboxId, request, stripAttachments = true)

        assertNotNull(summary.id)
        // Verify no attachment INSERT was executed
        coVerify(exactly = 0) {
            turso.execute(match { it.contains("INSERT INTO attachments") }, any())
        }
    }

    // -- getEmail --

    @Test
    fun `getEmail returns full detail`() = runTest {
        val now = "2026-01-15T10:30:00Z"

        coEvery { turso.execute(match { it.contains("FROM emails WHERE id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "inbox_id", "from_addr", "to_addr", "subject", "text_body", "html_body", "headers", "received_at"),
                rows = listOf(listOf(
                    "email-1", inboxId, "alice@example.com", "inbox@useblip.email",
                    "Hello", "Body text", "<p>Body</p>", """{"Message-ID":"<abc@example.com>"}""", now
                )),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        // Stub attachments query (no attachments)
        coEvery { turso.execute(match { it.contains("FROM attachments WHERE email_id") }, any()) } returns
            TursoResult(
                columns = listOf("name", "content_type", "size"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        stubRepliesQuery()

        val email = service.getEmail("email-1")

        assertEquals("email-1", email.id)
        assertEquals(inboxId, email.inboxId)
        assertEquals("alice@example.com", email.from)
        assertEquals("inbox@useblip.email", email.to)
        assertEquals("Hello", email.subject)
        assertEquals("Body text", email.textBody)
        assertEquals("<p>Body</p>", email.htmlBody)
        assertEquals(now, email.receivedAt)
        assertTrue(email.attachments.isEmpty())
    }

    @Test
    fun `getEmail throws NotFoundException for missing`() = runTest {
        coEvery { turso.execute(match { it.contains("FROM emails WHERE id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "inbox_id", "from_addr", "to_addr", "subject", "text_body", "html_body", "headers", "received_at"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<NotFoundException> {
            service.getEmail("nonexistent")
        }
    }

    @Test
    fun `getEmail includes attachments`() = runTest {
        val now = "2026-01-15T10:30:00Z"

        coEvery { turso.execute(match { it.contains("FROM emails WHERE id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "inbox_id", "from_addr", "to_addr", "subject", "text_body", "html_body", "headers", "received_at"),
                rows = listOf(listOf(
                    "email-1", inboxId, "alice@example.com", "inbox@useblip.email",
                    "Hello", "Body", null, "{}", now
                )),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        coEvery { turso.execute(match { it.contains("FROM attachments WHERE email_id") }, any()) } returns
            TursoResult(
                columns = listOf("name", "content_type", "size"),
                rows = listOf(
                    listOf("report.pdf", "application/pdf", "102400"),
                    listOf("image.png", "image/png", "51200"),
                ),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        stubRepliesQuery()

        val email = service.getEmail("email-1")

        assertEquals(2, email.attachments.size)
        assertEquals("report.pdf", email.attachments[0].name)
        assertEquals("application/pdf", email.attachments[0].contentType)
        assertEquals(102400L, email.attachments[0].size)
        assertEquals("image.png", email.attachments[1].name)
        assertEquals(51200L, email.attachments[1].size)
    }

    @Test
    fun `getEmail parses headers JSON`() = runTest {
        val now = "2026-01-15T10:30:00Z"
        val headersJson = """{"Message-ID":"<abc@example.com>","X-Mailer":"TestMailer"}"""

        coEvery { turso.execute(match { it.contains("FROM emails WHERE id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "inbox_id", "from_addr", "to_addr", "subject", "text_body", "html_body", "headers", "received_at"),
                rows = listOf(listOf(
                    "email-1", inboxId, "alice@example.com", "inbox@useblip.email",
                    "Hello", "Body", null, headersJson, now
                )),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        coEvery { turso.execute(match { it.contains("FROM attachments WHERE email_id") }, any()) } returns
            TursoResult(
                columns = listOf("name", "content_type", "size"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        stubRepliesQuery()

        val email = service.getEmail("email-1")

        assertEquals(2, email.headers.size)
        assertEquals("<abc@example.com>", email.headers["Message-ID"])
        assertEquals("TestMailer", email.headers["X-Mailer"])
    }

    // -- getAttachment --

    @Test
    fun `getAttachment returns content type and bytes`() = runTest {
        val fileContent = "file content here"
        val base64Data = Base64.getEncoder().encodeToString(fileContent.toByteArray())

        coEvery { turso.execute(match { it.contains("FROM attachments WHERE email_id") && it.contains("AND name") }, any()) } returns
            TursoResult(
                columns = listOf("content_type", "data"),
                rows = listOf(listOf("text/plain", base64Data)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val (contentType, bytes) = service.getAttachment("email-1", "test.txt")

        assertEquals("text/plain", contentType)
        assertEquals(fileContent, String(bytes))
    }

    @Test
    fun `getAttachment throws NotFoundException for missing`() = runTest {
        coEvery { turso.execute(match { it.contains("FROM attachments WHERE email_id") && it.contains("AND name") }, any()) } returns
            TursoResult(
                columns = listOf("content_type", "data"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<NotFoundException> {
            service.getAttachment("email-1", "nonexistent.txt")
        }
    }

    // -- getEmailInboxId --

    @Test
    fun `getEmailInboxId returns inbox id`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT inbox_id FROM emails") }, any()) } returns
            TursoResult(
                columns = listOf("inbox_id"),
                rows = listOf(listOf(inboxId)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val result = service.getEmailInboxId("email-1")

        assertEquals(inboxId, result)
    }

    @Test
    fun `getEmailInboxId throws NotFoundException for missing`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT inbox_id FROM emails") }, any()) } returns
            TursoResult(
                columns = listOf("inbox_id"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<NotFoundException> {
            service.getEmailInboxId("nonexistent")
        }
    }
}
