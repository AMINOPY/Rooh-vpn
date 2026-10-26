package com.aminopy.roohvpn.server

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress

/**
 * A candidate contains connection metadata only. Credentials and private keys
 * belong in a separate secret-bearing configuration object and must never be
 * put in UI labels, logs, or analytics.
 */
data class ServerCandidate(
    val id: String,
    val host: String,
    val port: Int,
    val region: String,
    val engineId: String,
    val priority: Int = 0
) {
    init {
        require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl))
        require(isValidHost(host))
        require(port in 1..65535)
        require(region.isNotBlank() && region.length <= 80 && region.none(Char::isISOControl))
        require(engineId.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        require(priority in -100_000..100_000)
    }

    private companion object {
        fun isValidHost(value: String): Boolean {
            if (value.isBlank() || value != value.trim() || value.length > 253) return false
            if (value.any { it.isWhitespace() || it.isISOControl() || it in "/\\\\@?#%" }) return false

            // IPv6 literals are accepted only as unbracketed numeric literals;
            // zone identifiers are intentionally rejected.
            if (':' in value) {
                if (!value.matches(Regex("[0-9A-Fa-f:]+")) || value.count { it == ':' } < 2) return false
                return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
            }

            val ascii = runCatching { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES) }
                .getOrNull() ?: return false
            if (ascii.length > 253 || ascii.startsWith('.') || ascii.endsWith('.')) return false
            return ascii.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.first() != '-' && label.last() != '-' &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }
        }
    }
}

/**
 * Implementations should call a legitimate RØH service API or an explicitly
 * authorized provider API. This project intentionally ships with no fake or
 * hard-coded "free VPN" endpoints.
 */
fun interface ServerDirectory {
    fun discover(): List<ServerCandidate>
}

fun interface EndpointProbe {
    /** Returns latency in milliseconds, or null when unreachable/timed out. */
    fun measureLatency(candidate: ServerCandidate): Long?
}

data class RankedServer(
    val candidate: ServerCandidate,
    val latencyMs: Long
)

/**
 * Picks a reachable candidate deterministically. TCP reachability is only a
 * preliminary filter, not proof that a VPN handshake or tunnel works.
 */
class ServerSelector(private val probe: EndpointProbe) {
    fun select(candidates: List<ServerCandidate>): RankedServer? =
        candidates.asSequence()
            .mapNotNull { candidate ->
                val latency = runCatching { probe.measureLatency(candidate) }.getOrNull()
                    ?: return@mapNotNull null
                if (latency < 0) return@mapNotNull null
                RankedServer(candidate, latency)
            }
            .sortedWith(
                compareBy<RankedServer> { it.latencyMs }
                    .thenByDescending { it.candidate.priority }
                    .thenBy { it.candidate.id }
            )
            .firstOrNull()
}
