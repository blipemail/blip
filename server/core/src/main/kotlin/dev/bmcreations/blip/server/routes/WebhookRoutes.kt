package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.CreateWebhookRequest
import dev.bmcreations.blip.models.CreateWebhookResponse
import dev.bmcreations.blip.server.TierLimitException
import dev.bmcreations.blip.server.services.InboxService
import dev.bmcreations.blip.server.services.SessionService
import dev.bmcreations.blip.server.services.WebhookService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.webhookRoutes(
    webhookService: WebhookService,
    sessionService: SessionService,
    inboxService: InboxService,
) {
    post("/v1/webhooks") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        if (!session.tier.webhooksEnabled) {
            throw TierLimitException("Webhooks require Pro plan")
        }

        val request = call.receive<CreateWebhookRequest>()
        if (request.inboxId != null) {
            require(inboxService.ownsInbox(request.inboxId!!, session.id, session.userId)) { "Access denied" }
        }
        val webhook = webhookService.createWebhook(session.id, request, session.userId)
        call.respond(HttpStatusCode.Created, CreateWebhookResponse(webhook))
    }

    get("/v1/webhooks") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        val webhooks = webhookService.listWebhooks(session.id, session.userId)
        call.respond(webhooks)
    }

    delete("/v1/webhooks/{id}") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        val webhookId = call.parameters["id"]!!
        webhookService.deleteWebhook(webhookId, session.id, session.userId)
        call.respond(HttpStatusCode.NoContent)
    }

    patch("/v1/webhooks/{id}") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        val webhookId = call.parameters["id"]!!
        val body = call.receive<Map<String, Boolean>>()
        val enabled = body["enabled"] ?: throw IllegalArgumentException("Missing 'enabled' field")
        webhookService.toggleWebhook(webhookId, session.id, enabled, session.userId)
        call.respond(HttpStatusCode.OK, mapOf("enabled" to enabled))
    }

    get("/v1/webhooks/{id}/deliveries") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        val webhookId = call.parameters["id"]!!
        webhookService.assertOwnsWebhook(webhookId, session.id, session.userId)
        val deliveries = webhookService.getDeliveryLog(webhookId)
        call.respond(deliveries)
    }
}
