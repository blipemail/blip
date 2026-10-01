package dev.bmcreations.blip.server

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import java.security.MessageDigest

/** Constant-time string comparison for shared secrets. */
fun secretsMatch(provided: String?, expected: String): Boolean {
    if (provided == null) return false
    val sha = MessageDigest.getInstance("SHA-256")
    val a = sha.digest(provided.toByteArray(Charsets.UTF_8))
    val b = sha.digest(expected.toByteArray(Charsets.UTF_8))
    return MessageDigest.isEqual(a, b)
}

/**
 * Resolves the client IP for rate limiting.
 *
 * The API is served through Cloudflare, which sets CF-Connecting-IP to the address that connected
 * to it and overwrites any client-supplied value, so it is preferred when present. Behind Cloudflare
 * and Railway's edge, the rightmost X-Forwarded-For entry is a proxy address, not the client.
 *
 * X-Forwarded-For is client-controlled except for the entries appended by our own proxies, so the
 * first hop is spoofable. With [trustedProxyHops] = N, the client address is the Nth entry from the
 * right (the one our outermost trusted proxy appended). 0 disables XFF and uses the socket address.
 */
object ClientIp {
    /** Number of trusted reverse proxies in front of the app. Override with TRUSTED_PROXY_HOPS. */
    val defaultTrustedProxyHops: Int =
        System.getenv("TRUSTED_PROXY_HOPS")?.toIntOrNull()?.coerceAtLeast(0) ?: 1

    fun resolve(
        cfConnectingIp: String?,
        forwardedFor: String?,
        remoteAddress: String,
        trustedProxyHops: Int = defaultTrustedProxyHops,
    ): String {
        cfConnectingIp?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return resolve(forwardedFor, remoteAddress, trustedProxyHops)
    }

    fun resolve(
        forwardedFor: String?,
        remoteAddress: String,
        trustedProxyHops: Int = defaultTrustedProxyHops,
    ): String {
        if (trustedProxyHops <= 0) return remoteAddress
        val hops = forwardedFor?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (hops.isEmpty()) return remoteAddress
        return hops[(hops.size - trustedProxyHops).coerceAtLeast(0)]
    }
}

fun ApplicationCall.clientIp(): String =
    ClientIp.resolve(
        request.headers["CF-Connecting-IP"],
        request.headers["X-Forwarded-For"],
        request.origin.remoteAddress,
    )
