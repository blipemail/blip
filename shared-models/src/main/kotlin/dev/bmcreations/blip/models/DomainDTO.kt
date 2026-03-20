package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
data class DomainDTO(
    val id: String,
    val domain: String,
    val status: DomainStatus,
    val cloudflareZoneId: String? = null,
    val resendDomainId: String? = null,
    val createdAt: String,
    val verifiedAt: String? = null,
)

@Serializable
enum class DomainStatus {
    PENDING_DNS,
    PENDING_VERIFICATION,
    ACTIVE,
    DISABLED,
}

@Serializable
data class CreateDomainRequest(val domain: String)

@Serializable
data class DomainVerificationStatus(
    val domain: String,
    val status: DomainStatus,
    val dnsRecords: List<DnsRecord> = emptyList(),
    val resendVerified: Boolean = false,
    val cloudflareNameservers: List<String> = emptyList(),
)

@Serializable
data class DnsRecord(
    val type: String,
    val name: String,
    val value: String,
    val verified: Boolean = false,
)
