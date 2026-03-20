package dev.bmcreations.blip.server.services

/**
 * Interface for modules to contribute cleanup tasks to the scheduled cleanup cycle.
 */
interface CleanupContributor {
    suspend fun cleanup()
}
