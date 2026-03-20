package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
data class Inbox(
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
data class InboxDetail(
    val inbox: Inbox,
    val emails: List<EmailSummary> = emptyList(),
)

@Serializable
data class CreateInboxRequest(
    val slug: String? = null,
    val domain: String? = null,
    val windowMinutes: Int? = null,
)

@Serializable
data class CreateInboxResponse(
    val inbox: Inbox,
)
