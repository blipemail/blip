package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.SessionDTO
import dev.bmcreations.blip.models.Tier
import dev.bmcreations.blip.server.TierLimitException
import dev.bmcreations.blip.server.UnauthorizedException
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoValue
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class SessionService(
    private val turso: TursoClient,
    private val apiKeyValidator: ApiKeyValidator? = null,
) {

    companion object {
        const val MAX_SESSIONS_PER_IP = 10
    }

    suspend fun createSession(clientIp: String? = null): SessionDTO {
        // Enforce per-IP active session cap
        if (clientIp != null) {
            val countResult = turso.execute(
                "SELECT COUNT(*) as cnt FROM sessions WHERE fingerprint = ? AND expires_at > ?",
                listOf(TursoValue.Text(clientIp), TursoValue.Text(Instant.now().toString()))
            ).firstOrNull()
            val activeCount = countResult?.get("cnt")?.toLongOrNull() ?: 0
            if (activeCount >= MAX_SESSIONS_PER_IP) {
                throw TierLimitException("Too many active sessions")
            }
        }

        val id = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val tier = Tier.FREE
        val expiresAt = Instant.now().plus(24, ChronoUnit.HOURS).toString()

        turso.execute(
            "INSERT INTO sessions (id, token, tier, expires_at, fingerprint) VALUES (?, ?, ?, ?, ?)",
            listOf(
                TursoValue.Text(id),
                TursoValue.Text(token),
                TursoValue.Text(tier.name),
                TursoValue.Text(expiresAt),
                if (clientIp != null) TursoValue.Text(clientIp) else TursoValue.Null,
            )
        )

        return SessionDTO(
            id = id,
            token = token,
            tier = tier,
            expiresAt = expiresAt,
        )
    }

    suspend fun getSessionByToken(token: String): SessionDTO {
        val row = turso.execute(
            "SELECT id, token, tier, user_id, expires_at FROM sessions WHERE token = ?",
            listOf(TursoValue.Text(token))
        ).firstOrNull() ?: throw UnauthorizedException("Invalid session token")

        val expiresAt = row["expires_at"]!!
        if (Instant.parse(expiresAt).isBefore(Instant.now())) {
            throw UnauthorizedException("Session expired")
        }

        return SessionDTO(
            id = row["id"]!!,
            token = row["token"]!!,
            tier = Tier.valueOf(row["tier"] ?: "FREE"),
            userId = row["user_id"],
            expiresAt = expiresAt,
        )
    }

    suspend fun getSessionById(sessionId: String): SessionDTO? {
        val row = turso.execute(
            "SELECT id, token, tier, user_id, expires_at FROM sessions WHERE id = ?",
            listOf(TursoValue.Text(sessionId))
        ).firstOrNull() ?: return null

        return SessionDTO(
            id = row["id"]!!,
            token = row["token"]!!,
            tier = Tier.valueOf(row["tier"] ?: "FREE"),
            userId = row["user_id"],
            expiresAt = row["expires_at"]!!,
        )
    }

    suspend fun extractSession(authHeader: String?): SessionDTO {
        val token = authHeader?.removePrefix("Bearer ")?.trim()
            ?: throw UnauthorizedException("Missing authorization header")

        // API key auth: blip_ak_ prefix routes to ApiKeyValidator
        if (token.startsWith("blip_ak_") && apiKeyValidator != null) {
            return apiKeyValidator.validateApiKey(token)
        }

        return getSessionByToken(token)
    }
}
