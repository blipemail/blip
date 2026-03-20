package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.DomainStatus
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DomainServiceTest {

    private lateinit var turso: TursoClient
    private lateinit var service: DomainService

    @BeforeTest
    fun setup() {
        turso = mockk()
        // No Cloudflare/Resend credentials — tests only exercise DB logic
        service = DomainService(turso, "", "", "")
    }

    @Test
    fun `listActiveDomains returns active domains`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT domain") && it.contains("ACTIVE") }) } returns
            TursoResult(
                columns = listOf("domain"),
                rows = listOf(listOf("bl1p.dev"), listOf("useblip.email")),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val domains = service.listActiveDomains()

        assertEquals(listOf("bl1p.dev", "useblip.email"), domains)
    }

    @Test
    fun `listActiveDomains returns empty when none active`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT domain") && it.contains("ACTIVE") }) } returns
            TursoResult(columns = listOf("domain"), rows = emptyList(), affectedRowCount = 0, lastInsertRowid = 0)

        val domains = service.listActiveDomains()

        assertTrue(domains.isEmpty())
    }

    @Test
    fun `addDomain rejects invalid domain format`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.addDomain("not a domain")
        }

        assertFailsWith<IllegalArgumentException> {
            service.addDomain("@invalid.com")
        }
    }

    @Test
    fun `addDomain rejects duplicate domain`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT id FROM domains") }, any()) } returns
            TursoResult(
                columns = listOf("id"),
                rows = listOf(listOf("existing-id")),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        assertFailsWith<IllegalArgumentException> {
            service.addDomain("bl1p.dev")
        }
    }

    @Test
    fun `addDomain inserts new domain with PENDING_DNS status`() = runTest {
        // No existing domain
        coEvery { turso.execute(match { it.contains("SELECT id FROM domains") }, any()) } returns
            TursoResult(columns = listOf("id"), rows = emptyList(), affectedRowCount = 0, lastInsertRowid = 0)

        // Insert
        coEvery { turso.execute(match { it.contains("INSERT INTO domains") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 0)

        // Get after insert
        coEvery { turso.execute(match { it.contains("SELECT * FROM domains WHERE id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "domain", "status", "cloudflare_zone_id", "resend_domain_id", "created_at", "verified_at"),
                rows = listOf(listOf("new-id", "example.com", "PENDING_DNS", null, null, "2026-01-01T00:00:00", null)),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val domain = service.addDomain("example.com")

        assertEquals("example.com", domain.domain)
        assertEquals(DomainStatus.PENDING_DNS, domain.status)
    }

    @Test
    fun `disableDomain updates status`() = runTest {
        coEvery { turso.execute(match { it.contains("UPDATE domains") && it.contains("DISABLED") }, any()) } returns
            TursoResult(emptyList(), emptyList(), 1, 0)

        service.disableDomain("domain-1")

        coVerify { turso.execute(match { it.contains("DISABLED") }, any()) }
    }

    @Test
    fun `removeDomain prevents removing last active domain`() = runTest {
        // getDomain
        coEvery { turso.execute(match { it.contains("SELECT * FROM domains WHERE id") }, any()) } returns
            TursoResult(
                columns = listOf("id", "domain", "status", "cloudflare_zone_id", "resend_domain_id", "created_at", "verified_at"),
                rows = listOf(listOf("domain-1", "bl1p.dev", "ACTIVE", null, null, "2026-01-01T00:00:00", "2026-01-01T00:00:00")),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        // Only 1 active domain
        coEvery { turso.execute(match { it.contains("SELECT COUNT") && it.contains("ACTIVE") }) } returns
            TursoResult(columns = listOf("cnt"), rows = listOf(listOf("1")), affectedRowCount = 0, lastInsertRowid = 0)

        assertFailsWith<IllegalArgumentException> {
            service.removeDomain("domain-1")
        }
    }

    @Test
    fun `listAllDomains returns all domains`() = runTest {
        coEvery { turso.execute(match { it.contains("SELECT * FROM domains ORDER") }) } returns
            TursoResult(
                columns = listOf("id", "domain", "status", "cloudflare_zone_id", "resend_domain_id", "created_at", "verified_at"),
                rows = listOf(
                    listOf("d1", "bl1p.dev", "ACTIVE", "zone-1", "resend-1", "2026-01-01T00:00:00", "2026-01-01T00:00:00"),
                    listOf("d2", "example.com", "PENDING_DNS", null, null, "2026-01-02T00:00:00", null),
                ),
                affectedRowCount = 0,
                lastInsertRowid = 0,
            )

        val domains = service.listAllDomains()

        assertEquals(2, domains.size)
        assertEquals("bl1p.dev", domains[0].domain)
        assertEquals(DomainStatus.ACTIVE, domains[0].status)
        assertEquals("example.com", domains[1].domain)
        assertEquals(DomainStatus.PENDING_DNS, domains[1].status)
    }
}
