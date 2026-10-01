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
import io.ktor.client.statement.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*

class ForwardingService(
    private val turso: TursoClient,
    private val resendApiKey: String,
    private val forwardFrom: String = System.getenv("FORWARD_FROM") ?: DEFAULT_FORWARD_FROM,
    private val dailyCap: Int = System.getenv("FORWARD_DAILY_CAP")?.toIntOrNull() ?: DEFAULT_DAILY_CAP,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = LoggerFactory.getLogger(ForwardingService::class.java)
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    companion object {
        const val DEFAULT_FORWARD_FROM = "Blip Forwarding <forward@mail.useblip.email>"
        const val DEFAULT_DAILY_CAP = 100

        /**
         * Resend payload built with a JSON encoder so no field can break out of its string.
         * `from` is a Blip address (Resend rejects unverified sender domains); the original sender
         * goes in reply_to.
         */
        internal fun buildResendPayload(
            forwardFrom: String,
            originalSender: String,
            subject: String,
            textBody: String?,
            htmlBody: String?,
            forwardTo: String,
        ): JsonObject = buildJsonObject {
            put("from", forwardFrom)
            putJsonArray("to") { add(forwardTo) }
            put("reply_to", originalSender)
            put("subject", "Fwd: $subject")
            if (textBody != null) put("text", textBody)
            if (htmlBody != null) put("html", htmlBody)
        }

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

    /**
     * Forwards an ingested email to the inbox's rules. Skips when the inbox owner's current tier no
     * longer includes forwarding, and stops once the owner's daily cap is reached. Runs in the
     * background so ingest does not wait on Resend.
     */
    fun forwardForInbox(
        inboxId: String,
        fromAddress: String,
        subject: String,
        textBody: String?,
        htmlBody: String?,
    ) {
        scope.launch {
            try {
                val owner = turso.resolveDeliveryOwner(inboxId)
                if (owner.tier.forwardingRules == 0) return@launch
                val rules = getRulesForInbox(inboxId)
                for (rule in rules) {
                    if (!tryConsumeDailyQuota(owner.ownerKey ?: "inbox:$inboxId")) {
                        log.warn("Forwarding cap ($dailyCap/day) reached for ${owner.ownerKey}; dropping forward to inbox $inboxId")
                        break
                    }
                    forwardEmail(fromAddress, subject, textBody, htmlBody, rule.forwardToEmail)
                }
            } catch (e: Exception) {
                log.error("Forwarding failed for inbox $inboxId", e)
            }
        }
    }

    /** Atomically counts one forward against today's cap. False when the cap is already reached. */
    suspend fun tryConsumeDailyQuota(ownerKey: String): Boolean {
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val row = turso.execute(
            """
            INSERT INTO forwarding_usage (owner_key, day, count) VALUES (?, ?, 1)
            ON CONFLICT(owner_key, day) DO UPDATE SET count = count + 1 WHERE count < ?
            RETURNING count
            """.trimIndent(),
            listOf(TursoValue.Text(ownerKey), TursoValue.Text(today), TursoValue.Integer(dailyCap.toLong()))
        ).firstOrNull()
        return row != null
    }

    suspend fun forwardEmail(
        fromAddress: String,
        subject: String,
        textBody: String?,
        htmlBody: String?,
        forwardTo: String,
    ) {
        if (resendApiKey.isBlank()) {
            log.error("Skipping forward to $forwardTo: RESEND_API_KEY not configured")
            return
        }
        try {
            val payload = buildResendPayload(forwardFrom, fromAddress, subject, textBody, htmlBody, forwardTo)
            val response = httpClient.post("https://api.resend.com/emails") {
                header("Authorization", "Bearer $resendApiKey")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            if (!response.status.isSuccess()) {
                log.error("Resend rejected forward to $forwardTo: status=${response.status.value} body=${response.bodyAsText().take(500)}")
            }
        } catch (e: Exception) {
            log.error("Failed to forward email to $forwardTo: ${e.message}")
        }
    }
}
