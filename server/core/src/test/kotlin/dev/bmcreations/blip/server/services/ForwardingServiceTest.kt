package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.server.ForbiddenException
import dev.bmcreations.blip.server.NotFoundException
import dev.bmcreations.blip.server.TierLimitException
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoResult
import dev.bmcreations.blip.server.db.TursoValue
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ForwardingServiceTest {

    private lateinit var turso: TursoClient
    private lateinit var service: ForwardingService

    @BeforeTest
    fun setup() {
        turso = mockk()
        service = ForwardingService(turso, "test-resend-key")
    }

    // --- createRule ---

    @Test
    fun `createRule stores rule and returns DTO`() = runTest {
        coEvery { turso.execute(match { it.contains("COUNT(*)") }, any()) } returns
            TursoResult(listOf("cnt"), listOf(listOf("0")), 0, 0)
        coEvery { turso.execute(match { it.contains("INSERT INTO forwarding_rules") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)

        val rule = service.createRule("inbox-1", "session-1", "user@example.com", 1)

        assertNotNull(rule.id)
        assertEquals("inbox-1", rule.inboxId)
        assertEquals("user@example.com", rule.forwardToEmail)
        assertNotNull(rule.createdAt)

        coVerify { turso.execute(match { it.contains("INSERT INTO forwarding_rules") }, any()) }
    }

    @Test
    fun `createRule rejects invalid email`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.createRule("inbox-1", "session-1", "not-an-email", 1)
        }
    }

    @Test
    fun `createRule rejects when tier limit reached`() = runTest {
        coEvery { turso.execute(match { it.contains("COUNT(*)") }, any()) } returns
            TursoResult(listOf("cnt"), listOf(listOf("1")), 0, 0)

        assertFailsWith<TierLimitException> {
            service.createRule("inbox-1", "session-1", "user@example.com", 1)
        }
    }

    @Test
    fun `createRule persists correct arguments`() = runTest {
        val argsSlot = slot<List<TursoValue>>()

        coEvery { turso.execute(match { it.contains("COUNT(*)") }, any()) } returns
            TursoResult(listOf("cnt"), listOf(listOf("0")), 0, 0)
        coEvery { turso.execute(match { it.contains("INSERT") }, capture(argsSlot)) } returns
            TursoResult(emptyList(), emptyList(), 1, 1)

        service.createRule("inbox-1", "session-1", "forward@test.com", 1)

        val textArgs = argsSlot.captured.filterIsInstance<TursoValue.Text>().map { it.value }
        assertTrue(textArgs.contains("inbox-1"))
        assertTrue(textArgs.contains("session-1"))
        assertTrue(textArgs.contains("forward@test.com"))
    }

    // --- listRules ---

    @Test
    fun `listRules returns rules for inbox`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT") && it.contains("forwarding_rules") }, any()) } returns
            TursoResult(
                columns = listOf("id", "inbox_id", "forward_to_email", "created_at"),
                rows = listOf(
                    listOf("rule-1", "inbox-1", "user@example.com", "2026-01-01T00:00:00Z"),
                ),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val rules = service.listRules("inbox-1")

        assertEquals(1, rules.size)
        assertEquals("rule-1", rules[0].id)
        assertEquals("inbox-1", rules[0].inboxId)
        assertEquals("user@example.com", rules[0].forwardToEmail)
    }

    @Test
    fun `listRules returns empty list when no rules exist`() = runTest {
        coEvery { turso.execute(any(), any()) } returns
            TursoResult(
                columns = listOf("id", "inbox_id", "forward_to_email", "created_at"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val rules = service.listRules("inbox-1")

        assertTrue(rules.isEmpty())
    }

    // --- deleteRule ---

    @Test
    fun `deleteRule removes rule owned by session`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT") }, any()) } returns
            TursoResult(
                columns = listOf("session_id"),
                rows = listOf(listOf("session-1")),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )
        coEvery { turso.execute(match { it.contains("DELETE") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 0)

        service.deleteRule("rule-1", "session-1")

        coVerify { turso.execute(match { it.contains("DELETE FROM forwarding_rules") }, any()) }
    }

    @Test
    fun `deleteRule rejects deletion by non-owner`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT") }, any()) } returns
            TursoResult(
                columns = listOf("session_id"),
                rows = listOf(listOf("session-other")),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<ForbiddenException> {
            service.deleteRule("rule-1", "session-1")
        }
    }

    @Test
    fun `deleteRule throws NotFoundException for missing rule`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT") }, any()) } returns
            TursoResult(
                columns = listOf("session_id"),
                rows = emptyList(),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<NotFoundException> {
            service.deleteRule("rule-missing", "session-1")
        }
    }

    // --- getRulesForInbox ---

    @Test
    fun `getRulesForInbox delegates to listRules`() = runTest {
        coEvery { turso.execute(any(), any()) } returns
            TursoResult(
                columns = listOf("id", "inbox_id", "forward_to_email", "created_at"),
                rows = listOf(
                    listOf("rule-1", "inbox-1", "fwd@test.com", "2026-01-01T00:00:00Z"),
                ),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val rules = service.getRulesForInbox("inbox-1")

        assertEquals(1, rules.size)
        assertEquals("fwd@test.com", rules[0].forwardToEmail)
    }
}
