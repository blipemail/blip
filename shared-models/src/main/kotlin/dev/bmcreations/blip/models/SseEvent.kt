package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
data class SseEvent(
    val type: String,
    val data: EmailSummary
)
