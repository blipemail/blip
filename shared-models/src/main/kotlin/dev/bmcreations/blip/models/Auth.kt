package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int = 600,
    val interval: Int = 2,
)

@Serializable
data class DeviceCodePollResponse(
    val status: DeviceCodeStatus,
    val token: String? = null,
    val session: Session? = null,
)

@Serializable
enum class DeviceCodeStatus {
    PENDING,
    CONFIRMED,
    EXPIRED,
}

@Serializable
data class DeviceCodeRequest(
    val sessionToken: String? = null,
)

@Serializable
data class MagicLinkRequest(
    val email: String,
    val deviceCode: String? = null,
)

@Serializable
data class MagicLinkResponse(
    val message: String,
    val id: String? = null,
)

@Serializable
data class BillingPortalResponse(
    val url: String,
)

@Serializable
data class CheckoutResponse(
    val url: String,
)
