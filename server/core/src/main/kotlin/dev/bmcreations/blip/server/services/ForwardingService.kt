package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.ForwardingRule
import dev.bmcreations.blip.server.NotFoundException
import dev.bmcreations.blip.server.ForbiddenException
import dev.bmcreations.blip.server.TierLimitException
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoValue
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

class ForwardingService(
    private val turso: TursoClient,
    private val resendApiKey: String,
) {
    private val log = LoggerFactory.getLogger(ForwardingService::class.java)
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    companion object {
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$")
    }

    suspend fun createRule(
        inboxId: String,
        sessionId: String,
        forwardToEmail: String,
        maxRules: Int,
    ): ForwardingRule {
        require(EMAIL_REGEX.matches(forwardToEmail)) { "Invalid email address" }

        val countResult = turso.execute(
            "SELECT COUNT(*) as cnt FROM forwarding_rules WHERE inbox_id = ?",
            listOf(TursoValue.Text(inboxId))
        ).firstOrNull()
        val currentCount = countResult?.get("cnt")?.toLongOrNull() ?: 0
        if (currentCount >= maxRules) {
            throw TierLimitException("Forwarding rule limit reached ($maxRules per inbox)")
        }

        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()

        turso.execute(
            "INSERT INTO forwarding_rules (id, inbox_id, session_id, forward_to_email, created_at) VALUES (?, ?, ?, ?, ?)",
            listOf(
                TursoValue.Text(id),
                TursoValue.Text(inboxId),
                TursoValue.Text(sessionId),
                TursoValue.Text(forwardToEmail),
                TursoValue.Text(now),
            )
        )

        return ForwardingRule(
            id = id,
            inboxId = inboxId,
            forwardToEmail = forwardToEmail,
            createdAt = now,
        )
    }

    suspend fun listRules(inboxId: String): List<ForwardingRule> {
        val result = turso.execute(
            "SELECT id, inbox_id, forward_to_email, created_at FROM forwarding_rules WHERE inbox_id = ?",
            listOf(TursoValue.Text(inboxId))
        )
        return result.toMaps().map { row ->
            ForwardingRule(
                id = row["id"]!!,
                inboxId = row["inbox_id"]!!,
                forwardToEmail = row["forward_to_email"]!!,
                createdAt = row["created_at"]!!,
            )
        }
    }

    suspend fun deleteRule(ruleId: String, sessionId: String) {
        val row = turso.execute(
            "SELECT session_id FROM forwarding_rules WHERE id = ?",
            listOf(TursoValue.Text(ruleId))
        ).firstOrNull() ?: throw NotFoundException("Forwarding rule not found")

        if (row["session_id"] != sessionId) {
            throw ForbiddenException("Access denied")
        }

        turso.execute(
            "DELETE FROM forwarding_rules WHERE id = ?",
            listOf(TursoValue.Text(ruleId))
        )
    }

    suspend fun getRulesForInbox(inboxId: String): List<ForwardingRule> {
        return listRules(inboxId)
    }

    suspend fun forwardEmail(
        fromAddress: String,
        subject: String,
        textBody: String?,
        htmlBody: String?,
        forwardTo: String,
    ) {
        if (resendApiKey.isBlank()) {
            log.warn("Skipping forward to $forwardTo — RESEND_API_KEY not configured")
            return
        }
        try {
            val subjectJson = Json.encodeToString("Fwd: $subject")
            val bodyParts = buildString {
                append("""{"from": "$fromAddress", "to": ["$forwardTo"], "subject": $subjectJson""")
                if (textBody != null) {
                    val textJson = Json.encodeToString(textBody)
                    append(""", "text": $textJson""")
                }
                if (htmlBody != null) {
                    val htmlJson = Json.encodeToString(htmlBody)
                    append(""", "html": $htmlJson""")
                }
                append("}")
            }

            httpClient.post("https://api.resend.com/emails") {
                header("Authorization", "Bearer $resendApiKey")
                contentType(ContentType.Application.Json)
                setBody(bodyParts)
            }
        } catch (e: Exception) {
            log.warn("Failed to forward email to $forwardTo: ${e.message}")
        }
    }
}
