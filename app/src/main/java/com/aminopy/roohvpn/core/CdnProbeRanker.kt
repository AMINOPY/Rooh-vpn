package com.aminopy.roohvpn.core

/**
 * Ranks only measured endpoint probes. Never promotes an untested candidate
 * by treating a missing RTT as zero, and never claims that TLS reachability is
 * equivalent to an authenticated Xray tunnel.
 */
object CdnProbeRanker {
    fun rank(results: Collection<CdnEndpointProbe.Result>): List<CdnEndpointProbe.Result> =
        results.sortedWith(
            compareBy<CdnEndpointProbe.Result> { !it.isTlsReachable }
                .thenBy { it.failureRatePercent }
                .thenBy { it.medianHandshakeMs ?: Long.MAX_VALUE }
                .thenBy { it.jitterMs ?: Long.MAX_VALUE }
                .thenBy { it.candidate.edgeIp }
                .thenBy { it.candidate.sni }
        )

    /** Filters to the requested hint only; it does not geolocate or prove the edge's country. */
    fun forRegion(
        results: Collection<CdnEndpointProbe.Result>,
        region: String
    ): List<CdnEndpointProbe.Result> {
        val normalized = region.trim().uppercase()
        require(normalized in setOf("IR", "US", "DE", "TR", "KR", "OTHER", "UNKNOWN"))
        return rank(results.filter { it.candidate.regionHint == normalized })
    }
}
