package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PgCoreFusionSelectorTest {
    private fun path(
        id: String,
        rtt: Long?,
        loss: Double?,
        active: Int = 0,
        healthy: Boolean = true,
        measuredAt: Long = 10_000,
        weight: Int = 1,
        maxFlows: Int = 0
    ) = FusionPathSnapshot(
        id = id,
        healthy = healthy,
        rttMillis = rtt,
        packetLossPercent = loss,
        activeFlows = active,
        weight = weight,
        measuredAtMillis = measuredAt,
        maxConcurrentFlows = maxFlows
    )

    @Test
    fun selectsLowerCostFreshPath() {
        val result = PgCoreFusionSelector.select(
            listOf(path("slow", 150, 0.0), path("fast", 35, 0.0)),
            nowMillis = 10_100
        )
        assertTrue(result is FusionSelectionResult.Selected)
        assertEquals("fast", (result as FusionSelectionResult.Selected).path.id)
    }

    @Test
    fun staleOrMissingMeasurementsAreNeverAssumedToBeZero() {
        val result = PgCoreFusionSelector.select(
            listOf(path("stale", 1, 0.0, measuredAt = 1_000), path("unknown", null, null)),
            nowMillis = 40_000
        )
        assertEquals(
            FusionSelectionResult.Reason.NO_FRESH_HEALTHY_PATH,
            (result as FusionSelectionResult.Rejected).reason
        )
    }

    @Test
    fun unhealthyAndCapacityLimitedPathsAreExcluded() {
        val result = PgCoreFusionSelector.select(
            listOf(
                path("unhealthy", 10, 0.0, healthy = false),
                path("full", 15, 0.0, active = 2, maxFlows = 2)
            ),
            nowMillis = 10_000
        )
        assertEquals(
            FusionSelectionResult.Reason.NO_FRESH_HEALTHY_PATH,
            (result as FusionSelectionResult.Rejected).reason
        )
    }

    @Test
    fun invalidDuplicateIdsAreRejected() {
        val result = PgCoreFusionSelector.select(
            listOf(path("same", 10, 0.0), path("same", 20, 0.0)),
            nowMillis = 10_000
        )
        assertEquals(
            FusionSelectionResult.Reason.INVALID_SNAPSHOT,
            (result as FusionSelectionResult.Rejected).reason
        )
    }

    @Test
    fun weightedScoreCanPreferConfiguredHigherCapacityPath() {
        val result = PgCoreFusionSelector.select(
            listOf(path("normal", 40, 0.0), path("weighted", 70, 0.0, weight = 3)),
            nowMillis = 10_000
        )
        assertEquals("weighted", (result as FusionSelectionResult.Selected).path.id)
    }
}
