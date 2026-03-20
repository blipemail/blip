package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.CreateForwardingRuleRequest
import dev.bmcreations.blip.models.CreateForwardingRuleResponse
import dev.bmcreations.blip.server.TierLimitException
import dev.bmcreations.blip.server.services.ForwardingService
import dev.bmcreations.blip.server.services.InboxService
import dev.bmcreations.blip.server.services.SessionService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.forwardingRoutes(
    forwardingService: ForwardingService,
    sessionService: SessionService,
    inboxService: InboxService,
) {
    post("/v1/inboxes/{id}/forwarding") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])

        if (session.tier.forwardingRules == 0) {
            throw TierLimitException("Forwarding is a Pro feature")
        }

        val inboxId = call.parameters["id"]!!
        require(inboxService.ownsInbox(inboxId, session.id, session.userId)) { "Access denied" }

        val req = call.receive<CreateForwardingRuleRequest>()
        val rule = forwardingService.createRule(inboxId, session.id, req.forwardToEmail, session.tier.forwardingRules)
        call.respond(HttpStatusCode.Created, CreateForwardingRuleResponse(rule))
    }

    get("/v1/inboxes/{id}/forwarding") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        val inboxId = call.parameters["id"]!!
        require(inboxService.ownsInbox(inboxId, session.id, session.userId)) { "Access denied" }

        val rules = forwardingService.listRules(inboxId)
        call.respond(rules)
    }

    delete("/v1/forwarding/{id}") {
        val session = sessionService.extractSession(call.request.headers["Authorization"])
        val ruleId = call.parameters["id"]!!
        forwardingService.deleteRule(ruleId, session.id)
        call.respond(HttpStatusCode.NoContent)
    }
}
