package com.aminopy.roohvpn.server

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
        require(id.isNotBlank())
        require(host.isNotBlank())
        require(port in 1..65535)
        require(region.isNotBlank())
        require(engineId.isNotBlank())
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
