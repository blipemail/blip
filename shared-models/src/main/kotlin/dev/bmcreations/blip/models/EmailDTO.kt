package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
data class EmailSummaryDTO(
    val id: String,
    val from: String,
    val subject: String,
    val receivedAt: String,
    val preview: String
)

@Serializable
data class EmailDetailDTO(
    val id: String,
    val inboxId: String,
    val from: String,
    val to: String,
    val subject: String,
    val textBody: String? = null,
    val htmlBody: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val receivedAt: String,
    val attachments: List<AttachmentDTO> = emptyList(),
    val replies: List<ReplyDTO> = emptyList()
)

@Serializable
data class ReplyDTO(
    val id: String,
    val body: String,
    val status: String,
    val createdAt: String,
)

@Serializable
data class AttachmentDTO(
    val name: String,
    val contentType: String,
    val size: Long
)

@Serializable
data class IngressEmailRequest(
    val from: String,
    val to: String,
    val subject: String,
    val textBody: String? = null,
    val htmlBody: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val attachments: List<IngressAttachment> = emptyList()
)

@Serializable
data class IngressAttachment(
    val name: String,
    val contentType: String,
    val contentBase64: String
)

@Serializable
data class ReplyRequest(
    val body: String,
)

@Serializable
data class ReplyResponse(
    val id: String,
    val status: String,
)

@Serializable
data class ForwardingRuleDTO(
    val id: String,
    val inboxId: String,
    val forwardToEmail: String,
    val createdAt: String,
)

@Serializable
data class CreateForwardingRuleRequest(
    val forwardToEmail: String,
)

@Serializable
data class CreateForwardingRuleResponse(
    val rule: ForwardingRuleDTO,
)

@Serializable
data class ExtractionResult(
    val codes: List<ExtractedCode> = emptyList(),
    val links: List<ExtractedLink> = emptyList(),
)

@Serializable
data class ExtractedCode(
    val value: String,
    val type: String,
    val context: String,
)

@Serializable
data class ExtractedLink(
    val url: String,
    val type: String,
    val text: String,
)
