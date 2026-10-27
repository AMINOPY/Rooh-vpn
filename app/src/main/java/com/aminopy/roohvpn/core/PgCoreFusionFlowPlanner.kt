package com.aminopy.roohvpn.core

/**
 * Combines fresh-measurement path selection with atomic flow-capacity
 * reservation. A successful plan pins a NEW flow to one path until its lease
 * is closed; it does not move existing flows or forward packets itself.
 */
class PgCoreFusionFlowPlanner(
    private val allocator: PgCoreFusionAllocator = PgCoreFusionAllocator()
) {
    sealed class PlanResult {
        data class Planned(
            val path: FusionPathSnapshot,
            val score: Double,
            val lease: PgCoreFusionAllocator.Lease
        ) : PlanResult()

        data class Rejected(val reason: Reason) : PlanResult()

        enum class Reason {
            INVALID_PATH_CONFIGURATION,
            NO_PATH_AVAILABLE,
            CAPACITY_CHANGED_RETRY
        }
    }

    fun planNewFlow(
        paths: List<FusionPathSnapshot>,
        nowMillis: Long,
        maxSampleAgeMillis: Long = 30_000L
    ): PlanResult {
        for (path in paths) {
            if (!allocator.configure(path.id, path.maxConcurrentFlows)) {
                return PlanResult.Rejected(PlanResult.Reason.INVALID_PATH_CONFIGURATION)
            }
        }

        val effectivePaths = paths.map { path ->
            path.copy(activeFlows = maxOf(path.activeFlows, allocator.activeReservations(path.id)))
        }

        return when (val selection = PgCoreFusionSelector.select(
            paths = effectivePaths,
            nowMillis = nowMillis,
            maxSampleAgeMillis = maxSampleAgeMillis
        )) {
            is FusionSelectionResult.Rejected -> {
                // Distinguish "all measured routes are currently full" from
                // "there is no valid route/measurement at all".
                val capacityBlocked = paths.any { path ->
                    val age = nowMillis - path.measuredAtMillis
                    path.healthy &&
                        path.rttMillis != null &&
                        path.packetLossPercent != null &&
                        age >= 0 && age <= maxSampleAgeMillis &&
                        path.maxConcurrentFlows > 0 &&
                        path.activeFlows < path.maxConcurrentFlows &&
                        allocator.activeReservations(path.id) >= path.maxConcurrentFlows
                }
                val reason = if (capacityBlocked) {
                    PlanResult.Reason.CAPACITY_CHANGED_RETRY
                } else {
                    PlanResult.Reason.NO_PATH_AVAILABLE
                }
                PlanResult.Rejected(reason)
            }

            is FusionSelectionResult.Selected -> {
                val lease = allocator.acquire(selection.path.id)
                    ?: return PlanResult.Rejected(PlanResult.Reason.CAPACITY_CHANGED_RETRY)
                PlanResult.Planned(selection.path, selection.score, lease)
            }
        }
    }

    fun activeReservations(pathId: String): Int = allocator.activeReservations(pathId)
}
