package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
data class SessionDTO(
    val id: String,
    val token: String,
    val tier: Tier,
    val userId: String? = null,
    val expiresAt: String,
    val hasPro: Boolean = false,
    val hasAgent: Boolean = false,
)

@Serializable
data class CreateSessionResponse(
    val token: String,
    val session: SessionDTO
)
