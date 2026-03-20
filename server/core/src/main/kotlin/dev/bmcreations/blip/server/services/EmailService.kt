package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.*
import dev.bmcreations.blip.server.ForbiddenException
import dev.bmcreations.blip.server.NotFoundException
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoValue
import dev.bmcreations.blip.server.sse.SseManager
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class EmailService(
    private val turso: TursoClient,
    private val sseManager: SseManager,
    private val encryptionService: EncryptionService = EncryptionService(),
    private val inboxService: InboxService? = null,
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(EmailService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Ingest an email into an inbox.
     * @param stripAttachments If true, attachments are dropped (FREE tier).
     * @param maxAttachmentBytes Max size per attachment in bytes. Oversized attachments are skipped. 0 = no limit.
     */
    suspend fun ingestEmail(
        inboxId: String,
        request: IngressEmailRequest,
        stripAttachments: Boolean = false,
        maxAttachmentBytes: Long = 0,
    ): EmailSummaryDTO {
        val emailId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val headersJson = Json.encodeToString(request.headers)

        val textBody = request.textBody
        val htmlBody = request.htmlBody
        val preview = (textBody ?: "").take(120)

        // Encrypt bodies if encryption key is available
        val encryptionKey = inboxService?.getEncryptionKey(inboxId)
        val storedTextBody = if (textBody != null && encryptionKey != null) {
            encryptionService.encrypt(textBody, encryptionKey)
        } else textBody
        val storedHtmlBody = if (htmlBody != null && encryptionKey != null) {
            encryptionService.encrypt(htmlBody, encryptionKey)
        } else htmlBody

        turso.execute(
            """
            INSERT INTO emails (id, inbox_id, from_addr, to_addr, subject, text_body, html_body, headers, received_at, preview)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                TursoValue.Text(emailId),
                TursoValue.Text(inboxId),
                TursoValue.Text(request.from),
                TursoValue.Text(request.to),
                TursoValue.Text(request.subject),
                if (storedTextBody != null) TursoValue.Text(storedTextBody) else TursoValue.Null,
                if (storedHtmlBody != null) TursoValue.Text(storedHtmlBody) else TursoValue.Null,
                TursoValue.Text(headersJson),
                TursoValue.Text(now),
                TursoValue.Text(preview),
            )
        )

        // Store attachments (unless stripped for FREE tier)
        if (!stripAttachments) {
            for (att in request.attachments) {
                val attId = UUID.randomUUID().toString()
                val decoded = java.util.Base64.getDecoder().decode(att.contentBase64)
                if (maxAttachmentBytes > 0 && decoded.size > maxAttachmentBytes) {
                    logger.warn(
                        "Skipping oversized attachment '{}' ({} bytes, limit {} bytes) on email {}",
                        att.name, decoded.size, maxAttachmentBytes, emailId
                    )
                    continue
                }
                val storedData = if (encryptionKey != null) {
                    java.util.Base64.getEncoder().encodeToString(
                        encryptionService.encryptBytes(decoded, encryptionKey)
                    )
                } else att.contentBase64
                turso.execute(
                    "INSERT INTO attachments (id, email_id, name, content_type, size, data) VALUES (?, ?, ?, ?, ?, ?)",
                    listOf(
                        TursoValue.Text(attId),
                        TursoValue.Text(emailId),
                        TursoValue.Text(att.name),
                        TursoValue.Text(att.contentType),
                        TursoValue.Integer(decoded.size.toLong()),
                        TursoValue.Blob(storedData),
                    )
                )
            }
        }

        val summary = EmailSummaryDTO(
            id = emailId,
            from = request.from,
            subject = request.subject,
            receivedAt = now,
            preview = preview,
        )

        // Publish to SSE
        sseManager.publish(inboxId, summary)

        return summary
    }

    suspend fun getEmail(emailId: String): EmailDetailDTO {
        val row = turso.execute(
            "SELECT * FROM emails WHERE id = ?",
            listOf(TursoValue.Text(emailId))
        ).firstOrNull() ?: throw NotFoundException("Email not found")

        val headers: Map<String, String> = try {
            json.decodeFromString(row["headers"] ?: "{}")
        } catch (_: Exception) {
            emptyMap()
        }

        val attachmentRows = turso.execute(
            "SELECT name, content_type, size FROM attachments WHERE email_id = ?",
            listOf(TursoValue.Text(emailId))
        )

        val attachments = attachmentRows.toMaps().map { att ->
            AttachmentDTO(
                name = att["name"]!!,
                contentType = att["content_type"]!!,
                size = att["size"]?.toLongOrNull() ?: 0,
            )
        }

        // Decrypt bodies if encryption key is available
        val encryptionKey = inboxService?.getEncryptionKey(row["inbox_id"]!!)
        val textBody = row["text_body"]?.let { raw ->
            if (encryptionKey != null) {
                try { encryptionService.decrypt(raw, encryptionKey) } catch (_: Exception) { raw }
            } else raw
        }
        val htmlBody = row["html_body"]?.let { raw ->
            if (encryptionKey != null) {
                try { encryptionService.decrypt(raw, encryptionKey) } catch (_: Exception) { raw }
            } else raw
        }

        val replyRows = turso.execute(
            "SELECT id, body, status, created_at FROM replies WHERE email_id = ? ORDER BY created_at ASC",
            listOf(TursoValue.Text(emailId))
        )
        val replies = replyRows.toMaps().map { r ->
            ReplyDTO(
                id = r["id"]!!,
                body = r["body"]!!,
                status = r["status"] ?: "pending",
                createdAt = r["created_at"]!!,
            )
        }

        return EmailDetailDTO(
            id = row["id"]!!,
            inboxId = row["inbox_id"]!!,
            from = row["from_addr"] ?: "",
            to = row["to_addr"] ?: "",
            subject = row["subject"] ?: "",
            textBody = textBody,
            htmlBody = htmlBody,
            headers = headers,
            receivedAt = row["received_at"]!!,
            attachments = attachments,
            replies = replies,
        )
    }

    suspend fun getAttachment(emailId: String, name: String, inboxId: String? = null): Pair<String, ByteArray> {
        val row = turso.execute(
            "SELECT content_type, data FROM attachments WHERE email_id = ? AND name = ?",
            listOf(TursoValue.Text(emailId), TursoValue.Text(name))
        ).firstOrNull() ?: throw NotFoundException("Attachment not found")

        val contentType = row["content_type"] ?: "application/octet-stream"
        val base64Data = row["data"] ?: throw NotFoundException("Attachment data missing")
        val bytes = java.util.Base64.getDecoder().decode(base64Data)

        // Decrypt if encryption key is available
        val encryptionKey = inboxId?.let { inboxService?.getEncryptionKey(it) }
        val decrypted = if (encryptionKey != null) {
            try { encryptionService.decryptBytes(bytes, encryptionKey) } catch (_: Exception) { bytes }
        } else bytes

        return contentType to decrypted
    }

    suspend fun getLatestEmail(inboxId: String): EmailDetailDTO? {
        val row = turso.execute(
            "SELECT id FROM emails WHERE inbox_id = ? ORDER BY received_at DESC LIMIT 1",
            listOf(TursoValue.Text(inboxId))
        ).firstOrNull() ?: return null

        return getEmail(row["id"]!!)
    }

    suspend fun getEmailInboxId(emailId: String): String {
        val row = turso.execute(
            "SELECT inbox_id FROM emails WHERE id = ?",
            listOf(TursoValue.Text(emailId))
        ).firstOrNull() ?: throw NotFoundException("Email not found")
        return row["inbox_id"]!!
    }
}
