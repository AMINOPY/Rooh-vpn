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
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}")) && !id.contains(".."))
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
    fun select(candidates: List<ServerCandidate>): RankedServer? {
        if (candidates.isEmpty()) return null

        // Bound work even if a directory returns hundreds of valid endpoints.
        // Provider priority and stable IDs choose the probe window; latency
        // ranks the reachable candidates inside that window.
        val bounded = candidates
            .sortedWith(compareByDescending<ServerCandidate> { it.priority }.thenBy { it.id })
            .take(MAX_CANDIDATES)

        // WireGuard normally uses UDP. A TCP connect probe against its UDP
        // endpoint will systematically fail, so use trusted provider priority
        // as a candidate order and let the real WireGuard handshake decide.
        // This is explicitly not a reachability/latency claim.
        if (bounded.all { it.engineId.equals("wireguard", ignoreCase = true) }) {
            return bounded.firstOrNull()?.let { RankedServer(it, Long.MAX_VALUE) }
        }

        val executor = java.util.concurrent.Executors.newFixedThreadPool(MAX_PARALLEL_PROBES) { task ->
            Thread(task, "roohvpn-endpoint-probe").apply { isDaemon = true }
        }
        return try {
            val tasks = bounded.map { candidate ->
                java.util.concurrent.Callable {
                    val latency = runCatching { probe.measureLatency(candidate) }.getOrNull()
                    if (latency == null || latency < 0L) null else RankedServer(candidate, latency)
                }
            }
            val futures = executor.invokeAll(tasks, TOTAL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            futures.asSequence()
                .filterNot { it.isCancelled }
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
                .sortedWith(
                    compareBy<RankedServer> { it.latencyMs }
                        .thenByDescending { it.candidate.priority }
                        .thenBy { it.candidate.id }
                )
                .firstOrNull()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 32
        const val MAX_PARALLEL_PROBES = 8
        const val TOTAL_TIMEOUT_MS = 8_000L
    }
}
