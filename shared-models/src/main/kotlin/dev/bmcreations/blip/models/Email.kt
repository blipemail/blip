package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
data class EmailSummary(
    val id: String,
    val from: String,
    val subject: String,
    val receivedAt: String,
    val preview: String
)

@Serializable
data class EmailDetail(
    val id: String,
    val inboxId: String,
    val from: String,
    val to: String,
    val subject: String,
    val textBody: String? = null,
    val htmlBody: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val receivedAt: String,
    val attachments: List<Attachment> = emptyList(),
    val replies: List<Reply> = emptyList()
)

@Serializable
data class Reply(
    val id: String,
    val body: String,
    val status: String,
    val createdAt: String,
)

@Serializable
data class Attachment(
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
data class ForwardingRule(
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
    val rule: ForwardingRule,
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
