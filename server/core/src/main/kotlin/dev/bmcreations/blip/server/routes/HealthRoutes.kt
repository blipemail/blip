package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.server.db.TursoClient
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory

@Serializable
data class HealthResponse(
    val status: String,
    val uptime: String,
    val db: DbHealth,
    val stats: Stats? = null,
)

@Serializable
data class DbHealth(
    val connected: Boolean,
    val latencyMs: Long,
)

@Serializable
data class Stats(
    val activeSessions: Long,
    val activeInboxes: Long,
    val emailsLast24h: Long,
)

fun Route.healthRoutes(turso: TursoClient) {
    get("/health") {
        val uptime = ManagementFactory.getRuntimeMXBean().uptime
        val uptimeStr = formatUptime(uptime)

        // Check DB connectivity and latency
        val dbStart = System.currentTimeMillis()
        val dbConnected = try {
            turso.execute("SELECT 1", emptyList())
            true
        } catch (_: Exception) {
            false
        }
        val dbLatency = System.currentTimeMillis() - dbStart

        val stats = if (dbConnected) {
            try {
                val now = java.time.Instant.now().toString()
                val dayAgo = java.time.Instant.now().minus(java.time.Duration.ofHours(24)).toString()

                val sessions = turso.execute(
                    "SELECT COUNT(*) as cnt FROM sessions WHERE expires_at > ?",
                    listOf(dev.bmcreations.blip.server.db.TursoValue.Text(now))
                ).firstOrNull()?.get("cnt")?.toLongOrNull() ?: 0

                val inboxes = turso.execute(
                    "SELECT COUNT(*) as cnt FROM inboxes WHERE expires_at > ?",
                    listOf(dev.bmcreations.blip.server.db.TursoValue.Text(now))
                ).firstOrNull()?.get("cnt")?.toLongOrNull() ?: 0

                val emails = turso.execute(
                    "SELECT COUNT(*) as cnt FROM emails WHERE received_at > ?",
                    listOf(dev.bmcreations.blip.server.db.TursoValue.Text(dayAgo))
                ).firstOrNull()?.get("cnt")?.toLongOrNull() ?: 0

                Stats(activeSessions = sessions, activeInboxes = inboxes, emailsLast24h = emails)
            } catch (_: Exception) {
                null
            }
        } else null

        val status = if (dbConnected) "ok" else "degraded"
        val httpStatus = if (dbConnected) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable

        call.respond(httpStatus, HealthResponse(
            status = status,
            uptime = uptimeStr,
            db = DbHealth(connected = dbConnected, latencyMs = dbLatency),
            stats = stats,
        ))
    }
}

private fun formatUptime(millis: Long): String {
    val seconds = millis / 1000
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h ${minutes}m"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
