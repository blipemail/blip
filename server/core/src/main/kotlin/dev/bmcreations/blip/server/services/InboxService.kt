package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.*
import dev.bmcreations.blip.server.ForbiddenException
import dev.bmcreations.blip.server.NotFoundException
import dev.bmcreations.blip.server.TierLimitException
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoValue
import dev.bmcreations.blip.server.sse.SseManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InboxService(
    private val turso: TursoClient,
    private val sessionService: SessionService,
    private val domainService: DomainService,
    private val sseManager: SseManager? = null,
    private val encryptionService: EncryptionService = EncryptionService(),
) {
    private val inboxCreationLocks = ConcurrentHashMap<String, Mutex>()

    companion object {
        private val SLUG_REGEX = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?\$")
        private val RESERVED_SLUGS = setOf(
            "admin", "postmaster", "abuse", "support", "noreply", "no-reply",
            "root", "webmaster", "info", "help",
        )
    }

    private fun validateSlug(slug: String) {
        require(slug.length in 3..64) { "Slug must be between 3 and 64 characters" }
        require(SLUG_REGEX.matches(slug)) { "Slug must contain only lowercase letters, numbers, and hyphens, and must start and end with a letter or number" }
        require(slug !in RESERVED_SLUGS) { "Slug '$slug' is reserved" }
    }
    private val adjectives = listOf(
        "swift", "quiet", "bold", "calm", "dark", "fair", "keen", "warm",
        "cool", "wild", "free", "pale", "deep", "soft", "bright", "fresh",
        "sharp", "light", "quick", "rare", "pure", "fine", "glad", "kind"
    )

    private val nouns = listOf(
        "fox", "owl", "elm", "oak", "bay", "sky", "dew", "ash",
        "fin", "gem", "ink", "jay", "kit", "log", "map", "net",
        "orb", "pen", "ray", "sun", "tea", "urn", "web", "yew"
    )

    private fun generateSlug(): String {
        val adj = adjectives.random()
        val noun = nouns.random()
        val num = (10..99).random()
        return "$adj-$noun-$num"
    }

    suspend fun createInbox(sessionId: String, tier: Tier, request: CreateInboxRequest = CreateInboxRequest(), userId: String? = null): InboxDTO {
        val lockKey = userId ?: sessionId
        val mutex = inboxCreationLocks.getOrPut(lockKey) { Mutex() }
        return mutex.withLock { createInboxInternal(sessionId, tier, request, userId) }
    }

    private suspend fun createInboxInternal(sessionId: String, tier: Tier, request: CreateInboxRequest, userId: String?): InboxDTO {
        // Check tier limits — count by user if authenticated, else by session
        val count = if (userId != null) {
            turso.execute(
                "SELECT COUNT(*) as cnt FROM inboxes WHERE user_id = ?",
                listOf(TursoValue.Text(userId))
            )
        } else {
            turso.execute(
                "SELECT COUNT(*) as cnt FROM inboxes WHERE session_id = ?",
                listOf(TursoValue.Text(sessionId))
            )
        }.firstOrNull()?.get("cnt")?.toLongOrNull() ?: 0

        if (count >= tier.maxAddresses) {
            throw TierLimitException("Address limit reached (${tier.maxAddresses} for ${tier.name} tier)")
        }

        // Custom slugs require PRO
        val isCustomSlug = request.slug != null
        if (isCustomSlug && !tier.customSlugs) {
            throw TierLimitException("Custom address slugs require Pro plan")
        }
        if (isCustomSlug) validateSlug(request.slug!!)

        // Domain selection
        val activeDomains = domainService.listActiveDomains()
        require(activeDomains.isNotEmpty()) { "No active domains configured" }
        val domain = request.domain ?: activeDomains.first()
        if (domain !in activeDomains) {
            throw IllegalArgumentException("Unsupported domain: $domain")
        }

        // Sniper inbox requires PRO
        val sniperMinutes = request.windowMinutes
        val sniperWindow = if (sniperMinutes != null && tier.sniperInboxEnabled) {
            val opensAt = Instant.now()
            val closesAt = opensAt.plusSeconds(sniperMinutes.toLong() * 60)
            SniperWindow(
                opensAt = opensAt.toString(),
                closesAt = closesAt.toString(),
                sealed = false,
            )
        } else if (sniperMinutes != null && tier != Tier.AGENT) {
            throw TierLimitException("Sniper Inbox requires Pro plan")
        } else null

        // Ensure address uniqueness with iterative retry for random slugs
        val maxAttempts = 5
        var slug: String? = null
        var address: String? = null
        for (attempt in 1..maxAttempts) {
            val candidateSlug = if (isCustomSlug) request.slug!! else generateSlug()
            val candidateAddress = "$candidateSlug@$domain"
            val existing = turso.execute(
                "SELECT id FROM inboxes WHERE address = ?",
                listOf(TursoValue.Text(candidateAddress))
            ).firstOrNull()
            if (existing != null) {
                if (isCustomSlug) {
                    throw IllegalArgumentException("Address already taken: $candidateAddress")
                }
                continue
            }
            slug = candidateSlug
            address = candidateAddress
            break
        }
        if (slug == null || address == null) {
            throw IllegalStateException("Could not generate a unique address after $maxAttempts attempts")
        }

        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        val ttlSeconds = if (tier == Tier.AGENT && sniperMinutes != null) {
            val requestedSeconds = sniperMinutes.toLong() * 60
            requestedSeconds.coerceAtMost(7_776_000L) // cap at 90 days
        } else {
            tier.addressTtlSeconds
        }
        val expiresAt = now.plusSeconds(ttlSeconds).toString()
        val encryptionKey = encryptionService.generateKey()

        val args = mutableListOf<TursoValue>(
            TursoValue.Text(id),
            TursoValue.Text(address),
            TursoValue.Text(domain),
            TursoValue.Text(sessionId),
            TursoValue.Text(now.toString()),
            TursoValue.Text(expiresAt),
            if (sniperWindow != null) TursoValue.Text(sniperWindow.opensAt) else TursoValue.Null,
            if (sniperWindow != null) TursoValue.Text(sniperWindow.closesAt) else TursoValue.Null,
            TursoValue.Integer(if (sniperWindow != null) 0 else 0),
            TursoValue.Text(encryptionKey),
        )

        if (userId != null) {
            args.add(TursoValue.Text(userId))
            turso.execute(
                """
                INSERT INTO inboxes (id, address, domain, session_id, created_at, expires_at, sniper_opens_at, sniper_closes_at, sniper_sealed, encryption_key, user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                args,
            )
        } else {
            turso.execute(
                """
                INSERT INTO inboxes (id, address, domain, session_id, created_at, expires_at, sniper_opens_at, sniper_closes_at, sniper_sealed, encryption_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                args,
            )
        }

        return InboxDTO(
            id = id,
            address = address,
            domain = domain,
            createdAt = now.toString(),
            expiresAt = expiresAt,
            sniperWindow = sniperWindow,
        )
    }

    suspend fun listInboxes(sessionId: String, userId: String? = null): List<InboxDTO> {
        val result = if (userId != null) {
            turso.execute(
                """
                SELECT i.id, i.address, i.domain, i.created_at, i.expires_at,
                       i.sniper_opens_at, i.sniper_closes_at, i.sniper_sealed,
                       (SELECT COUNT(*) FROM emails e WHERE e.inbox_id = i.id) as email_count
                FROM inboxes i
                WHERE i.user_id = ? AND i.expires_at > datetime('now')
                ORDER BY i.created_at DESC
                """.trimIndent(),
                listOf(TursoValue.Text(userId))
            )
        } else {
            turso.execute(
                """
                SELECT i.id, i.address, i.domain, i.created_at, i.expires_at,
                       i.sniper_opens_at, i.sniper_closes_at, i.sniper_sealed,
                       (SELECT COUNT(*) FROM emails e WHERE e.inbox_id = i.id) as email_count
                FROM inboxes i
                WHERE i.session_id = ? AND i.expires_at > datetime('now')
                ORDER BY i.created_at DESC
                """.trimIndent(),
                listOf(TursoValue.Text(sessionId))
            )
        }

        return result.toMaps().map { it.toInboxDTO() }
    }

    suspend fun getInbox(inboxId: String, sessionId: String, userId: String? = null): InboxDetailDTO {
        val inboxRow = turso.execute(
            """
            SELECT i.id, i.address, i.domain, i.session_id, i.user_id, i.created_at, i.expires_at,
                   i.sniper_opens_at, i.sniper_closes_at, i.sniper_sealed,
                   (SELECT COUNT(*) FROM emails e WHERE e.inbox_id = i.id) as email_count
            FROM inboxes i WHERE i.id = ?
            """.trimIndent(),
            listOf(TursoValue.Text(inboxId))
        ).firstOrNull() ?: throw NotFoundException("Inbox not found")

        val ownerMatch = (userId != null && inboxRow["user_id"] == userId) || inboxRow["session_id"] == sessionId
        if (!ownerMatch) {
            throw ForbiddenException("Access denied")
        }

        val inbox = inboxRow.toInboxDTO()

        val emailRows = turso.execute(
            """
            SELECT id, from_addr, subject, received_at, preview
            FROM emails WHERE inbox_id = ? ORDER BY received_at DESC LIMIT 100
            """.trimIndent(),
            listOf(TursoValue.Text(inboxId))
        )

        val emails = emailRows.toMaps().map { row ->
            EmailSummaryDTO(
                id = row["id"]!!,
                from = row["from_addr"] ?: "",
                subject = row["subject"] ?: "",
                receivedAt = row["received_at"]!!,
                preview = row["preview"] ?: "",
            )
        }

        return InboxDetailDTO(inbox = inbox, emails = emails)
    }

    suspend fun getEncryptionKey(inboxId: String): String? {
        val row = turso.execute(
            "SELECT encryption_key FROM inboxes WHERE id = ?",
            listOf(TursoValue.Text(inboxId))
        ).firstOrNull() ?: return null
        return row["encryption_key"]
    }

    suspend fun getInboxByAddress(address: String): InboxDTO? {
        val row = turso.execute(
            """
            SELECT id, address, domain, session_id, created_at, expires_at,
                   sniper_opens_at, sniper_closes_at, sniper_sealed
            FROM inboxes WHERE address = ? AND expires_at > datetime('now')
            """.trimIndent(),
            listOf(TursoValue.Text(address))
        ).firstOrNull() ?: return null

        return row.toInboxDTO()
    }

    /**
     * Check if a sniper inbox is currently accepting emails.
     * Returns true if the inbox has no sniper window, or if the window is currently open.
     */
    fun isSniperWindowOpen(inbox: InboxDTO): Boolean {
        val window = inbox.sniperWindow ?: return true
        if (window.sealed) return false
        val now = Instant.now()
        val opens = Instant.parse(window.opensAt)
        val closes = Instant.parse(window.closesAt)
        return now.isAfter(opens) && now.isBefore(closes)
    }

    suspend fun sealSniperInbox(inboxId: String) {
        turso.execute(
            "UPDATE inboxes SET sniper_sealed = 1 WHERE id = ?",
            listOf(TursoValue.Text(inboxId))
        )
    }

    suspend fun deleteInbox(inboxId: String, sessionId: String, userId: String? = null) {
        val row = turso.execute(
            "SELECT session_id, user_id FROM inboxes WHERE id = ?",
            listOf(TursoValue.Text(inboxId))
        ).firstOrNull() ?: throw NotFoundException("Inbox not found")

        val ownerMatch = (userId != null && row["user_id"] == userId) || row["session_id"] == sessionId
        if (!ownerMatch) {
            throw ForbiddenException("Access denied")
        }

        turso.execute(
            "DELETE FROM inboxes WHERE id = ?",
            listOf(TursoValue.Text(inboxId))
        )
        sseManager?.removeFlow(inboxId)
    }

    suspend fun ownsInbox(inboxId: String, sessionId: String, userId: String? = null): Boolean {
        val row = turso.execute(
            "SELECT session_id, user_id FROM inboxes WHERE id = ?",
            listOf(TursoValue.Text(inboxId))
        ).firstOrNull() ?: return false
        return (userId != null && row["user_id"] == userId) || row["session_id"] == sessionId
    }

    suspend fun getSessionIdForInbox(inboxId: String): String? {
        val row = turso.execute(
            "SELECT session_id FROM inboxes WHERE id = ?",
            listOf(TursoValue.Text(inboxId))
        ).firstOrNull() ?: return null
        return row["session_id"]
    }

    private fun Map<String, String?>.toInboxDTO(): InboxDTO {
        val sniperOpens = this["sniper_opens_at"]
        val sniperCloses = this["sniper_closes_at"]
        val sniperSealed = this["sniper_sealed"] == "1"

        val sniperWindow = if (sniperOpens != null && sniperCloses != null) {
            SniperWindow(
                opensAt = sniperOpens,
                closesAt = sniperCloses,
                sealed = sniperSealed,
            )
        } else null

        return InboxDTO(
            id = this["id"]!!,
            address = this["address"]!!,
            domain = this["domain"] ?: "bl1p.dev",
            createdAt = this["created_at"]!!,
            expiresAt = this["expires_at"]!!,
            emailCount = this["email_count"]?.toIntOrNull() ?: 0,
            sniperWindow = sniperWindow,
        )
    }
}
