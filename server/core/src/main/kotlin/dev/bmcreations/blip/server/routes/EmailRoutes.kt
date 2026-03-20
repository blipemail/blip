package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.IngressEmailRequest
import dev.bmcreations.blip.models.Tier
import dev.bmcreations.blip.server.ForbiddenException
import dev.bmcreations.blip.server.NotFoundException
import dev.bmcreations.blip.server.services.EmailService
import dev.bmcreations.blip.server.services.ExtractionService
import dev.bmcreations.blip.server.services.ForwardingService
import dev.bmcreations.blip.server.services.InboxService
import dev.bmcreations.blip.server.services.SessionService
import dev.bmcreations.blip.server.services.WebhookService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.emailRoutes(
    emailService: EmailService,
    sessionService: SessionService,
    inboxService: InboxService,
    workerSecret: String,
    webhookService: WebhookService? = null,
    forwardingService: ForwardingService? = null,
    extractionService: ExtractionService? = null,
) {
    // Email ingress from Cloudflare Worker
    post("/v1/inboxes/{address}/emails") {
        val secret = call.request.headers["X-Worker-Secret"]
            ?: throw ForbiddenException("Missing worker secret")
        if (secret != workerSecret) {
            throw ForbiddenException("Invalid worker secret")
        }

        val address = call.parameters["address"]!!
        val request = call.receive<IngressEmailRequest>()

        require(request.from.length <= 512) { "From address too long" }
        require(request.subject.length <= 998) { "Subject too long" }
        require((request.textBody?.length ?: 0) <= 1_000_000) { "Text body too large" }
        require((request.htmlBody?.length ?: 0) <= 2_000_000) { "HTML body too large" }

        val inbox = inboxService.getInboxByAddress(address)
            ?: throw NotFoundException("Inbox not found: $address")

        // Sniper inbox: check if window is still open
        if (!inboxService.isSniperWindowOpen(inbox)) {
            call.respond(HttpStatusCode.OK, mapOf("dropped" to true))
            return@post
        }

        // Determine tier for attachment handling
        val sessionId = inboxService.getSessionIdForInbox(inbox.id)
        val tier = sessionId?.let { sessionService.getSessionById(it)?.tier } ?: Tier.FREE
        val stripAttachments = !tier.attachmentsEnabled

        val summary = emailService.ingestEmail(inbox.id, request, stripAttachments, tier.maxAttachmentBytes)

        // Fire webhooks (non-blocking — errors are logged, not propagated)
        if (sessionId != null && webhookService != null) {
            webhookService.deliverWebhooks(inbox.id, sessionId, summary.id, address, request)
        }

        // Forward email to configured addresses
        if (forwardingService != null) {
            val rules = forwardingService.getRulesForInbox(inbox.id)
            for (rule in rules) {
                forwardingService.forwardEmail(
                    fromAddress = request.from,
                    subject = request.subject,
                    textBody = request.textBody,
                    htmlBody = request.htmlBody,
                    forwardTo = rule.forwardToEmail,
                )
            }
        }

        call.respond(HttpStatusCode.Created, summary)
    }

    // Get email detail
    get("/v1/emails/{id}") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        val emailId = call.parameters["id"]!!

        val inboxId = emailService.getEmailInboxId(emailId)
        require(inboxService.ownsInbox(inboxId, session.id, session.userId)) { "Access denied" }

        val email = emailService.getEmail(emailId)
        call.respond(email)
    }

    // Download attachment
    get("/v1/emails/{id}/attachments/{name}") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        val emailId = call.parameters["id"]!!
        val name = call.parameters["name"]!!

        val inboxId = emailService.getEmailInboxId(emailId)
        require(inboxService.ownsInbox(inboxId, session.id, session.userId)) { "Access denied" }

        val (contentType, data) = emailService.getAttachment(emailId, name, inboxId)
        call.respondBytes(data, ContentType.parse(contentType))
    }

    // Extract OTP codes and verification links from a specific email
    get("/v1/emails/{id}/extract") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        val emailId = call.parameters["id"]!!

        val inboxId = emailService.getEmailInboxId(emailId)
        require(inboxService.ownsInbox(inboxId, session.id, session.userId)) { "Access denied" }

        val email = emailService.getEmail(emailId)
        val result = (extractionService ?: ExtractionService()).extract(email.textBody, email.htmlBody)
        call.respond(result)
    }

    // Extract from the most recent email in an inbox (fire-and-forget pattern for agents)
    get("/v1/inboxes/{id}/extract") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        val inboxId = call.parameters["id"]!!

        require(inboxService.ownsInbox(inboxId, session.id, session.userId)) { "Access denied" }

        val email = emailService.getLatestEmail(inboxId)
            ?: throw NotFoundException("No emails in inbox")

        val result = (extractionService ?: ExtractionService()).extract(email.textBody, email.htmlBody)
        call.respond(result)
    }
}
