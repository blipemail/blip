package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoValue
import dev.bmcreations.blip.server.sse.SseManager
import io.ktor.server.application.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Instant

class CleanupService(
    private val turso: TursoClient,
    private val webhookService: WebhookService? = null,
    private val sseManager: SseManager? = null,
    private val cleanupContributors: List<CleanupContributor> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(CleanupService::class.java)

    private companion object {
        const val FREE_RETENTION_SECONDS = 86_400L
        const val PRO_RETENTION_SECONDS = 2_592_000L
        const val AGENT_RETENTION_SECONDS = 3_600L
    }

    fun startScheduled(app: Application) {
        app.launch {
            while (isActive) {
                delay(60_000) // Run every minute
                try {
                    cleanup()
                } catch (e: Exception) {
                    logger.error("Cleanup failed", e)
                }
            }
        }
    }

    suspend fun cleanup() {
        // Timestamps are stored as ISO-8601 (Instant.toString()), so compare against bound
        // ISO values. datetime('now') yields "YYYY-MM-DD HH:MM:SS"; since 'T' > ' ', an ISO
        // value on the same day sorts after it, delaying expiry by up to 24h.
        val now = Instant.now()
        val nowIso = now.toString()

        // Delete emails past their tier's retention period. The tier comes from the owning
        // user when the inbox has one, else the creating session, else FREE
        // (same rule as InboxService.resolveTier).
        val retentionResult = turso.execute(
            """
            DELETE FROM emails WHERE id IN (
                SELECT e.id FROM emails e
                JOIN inboxes i ON e.inbox_id = i.id
                LEFT JOIN users u ON u.id = i.user_id
                LEFT JOIN sessions s ON s.id = i.session_id
                WHERE ((CASE
                          WHEN u.id IS NOT NULL THEN
                            CASE WHEN u.has_pro = 1 THEN 'PRO' WHEN u.has_agent = 1 THEN 'AGENT' ELSE 'FREE' END
                          ELSE COALESCE(s.tier, 'FREE')
                        END) = 'FREE' AND e.received_at < ?)
                   OR ((CASE
                          WHEN u.id IS NOT NULL THEN
                            CASE WHEN u.has_pro = 1 THEN 'PRO' WHEN u.has_agent = 1 THEN 'AGENT' ELSE 'FREE' END
                          ELSE COALESCE(s.tier, 'FREE')
                        END) = 'PRO' AND e.received_at < ?)
                   OR ((CASE
                          WHEN u.id IS NOT NULL THEN
                            CASE WHEN u.has_pro = 1 THEN 'PRO' WHEN u.has_agent = 1 THEN 'AGENT' ELSE 'FREE' END
                          ELSE COALESCE(s.tier, 'FREE')
                        END) = 'AGENT' AND e.received_at < ?)
            )
            """.trimIndent(),
            listOf(
                TursoValue.Text(now.minusSeconds(FREE_RETENTION_SECONDS).toString()),
                TursoValue.Text(now.minusSeconds(PRO_RETENTION_SECONDS).toString()),
                TursoValue.Text(now.minusSeconds(AGENT_RETENTION_SECONDS).toString()),
            )
        )
        if (retentionResult.affectedRowCount > 0) {
            logger.info("Deleted ${retentionResult.affectedRowCount} emails past retention")
        }

        // Seal expired sniper windows
        val sealResult = turso.execute(
            "UPDATE inboxes SET sniper_sealed = 1 WHERE sniper_closes_at IS NOT NULL AND sniper_closes_at < ? AND sniper_sealed = 0",
            listOf(TursoValue.Text(nowIso))
        )
        if (sealResult.affectedRowCount > 0) {
            logger.info("Sealed ${sealResult.affectedRowCount} expired sniper inboxes")
        }

        // Delete expired inboxes (cascades to emails and attachments)
        val result = turso.execute("DELETE FROM inboxes WHERE expires_at < ?", listOf(TursoValue.Text(nowIso)))
        if (result.affectedRowCount > 0) {
            logger.info("Cleaned up ${result.affectedRowCount} expired inboxes")
        }

        // Prune SSE flows for deleted inboxes
        if (sseManager != null) {
            val activeRows = turso.execute("SELECT id FROM inboxes")
            val activeInboxIds = activeRows.toMaps().mapNotNull { it["id"] }.toSet()
            sseManager.pruneStaleFlows(activeInboxIds)
        }

        // Delete expired sessions, but keep any that still own an unexpired inbox:
        // inboxes.session_id is ON DELETE CASCADE, and PRO inboxes (90d TTL) outlive
        // the 24h anonymous session that created them. An expired session is already
        // rejected by SessionService.getSessionByToken, so keeping the row grants nothing.
        val sessionResult = turso.execute(
            """
            DELETE FROM sessions WHERE expires_at < ?
              AND id NOT IN (SELECT session_id FROM inboxes WHERE expires_at > ?)
            """.trimIndent(),
            listOf(TursoValue.Text(nowIso), TursoValue.Text(nowIso))
        )
        if (sessionResult.affectedRowCount > 0) {
            logger.info("Cleaned up ${sessionResult.affectedRowCount} expired sessions")
        }

        // Retry failed webhook deliveries
        try {
            webhookService?.retryFailedDeliveries()
        } catch (e: Exception) {
            logger.error("Webhook retry failed", e)
        }

        // Run contributed cleanup tasks (auth, magic links, etc.)
        for (contributor in cleanupContributors) {
            try {
                contributor.cleanup()
            } catch (e: Exception) {
                logger.error("Cleanup contributor failed", e)
            }
        }
    }
}
