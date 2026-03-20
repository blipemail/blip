package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val id: String,
    val token: String,
    val tier: Tier,
    val userId: String? = null,
    val expiresAt: String,
    val hasPro: Boolean = false,
    val hasAgent: Boolean = false,
    val stripeCustomerId: String? = null,
)

@Serializable
data class CreateSessionResponse(
    val token: String,
    val session: Session
)
