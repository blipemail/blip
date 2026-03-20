package dev.bmcreations.blip.server

import io.ktor.server.engine.*
import io.ktor.server.netty.*

/**
 * Standalone entry point for running the open-source core server.
 * Provides all core functionality (inboxes, emails, webhooks, forwarding, SSE)
 * without commercial features (billing, auth, API keys).
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        coreModule(
            CoreConfig(
                tursoUrl = System.getenv("TURSO_URL") ?: "http://localhost:8081",
                tursoToken = System.getenv("TURSO_AUTH_TOKEN") ?: "",
                workerSecret = System.getenv("WORKER_SECRET") ?: "dev-secret",
                resendApiKey = System.getenv("RESEND_API_KEY") ?: "",
                frontendUrl = System.getenv("FRONTEND_URL") ?: "http://localhost:4321",
                cloudflareApiToken = System.getenv("CLOUDFLARE_API_TOKEN") ?: "",
                cloudflareAccountId = System.getenv("CLOUDFLARE_ACCOUNT_ID") ?: "",
            )
        )
    }.start(wait = true)
}
