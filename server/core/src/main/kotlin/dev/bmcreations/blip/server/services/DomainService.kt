package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.DnsRecord
import dev.bmcreations.blip.models.DomainDTO
import dev.bmcreations.blip.models.DomainStatus
import dev.bmcreations.blip.models.DomainVerificationStatus
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoValue
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.*

class DomainService(
    private val turso: TursoClient,
    private val cloudflareApiToken: String,
    private val cloudflareAccountId: String,
    private val resendApiKey: String,
) {
    private val logger = LoggerFactory.getLogger(DomainService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    // -- Public queries --

    suspend fun listActiveDomains(): List<String> {
        val result = turso.execute("SELECT domain FROM domains WHERE status = 'ACTIVE'")
        return result.toMaps().mapNotNull { it["domain"] }
    }

    suspend fun listAllDomains(): List<DomainDTO> {
        val result = turso.execute("SELECT * FROM domains ORDER BY created_at DESC")
        return result.toMaps().map { it.toDomainDTO() }
    }

    suspend fun getDomain(id: String): DomainDTO? {
        val row = turso.execute(
            "SELECT * FROM domains WHERE id = ?",
            listOf(TursoValue.Text(id))
        ).firstOrNull() ?: return null
        return row.toDomainDTO()
    }

    // -- Domain lifecycle --

    suspend fun addDomain(domain: String): DomainDTO {
        val normalized = domain.lowercase().trim()
        require(normalized.matches(Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}$"))) {
            "Invalid domain format"
        }

        // Check if already exists
        val existing = turso.execute(
            "SELECT id FROM domains WHERE domain = ?",
            listOf(TursoValue.Text(normalized))
        ).firstOrNull()
        if (existing != null) {
            throw IllegalArgumentException("Domain already exists")
        }

        val id = UUID.randomUUID().toString()

        // Create Cloudflare zone
        val zoneId = createCloudflareZone(normalized)

        // Insert into DB
        turso.execute(
            "INSERT INTO domains (id, domain, status, cloudflare_zone_id) VALUES (?, ?, 'PENDING_DNS', ?)",
            listOf(
                TursoValue.Text(id),
                TursoValue.Text(normalized),
                if (zoneId != null) TursoValue.Text(zoneId) else TursoValue.Null,
            )
        )

        // Set up DNS records and email routing if zone was created
        if (zoneId != null) {
            setupDnsRecords(zoneId)
            setupEmailRouting(zoneId)
        }

        return getDomain(id)!!
    }

    suspend fun verifyDomain(id: String): DomainVerificationStatus {
        val domain = getDomain(id) ?: throw IllegalArgumentException("Domain not found")

        val dnsRecords = mutableListOf<DnsRecord>()
        var resendVerified = false
        var nameservers = emptyList<String>()
        var newStatus = domain.status

        // Check Cloudflare zone status
        val cfZoneId = domain.cloudflareZoneId
        if (cfZoneId != null) {
            val zoneStatus = checkCloudflareZoneStatus(cfZoneId)
            nameservers = zoneStatus.first
            val zoneActive = zoneStatus.second

            if (zoneActive && domain.status == DomainStatus.PENDING_DNS) {
                newStatus = DomainStatus.PENDING_VERIFICATION

                // Add to Resend now that DNS is active
                if (domain.resendDomainId == null) {
                    val resendDomainId = addResendDomain(domain.domain)
                    if (resendDomainId != null) {
                        turso.execute(
                            "UPDATE domains SET resend_domain_id = ? WHERE id = ?",
                            listOf(TursoValue.Text(resendDomainId), TursoValue.Text(id))
                        )
                    }
                }
            }
        }

        // Check Resend verification status
        val resendId = domain.resendDomainId ?: turso.execute(
            "SELECT resend_domain_id FROM domains WHERE id = ?",
            listOf(TursoValue.Text(id))
        ).firstOrNull()?.get("resend_domain_id")

        if (resendId != null) {
            val resendStatus = checkResendDomainStatus(resendId)
            dnsRecords.addAll(resendStatus.first)
            resendVerified = resendStatus.second

            if (resendVerified && (newStatus == DomainStatus.PENDING_VERIFICATION || domain.status == DomainStatus.PENDING_VERIFICATION)) {
                newStatus = DomainStatus.ACTIVE
                turso.execute(
                    "UPDATE domains SET status = 'ACTIVE', verified_at = datetime('now') WHERE id = ?",
                    listOf(TursoValue.Text(id))
                )
            }
        }

        // Update status if changed
        if (newStatus != domain.status && newStatus != DomainStatus.ACTIVE) {
            turso.execute(
                "UPDATE domains SET status = ? WHERE id = ?",
                listOf(TursoValue.Text(newStatus.name), TursoValue.Text(id))
            )
        }

        return DomainVerificationStatus(
            domain = domain.domain,
            status = newStatus,
            dnsRecords = dnsRecords,
            resendVerified = resendVerified,
            cloudflareNameservers = nameservers,
        )
    }

    suspend fun disableDomain(id: String) {
        turso.execute(
            "UPDATE domains SET status = 'DISABLED' WHERE id = ?",
            listOf(TursoValue.Text(id))
        )
    }

    suspend fun removeDomain(id: String) {
        val domain = getDomain(id) ?: throw IllegalArgumentException("Domain not found")

        // Prevent removing the last active domain
        val activeCount = turso.execute("SELECT COUNT(*) as cnt FROM domains WHERE status = 'ACTIVE'")
            .firstOrNull()?.get("cnt")?.toLongOrNull() ?: 0
        if (activeCount <= 1 && domain.status == DomainStatus.ACTIVE) {
            throw IllegalArgumentException("Cannot remove the last active domain")
        }

        // Remove from Cloudflare
        val zoneId = domain.cloudflareZoneId
        if (zoneId != null) {
            deleteCloudflareZone(zoneId)
        }

        // Remove from Resend
        val resendId = domain.resendDomainId
        if (resendId != null) {
            deleteResendDomain(resendId)
        }

        turso.execute(
            "DELETE FROM domains WHERE id = ?",
            listOf(TursoValue.Text(id))
        )
    }

    // -- Cloudflare API --

    private suspend fun createCloudflareZone(domain: String): String? {
        if (cloudflareApiToken.isBlank() || cloudflareAccountId.isBlank()) {
            logger.warn("Cloudflare credentials not configured, skipping zone creation for $domain")
            return null
        }

        val resp = httpClient.post("https://api.cloudflare.com/client/v4/zones") {
            header("Authorization", "Bearer $cloudflareApiToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", domain)
                putJsonObject("account") {
                    put("id", cloudflareAccountId)
                }
            }.toString())
        }

        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        if (!resp.status.isSuccess()) {
            // Zone might already exist
            val errors = body["errors"]?.jsonArray
            val alreadyExists = errors?.any {
                it.jsonObject["code"]?.jsonPrimitive?.intOrNull == 1061
            } == true

            if (alreadyExists) {
                // Fetch existing zone ID
                return getExistingZoneId(domain)
            }

            logger.error("Failed to create Cloudflare zone for $domain: ${resp.bodyAsText()}")
            return null
        }

        return body["result"]?.jsonObject?.get("id")?.jsonPrimitive?.content
    }

    private suspend fun getExistingZoneId(domain: String): String? {
        val resp = httpClient.get("https://api.cloudflare.com/client/v4/zones") {
            header("Authorization", "Bearer $cloudflareApiToken")
            parameter("name", domain)
            parameter("account.id", cloudflareAccountId)
        }

        if (!resp.status.isSuccess()) return null

        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val results = body["result"]?.jsonArray ?: return null
        return results.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
    }

    private suspend fun setupDnsRecords(zoneId: String) {
        // Add MX records for Cloudflare Email Routing
        val mxRecords = listOf(
            Triple("route1.mx.cloudflare.net", 10, "MX record 1"),
            Triple("route2.mx.cloudflare.net", 20, "MX record 2"),
            Triple("route3.mx.cloudflare.net", 30, "MX record 3"),
        )

        for ((content, priority, comment) in mxRecords) {
            httpClient.post("https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records") {
                header("Authorization", "Bearer $cloudflareApiToken")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("type", "MX")
                    put("name", "@")
                    put("content", content)
                    put("priority", priority)
                    put("comment", comment)
                }.toString())
            }
        }

        // Add SPF record
        httpClient.post("https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records") {
            header("Authorization", "Bearer $cloudflareApiToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("type", "TXT")
                put("name", "@")
                put("content", "v=spf1 include:_spf.mx.cloudflare.net include:amazonses.com ~all")
                put("comment", "SPF for Cloudflare Email Routing + Resend")
            }.toString())
        }

        logger.info("DNS records created for zone $zoneId")
    }

    private suspend fun setupEmailRouting(zoneId: String) {
        // Enable email routing
        val enableResp = httpClient.post("https://api.cloudflare.com/client/v4/zones/$zoneId/email/routing/enable") {
            header("Authorization", "Bearer $cloudflareApiToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        if (!enableResp.status.isSuccess()) {
            logger.warn("Failed to enable email routing for zone $zoneId: ${enableResp.bodyAsText()}")
        }

        // Create catch-all rule to forward to worker
        val ruleResp = httpClient.post("https://api.cloudflare.com/client/v4/zones/$zoneId/email/routing/rules") {
            header("Authorization", "Bearer $cloudflareApiToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", "Catch-all to Blip ingress worker")
                put("enabled", true)
                putJsonArray("matchers") {
                    add(buildJsonObject {
                        put("type", "all")
                    })
                }
                putJsonArray("actions") {
                    add(buildJsonObject {
                        put("type", "worker")
                        put("value", JsonArray(listOf(JsonPrimitive("blip-email-ingress"))))
                    })
                }
            }.toString())
        }

        if (!ruleResp.status.isSuccess()) {
            logger.warn("Failed to create email routing rule for zone $zoneId: ${ruleResp.bodyAsText()}")
        }

        logger.info("Email routing configured for zone $zoneId")
    }

    private suspend fun checkCloudflareZoneStatus(zoneId: String): Pair<List<String>, Boolean> {
        val resp = httpClient.get("https://api.cloudflare.com/client/v4/zones/$zoneId") {
            header("Authorization", "Bearer $cloudflareApiToken")
        }

        if (!resp.status.isSuccess()) return Pair(emptyList(), false)

        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val result = body["result"]?.jsonObject ?: return Pair(emptyList(), false)
        val status = result["status"]?.jsonPrimitive?.content
        val nameservers = result["name_servers"]?.jsonArray?.map {
            it.jsonPrimitive.content
        } ?: emptyList()

        return Pair(nameservers, status == "active")
    }

    private suspend fun deleteCloudflareZone(zoneId: String) {
        try {
            httpClient.delete("https://api.cloudflare.com/client/v4/zones/$zoneId") {
                header("Authorization", "Bearer $cloudflareApiToken")
            }
        } catch (e: Exception) {
            logger.error("Failed to delete Cloudflare zone $zoneId", e)
        }
    }

    // -- Resend API --

    private suspend fun addResendDomain(domain: String): String? {
        if (resendApiKey.isBlank()) {
            logger.warn("Resend API key not configured, skipping domain add for $domain")
            return null
        }

        val resp = httpClient.post("https://api.resend.com/domains") {
            header("Authorization", "Bearer $resendApiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", domain)
            }.toString())
        }

        if (!resp.status.isSuccess()) {
            logger.error("Failed to add Resend domain $domain: ${resp.bodyAsText()}")
            return null
        }

        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return body["id"]?.jsonPrimitive?.content
    }

    private suspend fun checkResendDomainStatus(resendDomainId: String): Pair<List<DnsRecord>, Boolean> {
        val resp = httpClient.get("https://api.resend.com/domains/$resendDomainId") {
            header("Authorization", "Bearer $resendApiKey")
        }

        if (!resp.status.isSuccess()) return Pair(emptyList(), false)

        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val status = body["status"]?.jsonPrimitive?.content
        val records = mutableListOf<DnsRecord>()

        // Parse DNS records Resend wants configured
        body["records"]?.jsonArray?.forEach { record ->
            val obj = record.jsonObject
            records.add(DnsRecord(
                type = obj["record_type"]?.jsonPrimitive?.content ?: obj["type"]?.jsonPrimitive?.content ?: "",
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                value = obj["value"]?.jsonPrimitive?.content ?: "",
                verified = obj["status"]?.jsonPrimitive?.content == "verified",
            ))
        }

        return Pair(records, status == "verified")
    }

    private suspend fun deleteResendDomain(resendDomainId: String) {
        try {
            httpClient.delete("https://api.resend.com/domains/$resendDomainId") {
                header("Authorization", "Bearer $resendApiKey")
            }
        } catch (e: Exception) {
            logger.error("Failed to delete Resend domain $resendDomainId", e)
        }
    }

    // -- Helpers --

    private fun Map<String, String?>.toDomainDTO(): DomainDTO {
        return DomainDTO(
            id = this["id"]!!,
            domain = this["domain"]!!,
            status = DomainStatus.valueOf(this["status"] ?: "PENDING_DNS"),
            cloudflareZoneId = this["cloudflare_zone_id"],
            resendDomainId = this["resend_domain_id"],
            createdAt = this["created_at"]!!,
            verifiedAt = this["verified_at"],
        )
    }
}
