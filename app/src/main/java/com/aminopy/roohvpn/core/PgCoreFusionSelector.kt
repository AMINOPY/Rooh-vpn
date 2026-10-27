package com.aminopy.roohvpn.core

/**
 * Conservative PG CORE path-selection policy for assigning a NEW flow.
 *
 * This is a policy primitive only: it does not create a TUN data plane, start
 * Xray/sing-box, or bond packets from several paths. Measurements must come
 * from real probes supplied by the caller; missing/stale measurements are
 * never treated as zero latency.
 */
data class FusionPathSnapshot(
    val id: String,
    val healthy: Boolean,
    val rttMillis: Long?,
    val packetLossPercent: Double?,
    val activeFlows: Int,
    val weight: Int = 1,
    val measuredAtMillis: Long,
    val maxConcurrentFlows: Int = 0
)

sealed class FusionSelectionResult {
    data class Selected(val path: FusionPathSnapshot, val score: Double) : FusionSelectionResult()
    data class Rejected(val reason: Reason) : FusionSelectionResult()

    enum class Reason {
        NO_PATHS,
        INVALID_SNAPSHOT,
        NO_FRESH_HEALTHY_PATH
    }
}

object PgCoreFusionSelector {
    private const val DEFAULT_MAX_SAMPLE_AGE_MS = 30_000L

    /**
     * Selects a route for one new flow using fresh RTT/loss samples and load.
     * Lower score is better. The caller must atomically reserve capacity after
     * selection; if reservation fails it should refresh snapshots and retry.
     */
    fun select(
        paths: List<FusionPathSnapshot>,
        nowMillis: Long,
        maxSampleAgeMillis: Long = DEFAULT_MAX_SAMPLE_AGE_MS
    ): FusionSelectionResult {
        if (paths.isEmpty()) return FusionSelectionResult.Rejected(
            FusionSelectionResult.Reason.NO_PATHS
        )
        if (nowMillis < 0 || maxSampleAgeMillis < 0) return FusionSelectionResult.Rejected(
            FusionSelectionResult.Reason.INVALID_SNAPSHOT
        )

        val ids = HashSet<String>()
        for (path in paths) {
            if (path.id.isBlank() || !ids.add(path.id) ||
                path.activeFlows < 0 || path.weight <= 0 ||
                path.maxConcurrentFlows < 0 || path.measuredAtMillis < 0 ||
                (path.rttMillis != null && path.rttMillis < 0) ||
                (path.packetLossPercent != null &&
                    (!path.packetLossPercent.isFinite() ||
                        path.packetLossPercent < 0.0 || path.packetLossPercent > 100.0))
            ) {
                return FusionSelectionResult.Rejected(
                    FusionSelectionResult.Reason.INVALID_SNAPSHOT
                )
            }
        }

        val candidates = paths.filter { path ->
            val age = nowMillis - path.measuredAtMillis
            path.healthy &&
                age >= 0 && age <= maxSampleAgeMillis &&
                path.rttMillis != null &&
                path.packetLossPercent != null &&
                (path.maxConcurrentFlows == 0 || path.activeFlows < path.maxConcurrentFlows)
        }
        if (candidates.isEmpty()) return FusionSelectionResult.Rejected(
            FusionSelectionResult.Reason.NO_FRESH_HEALTHY_PATH
        )

        fun score(path: FusionPathSnapshot): Double {
            // Loss is weighted as an RTT penalty. The load factor distributes
            // new flows without claiming that independent paths aggregate speed.
            val qualityCost = 1.0 + path.rttMillis!!.toDouble() +
                10.0 * path.packetLossPercent!!
            return qualityCost * (path.activeFlows.toDouble() + 1.0) / path.weight.toDouble()
        }

        val selected = candidates.minWithOrNull(
            compareBy<FusionPathSnapshot> { score(it) }
                .thenBy { it.activeFlows }
                .thenBy { it.rttMillis }
                .thenBy { it.id }
        ) ?: return FusionSelectionResult.Rejected(
            FusionSelectionResult.Reason.NO_FRESH_HEALTHY_PATH
        )
        return FusionSelectionResult.Selected(selected, score(selected))
    }
}
