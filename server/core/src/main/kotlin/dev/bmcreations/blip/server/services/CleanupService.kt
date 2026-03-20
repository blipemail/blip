package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.sse.SseManager
import io.ktor.server.application.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

class CleanupService(
    private val turso: TursoClient,
    private val webhookService: WebhookService? = null,
    private val sseManager: SseManager? = null,
    private val cleanupContributors: List<CleanupContributor> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(CleanupService::class.java)

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
        // Delete emails past their tier's retention period
        val retentionResult = turso.execute(
            """
            DELETE FROM emails WHERE id IN (
                SELECT e.id FROM emails e
                JOIN inboxes i ON e.inbox_id = i.id
                JOIN sessions s ON i.session_id = s.id
                WHERE (s.tier = 'FREE' AND e.received_at < datetime('now', '-86400 seconds'))
                   OR (s.tier = 'PRO' AND e.received_at < datetime('now', '-2592000 seconds'))
                   OR (s.tier = 'AGENT' AND e.received_at < datetime('now', '-3600 seconds'))
            )
            """.trimIndent()
        )
        if (retentionResult.affectedRowCount > 0) {
            logger.info("Deleted ${retentionResult.affectedRowCount} emails past retention")
        }

        // Seal expired sniper windows
        val sealResult = turso.execute(
            "UPDATE inboxes SET sniper_sealed = 1 WHERE sniper_closes_at IS NOT NULL AND sniper_closes_at < datetime('now') AND sniper_sealed = 0"
        )
        if (sealResult.affectedRowCount > 0) {
            logger.info("Sealed ${sealResult.affectedRowCount} expired sniper inboxes")
        }

        // Delete expired inboxes (cascades to emails and attachments)
        val result = turso.execute("DELETE FROM inboxes WHERE expires_at < datetime('now')")
        if (result.affectedRowCount > 0) {
            logger.info("Cleaned up ${result.affectedRowCount} expired inboxes")
        }

        // Prune SSE flows for deleted inboxes
        if (sseManager != null) {
            val activeRows = turso.execute("SELECT id FROM inboxes")
            val activeInboxIds = activeRows.toMaps().mapNotNull { it["id"] }.toSet()
            sseManager.pruneStaleFlows(activeInboxIds)
        }

        // Delete expired sessions
        val sessionResult = turso.execute("DELETE FROM sessions WHERE expires_at < datetime('now')")
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
