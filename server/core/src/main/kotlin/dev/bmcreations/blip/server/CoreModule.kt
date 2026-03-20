package dev.bmcreations.blip.server

import dev.bmcreations.blip.models.ErrorResponse
import dev.bmcreations.blip.server.db.Migrations
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.routes.*
import dev.bmcreations.blip.server.services.*
import dev.bmcreations.blip.server.sse.SseManager
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json

data class CoreConfig(
    val tursoUrl: String,
    val tursoToken: String,
    val workerSecret: String,
    val resendApiKey: String,
    val frontendUrl: String,
    val cloudflareApiToken: String,
    val cloudflareAccountId: String,
    val apiKeyValidator: ApiKeyValidator? = null,
    val cleanupContributors: List<CleanupContributor> = emptyList(),
    val turso: TursoClient? = null,
)

data class CoreServices(
    val turso: TursoClient,
    val json: Json,
    val sseManager: SseManager,
    val sessionService: SessionService,
    val inboxService: InboxService,
    val emailService: EmailService,
    val webhookService: WebhookService,
    val forwardingService: ForwardingService,
    val domainService: DomainService,
    val extractionService: ExtractionService,
    val encryptionService: EncryptionService,
    val cleanupService: CleanupService,
)

private val RequestBodyLimit = createApplicationPlugin("RequestBodyLimit") {
    onCall { call ->
        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength != null && contentLength > 10 * 1024 * 1024) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("payload_too_large", "Request body too large"))
        }
    }
}

fun Application.coreModule(config: CoreConfig): CoreServices {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val turso = config.turso ?: TursoClient(config.tursoUrl, config.tursoToken, json)
    val sseManager = SseManager()

    // Run core migrations
    Migrations.run(turso)

    // Services
    val sessionService = SessionService(turso, config.apiKeyValidator)
    val encryptionService = EncryptionService()
    val domainService = DomainService(turso, config.cloudflareApiToken, config.cloudflareAccountId, config.resendApiKey)
    val inboxService = InboxService(turso, sessionService, domainService, sseManager, encryptionService)
    val emailService = EmailService(turso, sseManager, encryptionService, inboxService)
    val extractionService = ExtractionService()
    val forwardingService = ForwardingService(turso, config.resendApiKey)
    val webhookService = WebhookService(turso, encryptionService = encryptionService, inboxService = inboxService)
    val cleanupService = CleanupService(turso, webhookService, sseManager, config.cleanupContributors)

    install(RequestBodyLimit)

    install(ContentNegotiation) {
        json(json)
    }

    install(CORS) {
        val uri = java.net.URI(config.frontendUrl)
        val schemes = listOf(uri.scheme)
        allowHost(uri.host.let { if (uri.port > 0) "$it:${uri.port}" else it }, schemes = schemes)
        // Allow app subdomain and root domain (marketing/pricing pages)
        allowHost("app.${uri.host}", schemes = schemes)
        val rootDomain = uri.host.removePrefix("app.")
        if (rootDomain != uri.host) {
            allowHost(rootDomain, schemes = schemes)
        }
        if (uri.host.contains("localhost")) {
            allowHost("localhost:4321", schemes = listOf("http"))
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        maxAgeInSeconds = 86400
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", cause.message ?: "Bad request"))
        }
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", cause.message ?: "Not found"))
        }
        exception<ForbiddenException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", cause.message ?: "Forbidden"))
        }
        exception<UnauthorizedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", cause.message ?: "Unauthorized"))
        }
        exception<TierLimitException> { call, cause ->
            call.respond(HttpStatusCode.PaymentRequired, ErrorResponse("tier_limit", cause.message ?: "Tier limit exceeded"))
        }
        exception<Throwable> { call, cause ->
            this@coreModule.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error", "Internal server error"))
        }
    }

    install(RateLimit) {
        register(RateLimitName("public")) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: call.request.origin.remoteAddress
            }
        }
        register(RateLimitName("session-create")) {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: call.request.origin.remoteAddress
            }
        }
        register(RateLimitName("authenticated")) {
            rateLimiter(limit = 600, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["Authorization"]?.removePrefix("Bearer ") ?: "anonymous"
            }
        }
        register(RateLimitName("write")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["Authorization"]?.removePrefix("Bearer ") ?: "anonymous"
            }
        }
    }

    // Start cleanup coroutine
    cleanupService.startScheduled(this)

    routing {
        healthRoutes(turso)

        // Email ingress from worker — trusted internal traffic, no rate limit
        emailRoutes(emailService, sessionService, inboxService, config.workerSecret, webhookService, forwardingService, extractionService)

        // Domain management — admin routes use worker secret
        domainRoutes(domainService, config.workerSecret)

        // Session creation (tighter rate limit)
        rateLimit(RateLimitName("session-create")) {
            sessionRoutes(sessionService)
        }

        // Authenticated read endpoints (by token)
        rateLimit(RateLimitName("authenticated")) {
            inboxRoutes(inboxService, sessionService)
            sseRoutes(sseManager, sessionService, inboxService)
        }

        // Write endpoints (by token)
        rateLimit(RateLimitName("write")) {
            webhookRoutes(webhookService, sessionService, inboxService)
            forwardingRoutes(forwardingService, sessionService, inboxService)
        }
    }

    return CoreServices(
        turso = turso,
        json = json,
        sseManager = sseManager,
        sessionService = sessionService,
        inboxService = inboxService,
        emailService = emailService,
        webhookService = webhookService,
        forwardingService = forwardingService,
        domainService = domainService,
        extractionService = extractionService,
        encryptionService = encryptionService,
        cleanupService = cleanupService,
    )
}
