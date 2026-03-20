package dev.bmcreations.blip.server

import dev.bmcreations.blip.models.Tier
import kotlin.test.*

class TierTest {

    @Test
    fun `FREE tier allows 3 addresses`() {
        assertEquals(3, Tier.FREE.maxAddresses)
    }

    @Test
    fun `FREE tier has 24 hour address TTL`() {
        assertEquals(86_400L, Tier.FREE.addressTtlSeconds)
    }

    @Test
    fun `FREE tier has 24 hour email retention`() {
        assertEquals(86_400L, Tier.FREE.emailRetentionSeconds)
    }

    @Test
    fun `FREE tier disallows custom slugs`() {
        assertFalse(Tier.FREE.customSlugs)
    }

    @Test
    fun `FREE tier disallows webhooks`() {
        assertFalse(Tier.FREE.webhooksEnabled)
    }

    @Test
    fun `FREE tier disallows sniper inbox`() {
        assertFalse(Tier.FREE.sniperInboxEnabled)
    }

    @Test
    fun `FREE tier strips attachments`() {
        assertFalse(Tier.FREE.attachmentsEnabled)
        assertEquals(0L, Tier.FREE.maxAttachmentBytes)
    }

    @Test
    fun `FREE tier disallows API access`() {
        assertFalse(Tier.FREE.apiAccessEnabled)
    }

    @Test
    fun `FREE tier has no forwarding`() {
        assertEquals(0, Tier.FREE.forwardingRules)
    }

    @Test
    fun `FREE tier has no replies`() {
        assertEquals(0, Tier.FREE.maxRepliesPerDay)
    }

    @Test
    fun `PRO tier has unlimited addresses`() {
        assertEquals(Int.MAX_VALUE, Tier.PRO.maxAddresses)
    }

    @Test
    fun `PRO tier has 90 day address TTL`() {
        assertEquals(7_776_000L, Tier.PRO.addressTtlSeconds)
    }

    @Test
    fun `PRO tier has 30 day email retention`() {
        assertEquals(2_592_000L, Tier.PRO.emailRetentionSeconds)
    }

    @Test
    fun `PRO tier allows custom slugs`() {
        assertTrue(Tier.PRO.customSlugs)
    }

    @Test
    fun `PRO tier enables webhooks`() {
        assertTrue(Tier.PRO.webhooksEnabled)
    }

    @Test
    fun `PRO tier enables sniper inbox`() {
        assertTrue(Tier.PRO.sniperInboxEnabled)
    }

    @Test
    fun `PRO tier allows 10MB attachments`() {
        assertTrue(Tier.PRO.attachmentsEnabled)
        assertEquals(10L * 1024 * 1024, Tier.PRO.maxAttachmentBytes)
    }

    @Test
    fun `PRO tier has API access`() {
        assertTrue(Tier.PRO.apiAccessEnabled)
    }

    @Test
    fun `PRO tier has 1 forwarding rule`() {
        assertEquals(1, Tier.PRO.forwardingRules)
    }

    @Test
    fun `PRO tier allows 10 replies per day`() {
        assertEquals(10, Tier.PRO.maxRepliesPerDay)
    }
}
