package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
data class InboxDTO(
    val id: String,
    val address: String,
    val domain: String,
    val createdAt: String,
    val expiresAt: String,
    val emailCount: Int = 0,
    val sniperWindow: SniperWindow? = null,
)

@Serializable
data class SniperWindow(
    val opensAt: String,
    val closesAt: String,
    val sealed: Boolean = false,
)

@Serializable
data class InboxDetailDTO(
    val inbox: InboxDTO,
    val emails: List<EmailSummaryDTO> = emptyList(),
)

@Serializable
data class CreateInboxRequest(
    val slug: String? = null,
    val domain: String? = null,
    val windowMinutes: Int? = null,
)

@Serializable
data class CreateInboxResponse(
    val inbox: InboxDTO,
)
