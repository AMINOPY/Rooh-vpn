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
        // Configure all supplied paths before selection. The allocator keeps
        // existing leases if limits are reduced; new reservations fail closed.
        for (path in paths) {
            if (!allocator.configure(path.id, path.maxConcurrentFlows)) {
                return PlanResult.Rejected(PlanResult.Reason.INVALID_PATH_CONFIGURATION)
            }
        }

        return when (val selection = PgCoreFusionSelector.select(
            paths = paths,
            nowMillis = nowMillis,
            maxSampleAgeMillis = maxSampleAgeMillis
        )) {
            is FusionSelectionResult.Rejected ->
                PlanResult.Rejected(PlanResult.Reason.NO_PATH_AVAILABLE)

            is FusionSelectionResult.Selected -> {
                val lease = allocator.acquire(selection.path.id)
                    ?: return PlanResult.Rejected(PlanResult.Reason.CAPACITY_CHANGED_RETRY)
                PlanResult.Planned(selection.path, selection.score, lease)
            }
        }
    }

    fun activeReservations(pathId: String): Int = allocator.activeReservations(pathId)
}
