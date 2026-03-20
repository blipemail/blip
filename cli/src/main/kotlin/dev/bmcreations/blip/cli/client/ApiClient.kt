package dev.bmcreations.blip.cli.client

import dev.bmcreations.blip.cli.config.ConfigManager
import dev.bmcreations.blip.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class ApiClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val baseUrl get() = ConfigManager.getApiUrl()

    private suspend fun ensureToken(): String {
        val existing = ConfigManager.getToken()
        if (existing != null) {
            // Validate
            try {
                val resp = client.get("$baseUrl/v1/sessions/me") {
                    header("Authorization", "Bearer $existing")
                }
                if (resp.status == HttpStatusCode.OK) return existing
            } catch (_: Exception) {}
        }

        // Create new session
        val resp = try {
            client.post("$baseUrl/v1/sessions") {
                contentType(ContentType.Application.Json)
            }
        } catch (e: Exception) {
            throw RuntimeException("Cannot connect to $baseUrl — is the server running?")
        }
        val result = resp.body<CreateSessionResponse>()
        ConfigManager.saveToken(result.token)
        return result.token
    }

    suspend fun createInbox(request: CreateInboxRequest = CreateInboxRequest()): InboxDTO {
        val token = ensureToken()
        val resp = client.post("$baseUrl/v1/inboxes") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException("Failed to create inbox: ${resp.bodyAsText()}")
        }
        return resp.body<CreateInboxResponse>().inbox
    }

    suspend fun listInboxes(): List<InboxDTO> {
        val token = ensureToken()
        val resp = client.get("$baseUrl/v1/inboxes") {
            header("Authorization", "Bearer $token")
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException("Failed to list inboxes: ${resp.bodyAsText()}")
        }
        return resp.body<List<InboxDTO>>()
    }

    suspend fun getInbox(id: String): InboxDetailDTO {
        val token = ensureToken()
        val resp = client.get("$baseUrl/v1/inboxes/$id") {
            header("Authorization", "Bearer $token")
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException("Inbox not found: $id")
        }
        return resp.body<InboxDetailDTO>()
    }

    suspend fun getEmail(id: String): EmailDetailDTO {
        val token = ensureToken()
        val resp = client.get("$baseUrl/v1/emails/$id") {
            header("Authorization", "Bearer $token")
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException("Email not found: $id")
        }
        return resp.body<EmailDetailDTO>()
    }

    suspend fun getSession(): SessionDTO {
        val token = ensureToken()
        val resp = client.get("$baseUrl/v1/sessions/me") {
            header("Authorization", "Bearer $token")
        }
        return resp.body<SessionDTO>()
    }

    suspend fun createDeviceCode(): DeviceCodeResponse {
        val currentToken = ConfigManager.getToken()
        val resp = client.post("$baseUrl/v1/auth/device") {
            contentType(ContentType.Application.Json)
            setBody(DeviceCodeRequest(sessionToken = currentToken))
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException("Failed to start login: ${resp.bodyAsText()}")
        }
        return resp.body<DeviceCodeResponse>()
    }

    suspend fun pollDeviceCode(deviceCode: String): DeviceCodePollResponse {
        val resp = client.get("$baseUrl/v1/auth/device/$deviceCode")
        if (!resp.status.isSuccess()) {
            throw RuntimeException("Poll failed: ${resp.bodyAsText()}")
        }
        return resp.body<DeviceCodePollResponse>()
    }

    suspend fun replyToEmail(emailId: String, body: String): ReplyResponse {
        val token = ensureToken()
        val resp = client.post("$baseUrl/v1/emails/$emailId/reply") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(ReplyRequest(body))
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException("Failed to send reply: ${resp.bodyAsText()}")
        }
        return resp.body<ReplyResponse>()
    }

    suspend fun getBillingPortalUrl(): String {
        val token = ensureToken()
        val resp = client.post("$baseUrl/v1/billing/portal") {
            header("Authorization", "Bearer $token")
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException(resp.bodyAsText())
        }
        return resp.body<BillingPortalResponse>().url
    }

    suspend fun deleteAccount() {
        val token = ensureToken()
        val resp = client.delete("$baseUrl/v1/account") {
            header("Authorization", "Bearer $token")
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException("Failed to delete account: ${resp.bodyAsText()}")
        }
    }

    fun getSseUrl(inboxId: String): String {
        val token = ConfigManager.getToken() ?: ""
        return "$baseUrl/v1/inboxes/$inboxId/sse?token=$token"
    }

    fun close() {
        client.close()
    }
}
