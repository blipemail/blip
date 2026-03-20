package dev.bmcreations.blip.models

import kotlinx.serialization.Serializable

@Serializable
enum class Tier(
    val maxAddresses: Int,
    val addressTtlSeconds: Long,
    val emailRetentionSeconds: Long,
    val customSlugs: Boolean,
    val webhooksEnabled: Boolean,
    val sniperInboxEnabled: Boolean,
    val attachmentsEnabled: Boolean,
    val maxAttachmentBytes: Long,
    val apiAccessEnabled: Boolean,
    val forwardingRules: Int,
    val maxRepliesPerDay: Int,
) {
    FREE(
        maxAddresses = 3,
        addressTtlSeconds = 86_400,          // 24 hours
        emailRetentionSeconds = 86_400,       // 24 hours
        customSlugs = false,
        webhooksEnabled = false,
        sniperInboxEnabled = false,
        attachmentsEnabled = false,
        maxAttachmentBytes = 0,
        apiAccessEnabled = false,
        forwardingRules = 0,
        maxRepliesPerDay = 0,
    ),
    PRO(
        maxAddresses = Int.MAX_VALUE,
        addressTtlSeconds = 7_776_000,       // 90 days
        emailRetentionSeconds = 2_592_000,    // 30 days
        customSlugs = true,
        webhooksEnabled = true,
        sniperInboxEnabled = true,
        attachmentsEnabled = true,
        maxAttachmentBytes = 10 * 1024 * 1024, // 10MB
        apiAccessEnabled = true,
        forwardingRules = 1,
        maxRepliesPerDay = 10,
    ),
    AGENT(
        maxAddresses = Int.MAX_VALUE,
        addressTtlSeconds = 3_600,            // 1 hour
        emailRetentionSeconds = 86_400,       // 24 hours
        customSlugs = true,
        webhooksEnabled = true,
        sniperInboxEnabled = false,
        attachmentsEnabled = false,
        maxAttachmentBytes = 0,
        apiAccessEnabled = true,
        forwardingRules = 0,
        maxRepliesPerDay = 0,
    ),
}
