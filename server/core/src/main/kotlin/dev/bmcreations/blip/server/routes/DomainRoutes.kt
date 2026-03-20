package dev.bmcreations.blip.server.routes

import dev.bmcreations.blip.models.CreateDomainRequest
import dev.bmcreations.blip.server.ForbiddenException
import dev.bmcreations.blip.server.services.DomainService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.domainRoutes(domainService: DomainService, adminSecret: String) {
    // Public: list active domain names
    get("/v1/domains") {
        val domains = domainService.listActiveDomains()
        call.respond(domains)
    }

    // Admin endpoints
    route("/v1/admin/domains") {
        // Auth check for all admin routes
        fun io.ktor.server.routing.RoutingContext.requireAdmin() {
            val secret = call.request.headers["X-Worker-Secret"]
            if (secret != adminSecret) {
                throw ForbiddenException("Admin access required")
            }
        }

        get {
            requireAdmin()
            val domains = domainService.listAllDomains()
            call.respond(domains)
        }

        post {
            requireAdmin()
            val req = call.receive<CreateDomainRequest>()
            val domain = domainService.addDomain(req.domain)
            call.respond(HttpStatusCode.Created, domain)
        }

        post("/{id}/verify") {
            requireAdmin()
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val status = domainService.verifyDomain(id)
            call.respond(status)
        }

        post("/{id}/disable") {
            requireAdmin()
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            domainService.disableDomain(id)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/{id}") {
            requireAdmin()
            val id = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            domainService.removeDomain(id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
