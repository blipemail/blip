package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
data class Webhook(
    val id: String,
    val url: String,
    val secret: String,
    val inboxId: String? = null,
    val createdAt: String,
    val enabled: Boolean = true,
)

@Serializable
data class CreateWebhookRequest(
    val url: String,
    val inboxId: String? = null,
)

@Serializable
data class CreateWebhookResponse(
    val webhook: Webhook,
)

@Serializable
data class WebhookPayload(
    val address: String,
    val from: String,
    val subject: String,
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    val receivedAt: String,
    val attachments: List<WebhookAttachmentMeta> = emptyList(),
)

@Serializable
data class WebhookAttachmentMeta(
    val name: String,
    val contentType: String,
    val size: Long,
)

@Serializable
data class WebhookDelivery(
    val id: String,
    val webhookId: String,
    val emailId: String,
    val statusCode: Int? = null,
    val attempts: Int = 0,
    val nextRetryAt: String? = null,
    val completedAt: String? = null,
    val status: DeliveryStatus = DeliveryStatus.PENDING,
)

@Serializable
enum class DeliveryStatus {
    PENDING, SUCCESS, FAILED
}
