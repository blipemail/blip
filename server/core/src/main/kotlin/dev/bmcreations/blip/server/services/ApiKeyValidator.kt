package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.SessionDTO

/**
 * Interface for API key validation, allowing the core module to support
 * API key auth without depending on the commercial ApiKeyService.
 */
interface ApiKeyValidator {
    suspend fun validateApiKey(rawKey: String): SessionDTO
}
