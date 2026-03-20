package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.*
import dev.bmcreations.blip.server.NotFoundException
import dev.bmcreations.blip.server.ForbiddenException
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoValue
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookService(
    private val turso: TursoClient,
    private val httpClient: HttpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
    },
    private val json: Json = Json { encodeDefaults = true },
    private val encryptionService: EncryptionService = EncryptionService(),
    private val inboxService: InboxService? = null,
) {
    private val log = LoggerFactory.getLogger(WebhookService::class.java)
    companion object {
        const val MAX_RETRY_ATTEMPTS = 3
        private val BLOCKED_HOSTNAMES = setOf(
            "localhost",
            "metadata.google.internal",
            "metadata.goog",
            "169.254.169.254",
        )
    }

    private fun validateWebhookUrl(url: String) {
        require(url.length <= 2048) { "Webhook URL must be 2048 characters or less" }

        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid webhook URL")
        }

        require(uri.scheme in listOf("http", "https")) { "Webhook URL must use http or https scheme" }
        require(!uri.host.isNullOrBlank()) { "Webhook URL must have a valid host" }

        val hostname = uri.host.lowercase()
        require(hostname !in BLOCKED_HOSTNAMES) { "Webhook URL host is not allowed" }

        val addresses = try {
            InetAddress.getAllByName(hostname)
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot resolve webhook URL host")
        }

        for (addr in addresses) {
            require(!addr.isSiteLocalAddress) { "Webhook URL must not resolve to a private address" }
            require(!addr.isLoopbackAddress) { "Webhook URL must not resolve to a loopback address" }
            require(!addr.isLinkLocalAddress) { "Webhook URL must not resolve to a link-local address" }
            require(!addr.isAnyLocalAddress) { "Webhook URL must not resolve to a wildcard address" }
        }
    }

    suspend fun createWebhook(sessionId: String, request: CreateWebhookRequest): WebhookDTO {
        validateWebhookUrl(request.url)
        val id = UUID.randomUUID().toString()
        val secret = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val now = Instant.now().toString()

        val inboxId = request.inboxId
        turso.execute(
            "INSERT INTO webhooks (id, session_id, inbox_id, url, secret, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(
                TursoValue.Text(id),
                TursoValue.Text(sessionId),
                if (inboxId != null) TursoValue.Text(inboxId) else TursoValue.Null,
                TursoValue.Text(request.url),
                TursoValue.Text(secret),
                TursoValue.Text(now),
            )
        )

        return WebhookDTO(
            id = id,
            url = request.url,
            secret = secret,
            inboxId = request.inboxId,
            createdAt = now,
            enabled = true,
        )
    }

    suspend fun listWebhooks(sessionId: String): List<WebhookDTO> {
        val result = turso.execute(
            "SELECT id, url, secret, inbox_id, created_at, enabled FROM webhooks WHERE session_id = ?",
            listOf(TursoValue.Text(sessionId))
        )
        return result.toMaps().map { it.toWebhookDTO() }
    }

    suspend fun deleteWebhook(webhookId: String, sessionId: String) {
        val row = turso.execute(
            "SELECT session_id FROM webhooks WHERE id = ?",
            listOf(TursoValue.Text(webhookId))
        ).firstOrNull() ?: throw NotFoundException("Webhook not found")

        if (row["session_id"] != sessionId) {
            throw ForbiddenException("Access denied")
        }

        turso.execute(
            "DELETE FROM webhooks WHERE id = ?",
            listOf(TursoValue.Text(webhookId))
        )
    }

    suspend fun getWebhooksForInbox(inboxId: String, sessionId: String): List<WebhookDTO> {
        val result = turso.execute(
            """
            SELECT id, url, secret, inbox_id, created_at, enabled FROM webhooks
            WHERE session_id = ? AND enabled = 1 AND (inbox_id IS NULL OR inbox_id = ?)
            """.trimIndent(),
            listOf(TursoValue.Text(sessionId), TursoValue.Text(inboxId))
        )
        return result.toMaps().map { it.toWebhookDTO() }
    }

    suspend fun toggleWebhook(webhookId: String, sessionId: String, enabled: Boolean) {
        val row = turso.execute(
            "SELECT session_id FROM webhooks WHERE id = ?",
            listOf(TursoValue.Text(webhookId))
        ).firstOrNull() ?: throw NotFoundException("Webhook not found")

        if (row["session_id"] != sessionId) {
            throw ForbiddenException("Access denied")
        }

        turso.execute(
            "UPDATE webhooks SET enabled = ? WHERE id = ?",
            listOf(TursoValue.Integer(if (enabled) 1 else 0), TursoValue.Text(webhookId))
        )
    }

    fun shouldRetry(delivery: WebhookDeliveryDTO): Boolean {
        if (delivery.status == DeliveryStatus.SUCCESS) return false
        return delivery.attempts < MAX_RETRY_ATTEMPTS
    }

    fun calculateBackoffSeconds(attempt: Int): Long {
        // Exponential backoff: 10s, 60s, 300s
        return when (attempt) {
            1 -> 10L
            2 -> 60L
            3 -> 300L
            else -> 300L
        }
    }

    fun signPayload(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun buildPayload(address: String, email: IngressEmailRequest): WebhookPayload {
        return WebhookPayload(
            address = address,
            from = email.from,
            subject = email.subject,
            bodyText = email.textBody,
            bodyHtml = email.htmlBody,
            receivedAt = Instant.now().toString(),
            attachments = email.attachments.map { att ->
                val decoded = java.util.Base64.getDecoder().decode(att.contentBase64)
                WebhookAttachmentMeta(
                    name = att.name,
                    contentType = att.contentType,
                    size = decoded.size.toLong(),
                )
            },
        )
    }

    suspend fun recordDelivery(
        webhookId: String,
        emailId: String,
        statusCode: Int?,
        status: DeliveryStatus,
    ) {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val completedAt = if (status != DeliveryStatus.PENDING) now else null

        turso.execute(
            """
            INSERT INTO webhook_deliveries (id, webhook_id, email_id, status_code, attempts, completed_at, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                TursoValue.Text(id),
                TursoValue.Text(webhookId),
                TursoValue.Text(emailId),
                if (statusCode != null) TursoValue.Integer(statusCode.toLong()) else TursoValue.Null,
                TursoValue.Integer(1),
                if (completedAt != null) TursoValue.Text(completedAt) else TursoValue.Null,
                TursoValue.Text(status.name),
                TursoValue.Text(now),
            )
        )
    }

    suspend fun getDeliveryLog(webhookId: String): List<WebhookDeliveryDTO> {
        val result = turso.execute(
            """
            SELECT id, webhook_id, email_id, status_code, attempts, next_retry_at, completed_at, status
            FROM webhook_deliveries WHERE webhook_id = ? ORDER BY created_at DESC LIMIT 50
            """.trimIndent(),
            listOf(TursoValue.Text(webhookId))
        )
        return result.toMaps().map { row ->
            WebhookDeliveryDTO(
                id = row["id"]!!,
                webhookId = row["webhook_id"]!!,
                emailId = row["email_id"]!!,
                statusCode = row["status_code"]?.toIntOrNull(),
                attempts = row["attempts"]?.toIntOrNull() ?: 0,
                nextRetryAt = row["next_retry_at"],
                completedAt = row["completed_at"],
                status = DeliveryStatus.valueOf(row["status"] ?: "PENDING"),
            )
        }
    }

    /**
     * Fire webhooks for a newly ingested email. Runs delivery attempts
     * concurrently in the background — does not block the ingress response.
     */
    suspend fun deliverWebhooks(
        inboxId: String,
        sessionId: String,
        emailId: String,
        address: String,
        request: IngressEmailRequest,
    ) = coroutineScope {
        val webhooks = getWebhooksForInbox(inboxId, sessionId)
        if (webhooks.isEmpty()) return@coroutineScope

        val payload = buildPayload(address, request)
        val payloadJson = json.encodeToString(payload)

        for (webhook in webhooks) {
            launch {
                val signature = signPayload(payloadJson, webhook.secret)

                val result = try {
                    val response = httpClient.post(webhook.url) {
                        contentType(ContentType.Application.Json)
                        header("X-Blip-Signature", signature)
                        header("X-Blip-Event", "email.received")
                        setBody(payloadJson)
                    }
                    val code = response.status.value
                    val status = if (response.status.isSuccess()) DeliveryStatus.SUCCESS else DeliveryStatus.FAILED
                    code to status
                } catch (e: Exception) {
                    log.warn("Webhook delivery failed for ${webhook.id}: ${e.message}")
                    null to DeliveryStatus.FAILED
                }

                try {
                    recordDelivery(webhook.id, emailId, result.first, result.second)
                } catch (e: Exception) {
                    log.error("Failed to record webhook delivery for ${webhook.id}", e)
                }
            }
        }
    }

    suspend fun retryFailedDeliveries() {
        val failedDeliveries = turso.execute(
            """
            SELECT wd.id, wd.webhook_id, wd.email_id, wd.attempts
            FROM webhook_deliveries wd
            WHERE wd.status = 'FAILED' AND wd.attempts < ?
              AND (wd.next_retry_at IS NULL OR wd.next_retry_at <= datetime('now'))
            """.trimIndent(),
            listOf(TursoValue.Integer(MAX_RETRY_ATTEMPTS.toLong()))
        ).toMaps()

        for (delivery in failedDeliveries) {
            try {
                val deliveryId = delivery["id"] ?: continue
                val webhookId = delivery["webhook_id"] ?: continue
                val emailId = delivery["email_id"] ?: continue
                val attempts = delivery["attempts"]?.toIntOrNull() ?: 0

                // Fetch webhook details
                val webhook = turso.execute(
                    "SELECT url, secret FROM webhooks WHERE id = ? AND enabled = 1",
                    listOf(TursoValue.Text(webhookId))
                ).firstOrNull() ?: continue

                val url = webhook["url"] ?: continue
                val secret = webhook["secret"] ?: continue

                // Fetch original email payload
                val email = turso.execute(
                    """
                    SELECT e.from_addr, e.subject, e.text_body, e.html_body, e.inbox_id, i.address
                    FROM emails e JOIN inboxes i ON e.inbox_id = i.id
                    WHERE e.id = ?
                    """.trimIndent(),
                    listOf(TursoValue.Text(emailId))
                ).firstOrNull() ?: continue

                // Decrypt bodies if encryption key is available
                val encryptionKey = email["inbox_id"]?.let { inboxService?.getEncryptionKey(it) }
                if (encryptionKey == null && inboxService != null) {
                    // Inbox deleted — key gone, mark permanently failed
                    turso.execute(
                        "UPDATE webhook_deliveries SET status = 'FAILED', attempts = ?, completed_at = ? WHERE id = ?",
                        listOf(
                            TursoValue.Integer(MAX_RETRY_ATTEMPTS.toLong()),
                            TursoValue.Text(Instant.now().toString()),
                            TursoValue.Text(deliveryId),
                        )
                    )
                    log.info("Skipping webhook retry for delivery $deliveryId: inbox deleted, encryption key unavailable")
                    continue
                }
                val textBody = email["text_body"]?.let { raw ->
                    if (encryptionKey != null) {
                        try { encryptionService.decrypt(raw, encryptionKey) } catch (_: Exception) { raw }
                    } else raw
                }
                val htmlBody = email["html_body"]?.let { raw ->
                    if (encryptionKey != null) {
                        try { encryptionService.decrypt(raw, encryptionKey) } catch (_: Exception) { raw }
                    } else raw
                }

                val payload = WebhookPayload(
                    address = email["address"] ?: "",
                    from = email["from_addr"] ?: "",
                    subject = email["subject"] ?: "",
                    bodyText = textBody,
                    bodyHtml = htmlBody,
                    receivedAt = Instant.now().toString(),
                    attachments = emptyList(),
                )
                val payloadJson = json.encodeToString(payload)
                val signature = signPayload(payloadJson, secret)

                val newAttempts = attempts + 1
                val result = try {
                    val response = httpClient.post(url) {
                        contentType(ContentType.Application.Json)
                        header("X-Blip-Signature", signature)
                        header("X-Blip-Event", "email.received")
                        setBody(payloadJson)
                    }
                    val code = response.status.value
                    val status = if (response.status.isSuccess()) DeliveryStatus.SUCCESS else DeliveryStatus.FAILED
                    code to status
                } catch (e: Exception) {
                    log.warn("Webhook retry failed for delivery $deliveryId: ${e.message}")
                    null to DeliveryStatus.FAILED
                }

                val now = Instant.now().toString()
                if (result.second == DeliveryStatus.SUCCESS) {
                    turso.execute(
                        "UPDATE webhook_deliveries SET attempts = ?, status = 'SUCCESS', status_code = ?, completed_at = ?, next_retry_at = NULL WHERE id = ?",
                        listOf(
                            TursoValue.Integer(newAttempts.toLong()),
                            if (result.first != null) TursoValue.Integer(result.first!!.toLong()) else TursoValue.Null,
                            TursoValue.Text(now),
                            TursoValue.Text(deliveryId),
                        )
                    )
                } else {
                    val nextRetryAt = if (newAttempts < MAX_RETRY_ATTEMPTS) {
                        Instant.now().plusSeconds(calculateBackoffSeconds(newAttempts)).toString()
                    } else null

                    turso.execute(
                        "UPDATE webhook_deliveries SET attempts = ?, status = 'FAILED', status_code = ?, next_retry_at = ?, completed_at = ? WHERE id = ?",
                        listOf(
                            TursoValue.Integer(newAttempts.toLong()),
                            if (result.first != null) TursoValue.Integer(result.first!!.toLong()) else TursoValue.Null,
                            if (nextRetryAt != null) TursoValue.Text(nextRetryAt) else TursoValue.Null,
                            if (newAttempts >= MAX_RETRY_ATTEMPTS) TursoValue.Text(now) else TursoValue.Null,
                            TursoValue.Text(deliveryId),
                        )
                    )
                }

                log.info("Webhook retry for delivery $deliveryId: attempt $newAttempts, status=${result.second}")
            } catch (e: Exception) {
                log.error("Error retrying webhook delivery ${delivery["id"]}", e)
            }
        }
    }

    private fun Map<String, String?>.toWebhookDTO(): WebhookDTO {
        return WebhookDTO(
            id = this["id"]!!,
            url = this["url"]!!,
            secret = this["secret"]!!,
            inboxId = this["inbox_id"],
            createdAt = this["created_at"]!!,
            enabled = this["enabled"] == "1",
        )
    }
}
