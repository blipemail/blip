package dev.bmcreations.blip.server.services

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Destination checks for outbound webhook requests. */
object SsrfGuard {
    /** True if [addr] must not be contacted by webhook delivery. */
    fun isBlocked(addr: InetAddress): Boolean {
        embeddedIPv4(addr)?.let { return isBlocked(it) }
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress
        ) return true
        val b = addr.address
        return when (addr) {
            is Inet4Address -> {
                val b0 = b[0].toInt() and 0xff
                val b1 = b[1].toInt() and 0xff
                b0 == 0 ||                                  // 0.0.0.0/8
                    (b0 == 100 && b1 in 64..127) ||         // 100.64.0.0/10 CGNAT
                    (b0 == 169 && b1 == 254) ||             // 169.254.0.0/16
                    b0 >= 240                               // 240.0.0.0/4 reserved + broadcast
            }
            is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc // fc00::/7 unique local
            else -> true
        }
    }

    /** Throws [IllegalArgumentException] if [addr] is blocked. */
    fun validateTargetAddress(addr: InetAddress) {
        require(!isBlocked(addr)) { "Webhook URL must not resolve to a private or reserved address" }
    }

    /** Unwraps IPv4-mapped (::ffff:a.b.c.d), IPv4-compatible (::a.b.c.d) and NAT64 (64:ff9b::/96) forms. */
    private fun embeddedIPv4(addr: InetAddress): InetAddress? {
        if (addr !is Inet6Address) return null
        val b = addr.address
        val zeros10 = (0..9).all { b[it].toInt() == 0 }
        val mapped = zeros10 && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
        val compat = (0..11).all { b[it].toInt() == 0 } && (12..15).any { b[it].toInt() != 0 } &&
            !(b[12].toInt() == 0 && b[13].toInt() == 0 && b[14].toInt() == 0 && b[15].toInt() == 1)
        val nat64 = b[0].toInt() == 0x00 && (b[1].toInt() and 0xff) == 0x64 && (b[2].toInt() and 0xff) == 0xff &&
            (b[3].toInt() and 0xff) == 0x9b && (4..11).all { b[it].toInt() == 0 }
        if (!mapped && !compat && !nat64) return null
        return InetAddress.getByAddress(b.copyOfRange(12, 16))
    }
}
