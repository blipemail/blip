package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.CreateInboxRequest
import dev.bmcreations.blip.models.Inbox
import dev.bmcreations.blip.models.SniperWindow
import dev.bmcreations.blip.models.Tier
import dev.bmcreations.blip.server.ForbiddenException
import dev.bmcreations.blip.server.NotFoundException
import dev.bmcreations.blip.server.TierLimitException
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoResult
import dev.bmcreations.blip.server.db.TursoValue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

class InboxServiceTest {

    private lateinit var turso: TursoClient
    private lateinit var sessionService: SessionService
    private lateinit var domainService: DomainService
    private lateinit var service: InboxService

    private val sessionId = "test-session-id"

    @BeforeTest
    fun setup() {
        turso = mockk()
        sessionService = mockk()
        domainService = mockk()
        coEvery { domainService.listActiveDomains() } returns listOf("useblip.email")
        service = InboxService(turso, sessionService, domainService)
    }

    /** Helper: stub the COUNT query to return [count] existing inboxes. */
    private fun stubInboxCount(count: Int) {
        coEvery { turso.execute(match { it.contains("SELECT COUNT") }, any()) } returns
            TursoResult(listOf("cnt"), listOf(listOf(count.toString())), 0, 0)
    }

    /** Helper: stub the uniqueness-check query to say address is available. */
    private fun stubAddressAvailable() {
        coEvery { turso.execute(match { it.contains("SELECT id FROM inboxes WHERE address") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
    }

    /** Helper: stub the INSERT INTO inboxes. */
    private fun stubInsertInbox() {
        coEvery { turso.execute(match { it.contains("INSERT INTO inboxes") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)
    }

    // -- createInbox --

    @Test
    fun `createInbox generates random address with useblip-email domain`() = runTest {
        stubInboxCount(0)
        stubAddressAvailable()
        stubInsertInbox()

        val inbox = service.createInbox(sessionId, Tier.FREE)

        assertTrue(inbox.address.endsWith("@useblip.email"), "Address should end with @useblip.email")
        assertTrue(inbox.address.contains("-"), "Address should have adjective-noun format")
    }

    @Test
    fun `createInbox respects FREE tier 3-address limit`() = runTest {
        stubInboxCount(3) // already at the limit

        assertFailsWith<TierLimitException> {
            service.createInbox(sessionId, Tier.FREE)
        }
    }

    @Test
    fun `createInbox allows PRO tier unlimited addresses`() = runTest {
        // Even with many existing inboxes, PRO should allow creation
        stubInboxCount(1000)
        stubAddressAvailable()
        stubInsertInbox()

        val inbox = service.createInbox(sessionId, Tier.PRO)

        assertNotNull(inbox.id)
    }

    @Test
    fun `createInbox sets correct expiry based on tier TTL`() = runTest {
        stubInboxCount(0)
        stubAddressAvailable()
        stubInsertInbox()

        val inbox = service.createInbox(sessionId, Tier.FREE)

        val expiresAt = Instant.parse(inbox.expiresAt)
        val expectedExpiry = Instant.now().plusSeconds(Tier.FREE.addressTtlSeconds)
        // Within 5-second tolerance
        assertTrue(
            expiresAt.isAfter(expectedExpiry.minus(5, ChronoUnit.SECONDS)),
            "Expiry should be approximately ${Tier.FREE.addressTtlSeconds}s from now"
        )
        assertTrue(expiresAt.isBefore(expectedExpiry.plus(5, ChronoUnit.SECONDS)))
    }

    @Test
    fun `createInbox with custom slug uses slug as address for PRO tier`() = runTest {
        stubInboxCount(0)
        stubAddressAvailable()
        stubInsertInbox()

        val request = CreateInboxRequest(slug = "my-custom-slug", domain = "useblip.email")
        val inbox = service.createInbox(sessionId, Tier.PRO, request)

        assertEquals("my-custom-slug@useblip.email", inbox.address)
    }

    @Test
    fun `createInbox with custom slug rejected for FREE tier`() = runTest {
        stubInboxCount(0)

        val request = CreateInboxRequest(slug = "my-slug")

        assertFailsWith<TierLimitException> {
            service.createInbox(sessionId, Tier.FREE, request)
        }
    }

    @Test
    fun `createInbox with sniper window creates timed inbox for PRO tier`() = runTest {
        stubInboxCount(0)
        stubAddressAvailable()
        stubInsertInbox()

        val request = CreateInboxRequest(windowMinutes = 10)
        val inbox = service.createInbox(sessionId, Tier.PRO, request)

        assertNotNull(inbox.sniperWindow)
        val opens = Instant.parse(inbox.sniperWindow!!.opensAt)
        val closes = Instant.parse(inbox.sniperWindow!!.closesAt)
        val diff = closes.epochSecond - opens.epochSecond
        assertEquals(600, diff, "Sniper window should be 10 minutes (600 seconds)")
        assertFalse(inbox.sniperWindow!!.sealed)
    }

    @Test
    fun `createInbox with sniper window rejected for FREE tier`() = runTest {
        stubInboxCount(0)

        val request = CreateInboxRequest(windowMinutes = 10)

        assertFailsWith<TierLimitException> {
            service.createInbox(sessionId, Tier.FREE, request)
        }
    }

    @Test
    fun `createInbox stores domain separately`() = runTest {
        stubInboxCount(0)
        stubAddressAvailable()
        stubInsertInbox()

        val request = CreateInboxRequest(domain = "useblip.email")
        val inbox = service.createInbox(sessionId, Tier.FREE, request)

        assertEquals("useblip.email", inbox.domain)
        assertTrue(inbox.address.endsWith("@useblip.email"))
    }

    // -- listInboxes --

    @Test
    fun `listInboxes returns inboxes for session`() = runTest {
        val now = Instant.now().toString()
        val later = Instant.now().plus(1, ChronoUnit.HOURS).toString()

        coEvery { turso.execute(match { it.contains("FROM inboxes") && it.contains("session_id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "address", "created_at", "expires_at", "email_count"),
                rows = listOf(
                    listOf("inbox-1", "swift-fox-42@useblip.email", now, later, "5"),
                    listOf("inbox-2", "calm-owl-17@useblip.email", now, later, "0"),
                ),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val inboxes = service.listInboxes(sessionId)

        assertEquals(2, inboxes.size)
        assertEquals("inbox-1", inboxes[0].id)
        assertEquals("swift-fox-42@useblip.email", inboxes[0].address)
        assertEquals(5, inboxes[0].emailCount)
        assertEquals("inbox-2", inboxes[1].id)
        assertEquals(0, inboxes[1].emailCount)
    }

    @Test
    fun `listInboxes returns empty when none exist`() = runTest {
        coEvery { turso.execute(match { it.contains("FROM inboxes") }, any()) } returns
            TursoResult(
                columns = listOf("id", "address", "created_at", "expires_at", "email_count"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val inboxes = service.listInboxes(sessionId)

        assertTrue(inboxes.isEmpty())
    }

    // -- getInbox --

    @Test
    fun `getInbox returns detail with emails`() = runTest {
        val now = Instant.now().toString()
        val later = Instant.now().plus(1, ChronoUnit.HOURS).toString()

        // Stub inbox query
        coEvery { turso.execute(match { it.contains("FROM inboxes i WHERE i.id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "address", "session_id", "created_at", "expires_at", "email_count"),
                rows = listOf(listOf("inbox-1", "swift-fox-42@useblip.email", sessionId, now, later, "2")),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        // Stub emails query
        coEvery { turso.execute(match { it.contains("FROM emails WHERE inbox_id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "from_addr", "subject", "received_at", "preview"),
                rows = listOf(
                    listOf("email-1", "alice@example.com", "Hello", now, "Body text here"),
                    listOf("email-2", "bob@example.com", "Re: Hello", now, "Reply body"),
                ),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val detail = service.getInbox("inbox-1", sessionId)

        assertEquals("inbox-1", detail.inbox.id)
        assertEquals(2, detail.emails.size)
        assertEquals("Hello", detail.emails[0].subject)
        assertEquals("Body text here", detail.emails[0].preview)
    }

    @Test
    fun `getInbox throws NotFoundException for missing inbox`() = runTest {
        coEvery { turso.execute(match { it.contains("FROM inboxes i WHERE i.id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "address", "session_id", "created_at", "expires_at", "email_count"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<NotFoundException> {
            service.getInbox("missing-inbox", sessionId)
        }
    }

    @Test
    fun `getInbox throws ForbiddenException for wrong session`() = runTest {
        val now = Instant.now().toString()
        val later = Instant.now().plus(1, ChronoUnit.HOURS).toString()

        coEvery { turso.execute(match { it.contains("FROM inboxes i WHERE i.id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "address", "session_id", "created_at", "expires_at", "email_count"),
                rows = listOf(listOf("inbox-1", "swift-fox-42@useblip.email", "other-session", now, later, "0")),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<ForbiddenException> {
            service.getInbox("inbox-1", sessionId)
        }
    }

    // -- deleteInbox --

    @Test
    fun `deleteInbox removes inbox`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT session_id") }, any()) } returns
            TursoResult(
                columns = listOf("session_id", "user_id"),
                rows = listOf(listOf(sessionId, null)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )
        coEvery { turso.execute(match { it.contains("DELETE FROM inboxes") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 0)

        service.deleteInbox("inbox-1", sessionId)

        coVerify { turso.execute(match { it.contains("DELETE FROM inboxes") }, any()) }
    }

    @Test
    fun `deleteInbox throws NotFoundException for missing inbox`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT session_id") }, any()) } returns
            TursoResult(
                columns = listOf("session_id", "user_id"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<NotFoundException> {
            service.deleteInbox("missing-inbox", sessionId)
        }
    }

    @Test
    fun `deleteInbox throws ForbiddenException for wrong session`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT session_id") }, any()) } returns
            TursoResult(
                columns = listOf("session_id", "user_id"),
                rows = listOf(listOf("other-session", null)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<ForbiddenException> {
            service.deleteInbox("inbox-1", sessionId)
        }
    }

    // -- ownsInbox --

    @Test
    fun `ownsInbox returns true for owner`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT session_id") }, any()) } returns
            TursoResult(
                columns = listOf("session_id", "user_id"),
                rows = listOf(listOf(sessionId, null)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertTrue(service.ownsInbox("inbox-1", sessionId))
    }

    @Test
    fun `ownsInbox returns false for non-owner`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT session_id") }, any()) } returns
            TursoResult(
                columns = listOf("session_id", "user_id"),
                rows = listOf(listOf("other-session", null)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFalse(service.ownsInbox("inbox-1", sessionId))
    }

    @Test
    fun `ownsInbox returns false for missing inbox`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT session_id") }, any()) } returns
            TursoResult(
                columns = listOf("session_id", "user_id"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFalse(service.ownsInbox("missing-inbox", sessionId))
    }

    // -- getInboxByAddress --

    @Test
    fun `getInboxByAddress returns inbox`() = runTest {
        val now = Instant.now().toString()
        val later = Instant.now().plus(1, ChronoUnit.HOURS).toString()

        coEvery { turso.execute(match { it.contains("FROM inboxes WHERE address") }, any()) } returns
            TursoResult(
                columns = listOf("id", "address", "session_id", "created_at", "expires_at"),
                rows = listOf(listOf("inbox-1", "swift-fox-42@useblip.email", sessionId, now, later)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val inbox = service.getInboxByAddress("swift-fox-42@useblip.email")

        assertNotNull(inbox)
        assertEquals("inbox-1", inbox.id)
        assertEquals("swift-fox-42@useblip.email", inbox.address)
    }

    @Test
    fun `getInboxByAddress returns null for missing`() = runTest {
        coEvery { turso.execute(match { it.contains("FROM inboxes WHERE address") }, any()) } returns
            TursoResult(
                columns = listOf("id", "address", "session_id", "created_at", "expires_at"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val inbox = service.getInboxByAddress("nonexistent@useblip.email")

        assertNull(inbox)
    }

    // -- AGENT tier TTL --

    @Test
    fun `createInbox AGENT tier with windowMinutes sets custom TTL`() = runTest {
        stubInboxCount(0)
        stubAddressAvailable()
        stubInsertInbox()

        val request = CreateInboxRequest(windowMinutes = 60)
        val inbox = service.createInbox(sessionId, Tier.AGENT, request)

        val expiresAt = Instant.parse(inbox.expiresAt)
        val expectedExpiry = Instant.now().plusSeconds(3600)
        assertTrue(
            expiresAt.isAfter(expectedExpiry.minus(5, ChronoUnit.SECONDS)),
            "Expiry should be approximately 1 hour from now"
        )
        assertTrue(expiresAt.isBefore(expectedExpiry.plus(5, ChronoUnit.SECONDS)))
        assertNull(inbox.sniperWindow, "AGENT tier should not create a sniper window")
    }

    @Test
    fun `createInbox AGENT tier windowMinutes capped at 90 days`() = runTest {
        stubInboxCount(0)
        stubAddressAvailable()
        stubInsertInbox()

        val request = CreateInboxRequest(windowMinutes = 200_000) // exceeds 90 days
        val inbox = service.createInbox(sessionId, Tier.AGENT, request)

        val expiresAt = Instant.parse(inbox.expiresAt)
        val expectedExpiry = Instant.now().plusSeconds(7_776_000L) // 90 days cap
        assertTrue(
            expiresAt.isAfter(expectedExpiry.minus(5, ChronoUnit.SECONDS)),
            "Expiry should be capped at 90 days"
        )
        assertTrue(expiresAt.isBefore(expectedExpiry.plus(5, ChronoUnit.SECONDS)))
    }

    @Test
    fun `createInbox AGENT tier without windowMinutes uses default 1h TTL`() = runTest {
        stubInboxCount(0)
        stubAddressAvailable()
        stubInsertInbox()

        val inbox = service.createInbox(sessionId, Tier.AGENT)

        val expiresAt = Instant.parse(inbox.expiresAt)
        val expectedExpiry = Instant.now().plusSeconds(Tier.AGENT.addressTtlSeconds)
        assertTrue(
            expiresAt.isAfter(expectedExpiry.minus(5, ChronoUnit.SECONDS)),
            "Expiry should be default 1-hour TTL"
        )
        assertTrue(expiresAt.isBefore(expectedExpiry.plus(5, ChronoUnit.SECONDS)))
    }

    // -- TDD: sniper inbox --

    @Test
    fun `isSniperWindowOpen returns false after window closes`() {
        val pastOpens = Instant.now().minusSeconds(3600).toString()
        val pastCloses = Instant.now().minusSeconds(1800).toString()

        val inbox = Inbox(
            id = "inbox-sniper",
            address = "sniper@useblip.email",
            domain = "useblip.email",
            createdAt = pastOpens,
            expiresAt = Instant.now().plusSeconds(86400).toString(),
            sniperWindow = SniperWindow(
                opensAt = pastOpens,
                closesAt = pastCloses,
                sealed = false,
            )
        )

        assertFalse(service.isSniperWindowOpen(inbox))
    }

    @Test
    fun `isSniperWindowOpen returns true during window`() {
        val opens = Instant.now().minusSeconds(300).toString()
        val closes = Instant.now().plusSeconds(300).toString()

        val inbox = Inbox(
            id = "inbox-sniper",
            address = "sniper@useblip.email",
            domain = "useblip.email",
            createdAt = opens,
            expiresAt = Instant.now().plusSeconds(86400).toString(),
            sniperWindow = SniperWindow(opensAt = opens, closesAt = closes, sealed = false)
        )

        assertTrue(service.isSniperWindowOpen(inbox))
    }

    @Test
    fun `isSniperWindowOpen returns false when sealed`() {
        val opens = Instant.now().minusSeconds(300).toString()
        val closes = Instant.now().plusSeconds(300).toString()

        val inbox = Inbox(
            id = "inbox-sniper",
            address = "sniper@useblip.email",
            domain = "useblip.email",
            createdAt = opens,
            expiresAt = Instant.now().plusSeconds(86400).toString(),
            sniperWindow = SniperWindow(opensAt = opens, closesAt = closes, sealed = true)
        )

        assertFalse(service.isSniperWindowOpen(inbox))
    }

    @Test
    fun `isSniperWindowOpen returns true for non-sniper inbox`() {
        val inbox = Inbox(
            id = "inbox-normal",
            address = "normal@useblip.email",
            domain = "useblip.email",
            createdAt = Instant.now().toString(),
            expiresAt = Instant.now().plusSeconds(86400).toString(),
            sniperWindow = null,
        )

        assertTrue(service.isSniperWindowOpen(inbox))
    }
}
