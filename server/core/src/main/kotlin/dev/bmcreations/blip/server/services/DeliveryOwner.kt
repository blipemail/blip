package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.Tier
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoValue

/** Who currently pays for an inbox, resolved at delivery time. */
data class DeliveryOwner(val tier: Tier, val ownerKey: String?)

/**
 * Current tier for an inbox: the owning user's subscription flags when the inbox has a user,
 * otherwise the session's tier. Evaluated per delivery so downgrades take effect immediately.
 *
 * TODO: consolidate with InboxService's tier-for-inbox helper once that lands.
 */
suspend fun TursoClient.resolveDeliveryOwner(inboxId: String): DeliveryOwner {
    val row = execute(
        """
        SELECT i.session_id, i.user_id, u.has_pro, u.has_agent, s.tier
        FROM inboxes i
        LEFT JOIN users u ON u.id = i.user_id
        LEFT JOIN sessions s ON s.id = i.session_id
        WHERE i.id = ?
        """.trimIndent(),
        listOf(TursoValue.Text(inboxId))
    ).firstOrNull() ?: return DeliveryOwner(Tier.FREE, null)

    val userId = row["user_id"]
    if (userId != null) {
        val tier = when {
            row["has_pro"]?.toIntOrNull() == 1 -> Tier.PRO
            row["has_agent"]?.toIntOrNull() == 1 -> Tier.AGENT
            else -> Tier.FREE
        }
        return DeliveryOwner(tier, "user:$userId")
    }
    val tier = row["tier"]?.let { runCatching { Tier.valueOf(it) }.getOrNull() } ?: Tier.FREE
    return DeliveryOwner(tier, row["session_id"]?.let { "session:$it" })
}
