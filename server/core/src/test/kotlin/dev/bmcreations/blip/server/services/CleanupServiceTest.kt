package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoResult
import dev.bmcreations.blip.server.db.TursoValue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class CleanupServiceTest {

    private lateinit var turso: TursoClient
    private lateinit var webhookService: WebhookService
    private lateinit var service: CleanupService

    @BeforeTest
    fun setup() {
        turso = mockk()
        webhookService = mockk()
        coEvery { webhookService.retryFailedDeliveries() } returns Unit
        coEvery { turso.execute(match { it.contains("forwarding_usage") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
        service = CleanupService(turso, webhookService)
    }

    @Test
    fun `cleanup seals expired sniper inboxes`() = runTest {
        coEvery { turso.execute(match { it.contains("DELETE FROM emails") && it.contains("received_at") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
        coEvery { turso.execute(match { it.contains("sniper_sealed = 1") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 2, 0)
        coEvery { turso.execute(match { it.contains("DELETE FROM inboxes") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
        coEvery { turso.execute(match { it.contains("DELETE FROM sessions") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)

        service.cleanup()

        coVerify { turso.execute(match { it.contains("sniper_sealed = 1") && it.contains("sniper_closes_at") }, any()) }
    }

    @Test
    fun `cleanup deletes expired inboxes`() = runTest {
        coEvery { turso.execute(match { it.contains("DELETE FROM emails") && it.contains("received_at") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
        coEvery { turso.execute(match { it.contains("sniper_sealed") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
        coEvery { turso.execute(match { it.contains("DELETE FROM inboxes") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 5, 0)
        coEvery { turso.execute(match { it.contains("DELETE FROM sessions") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)

        service.cleanup()

        coVerify { turso.execute(match { it.contains("DELETE FROM inboxes") && it.contains("expires_at") }, any()) }
    }

    @Test
    fun `cleanup deletes expired sessions`() = runTest {
        coEvery { turso.execute(match { it.contains("DELETE FROM emails") && it.contains("received_at") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
        coEvery { turso.execute(match { it.contains("sniper_sealed") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
        coEvery { turso.execute(match { it.contains("DELETE FROM inboxes") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
        coEvery { turso.execute(match { it.contains("DELETE FROM sessions") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 3, 0)

        service.cleanup()

        coVerify { turso.execute(match { it.contains("DELETE FROM sessions") && it.contains("expires_at") }, any()) }
    }

    @Test
    fun `cleanup deletes emails past retention`() = runTest {
        coEvery { turso.execute(match { it.contains("DELETE FROM emails") && it.contains("received_at") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 7, 0)
        coEvery { turso.execute(match { it.contains("sniper_sealed") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
        coEvery { turso.execute(match { it.contains("DELETE FROM inboxes") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)
        coEvery { turso.execute(match { it.contains("DELETE FROM sessions") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)

        service.cleanup()

        coVerify { turso.execute(match { it.contains("DELETE FROM emails") && it.contains("s.tier") }, any()) }
    }

    @Test
    fun `cleanup runs all four operations in order`() = runTest {
        coEvery { turso.execute(any(), any()) } returns TursoResult(emptyList(), emptyList(), 0, 0)

        service.cleanup()

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            turso.execute(match { it.contains("DELETE FROM emails") && it.contains("received_at") }, any())
            turso.execute(match { it.contains("sniper_sealed") }, any())
            turso.execute(match { it.contains("DELETE FROM inboxes") }, any())
            turso.execute(match { it.contains("DELETE FROM sessions") }, any())
        }
    }

    @Test
    fun `cleanup prunes forwarding usage older than a week`() = runTest {
        coEvery { turso.execute(match { !it.contains("forwarding_usage") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 0, 0)

        service.cleanup()

        val cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(7).toString()
        coVerify { turso.execute("DELETE FROM forwarding_usage WHERE day < ?", listOf(TursoValue.Text(cutoff))) }
    }
}
