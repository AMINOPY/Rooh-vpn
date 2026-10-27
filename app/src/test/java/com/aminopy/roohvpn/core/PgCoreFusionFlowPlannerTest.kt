package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PgCoreFusionFlowPlannerTest {
    private fun path(
        id: String,
        rtt: Long,
        active: Int = 0,
        maxFlows: Int = 1,
        measuredAt: Long = 1_000
    ) = FusionPathSnapshot(
        id = id,
        healthy = true,
        rttMillis = rtt,
        packetLossPercent = 0.0,
        activeFlows = active,
        weight = 1,
        measuredAtMillis = measuredAt,
        maxConcurrentFlows = maxFlows
    )

    @Test
    fun plannedFlowHoldsCapacityUntilLeaseIsClosed() {
        val planner = PgCoreFusionFlowPlanner()
        val first = planner.planNewFlow(listOf(path("fast", 20)), nowMillis = 1_000)
        assertTrue(first is PgCoreFusionFlowPlanner.PlanResult.Planned)
        assertEquals(1, planner.activeReservations("fast"))

        val second = planner.planNewFlow(listOf(path("fast", 20)), nowMillis = 1_000)
        assertEquals(
            PgCoreFusionFlowPlanner.PlanResult.Reason.CAPACITY_CHANGED_RETRY,
            (second as PgCoreFusionFlowPlanner.PlanResult.Rejected).reason
        )

        (first as PgCoreFusionFlowPlanner.PlanResult.Planned).lease.close()
        assertEquals(0, planner.activeReservations("fast"))
        val third = planner.planNewFlow(listOf(path("fast", 20)), nowMillis = 1_000)
        assertNotNull(third as? PgCoreFusionFlowPlanner.PlanResult.Planned)
        (third as PgCoreFusionFlowPlanner.PlanResult.Planned).lease.close()
    }

    @Test
    fun choosesOtherPathWhenBestPathIsAlreadyAtCapacity() {
        val planner = PgCoreFusionFlowPlanner()
        val held = planner.planNewFlow(listOf(path("fast", 10)), nowMillis = 1_000)
            as PgCoreFusionFlowPlanner.PlanResult.Planned

        val result = planner.planNewFlow(
            listOf(path("fast", 10), path("backup", 30)),
            nowMillis = 1_000
        )
        assertTrue(result is PgCoreFusionFlowPlanner.PlanResult.Planned)
        assertEquals("backup", (result as PgCoreFusionFlowPlanner.PlanResult.Planned).path.id)
        (result as PgCoreFusionFlowPlanner.PlanResult.Planned).lease.close()
        held.lease.close()
    }

    @Test
    fun staleMeasurementsFailClosed() {
        val planner = PgCoreFusionFlowPlanner()
        val result = planner.planNewFlow(
            listOf(path("stale", 10, measuredAt = 1)),
            nowMillis = 100_000
        )
        assertEquals(
            PgCoreFusionFlowPlanner.PlanResult.Reason.NO_PATH_AVAILABLE,
            (result as PgCoreFusionFlowPlanner.PlanResult.Rejected).reason
        )
        assertNull(result as? PgCoreFusionFlowPlanner.PlanResult.Planned)
    }

    @Test
    fun closingLeaseMoreThanOnceDoesNotReleaseAnotherFlowsCapacity() {
        val planner = PgCoreFusionFlowPlanner()
        val first = planner.planNewFlow(listOf(path("shared", 15, maxFlows = 2)), 1_000)
            as PgCoreFusionFlowPlanner.PlanResult.Planned
        val second = planner.planNewFlow(listOf(path("shared", 15, maxFlows = 2)), 1_000)
            as PgCoreFusionFlowPlanner.PlanResult.Planned

        first.lease.close()
        first.lease.close()
        assertEquals(1, planner.activeReservations("shared"))

        val third = planner.planNewFlow(listOf(path("shared", 15, maxFlows = 2)), 1_000)
        assertTrue(third is PgCoreFusionFlowPlanner.PlanResult.Planned)
        assertEquals(2, planner.activeReservations("shared"))

        (third as PgCoreFusionFlowPlanner.PlanResult.Planned).lease.close()
        second.lease.close()
        assertEquals(0, planner.activeReservations("shared"))
    }

    @Test
    fun invalidCapacityConfigurationIsRejectedWithoutPlanning() {
        val planner = PgCoreFusionFlowPlanner()
        val result = planner.planNewFlow(listOf(path("bad", 15, maxFlows = -1)), 1_000)

        assertEquals(
            PgCoreFusionFlowPlanner.PlanResult.Reason.INVALID_PATH_CONFIGURATION,
            (result as PgCoreFusionFlowPlanner.PlanResult.Rejected).reason
        )
        assertEquals(0, planner.activeReservations("bad"))
    }

    @Test
    fun futureDatedMeasurementIsNotTreatedAsFresh() {
        val planner = PgCoreFusionFlowPlanner()
        val result = planner.planNewFlow(
            listOf(path("clock-skewed", 5, measuredAt = 1_001)),
            nowMillis = 1_000
        )

        assertEquals(
            PgCoreFusionFlowPlanner.PlanResult.Reason.NO_PATH_AVAILABLE,
            (result as PgCoreFusionFlowPlanner.PlanResult.Rejected).reason
        )
    }
    @Test
    fun concurrentPlanningNeverOverbooksPathCapacity() {
        val planner = PgCoreFusionFlowPlanner()
        val start = java.util.concurrent.CountDownLatch(1)
        val planned = java.util.concurrent.atomic.AtomicInteger()
        val leases = java.util.concurrent.ConcurrentLinkedQueue<PgCoreFusionAllocator.Lease>()
        val threads = (0 until 24).map {
            Thread {
                start.await()
                when (val result = planner.planNewFlow(
                    listOf(path("shared", 20, maxFlows = 3)),
                    nowMillis = 1_000
                )) {
                    is PgCoreFusionFlowPlanner.PlanResult.Planned -> {
                        leases.add(result.lease)
                        planned.incrementAndGet()
                    }
                    is PgCoreFusionFlowPlanner.PlanResult.Rejected -> Unit
                }
            }.apply { start() }
        }

        start.countDown()
        threads.forEach { it.join(2_000) }
        assertEquals(3, planned.get())
        assertEquals(3, planner.activeReservations("shared"))

        leases.forEach { it.close() }
        assertEquals(0, planner.activeReservations("shared"))
    }

}
