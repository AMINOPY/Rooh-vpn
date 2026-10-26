package com.aminopy.roohvpn.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSelectorTest {
    private val fast = ServerCandidate("fast", "fast.example", 443, "EU", "xray")
    private val slowerHighPriority = ServerCandidate("priority", "priority.example", 443, "EU", "xray", priority = 10)
    private val dead = ServerCandidate("dead", "dead.example", 443, "EU", "xray")

    @Test
    fun selectsLowestLatencyReachableCandidate() {
        val selector = ServerSelector(EndpointProbe { candidate ->
            when (candidate.id) {
                "fast" -> 35L
                "priority" -> 70L
                else -> null
            }
        })

        assertEquals("fast", selector.select(listOf(slowerHighPriority, dead, fast))?.candidate?.id)
    }

    @Test
    fun priorityBreaksEqualLatencyTies() {
        val selector = ServerSelector(EndpointProbe { 42L })

        assertEquals(
            "priority",
            selector.select(listOf(fast, slowerHighPriority))?.candidate?.id
        )
    }

    @Test
    fun returnsNullWhenAllCandidatesFail() {
        val selector = ServerSelector(EndpointProbe { null })

        assertNull(selector.select(listOf(fast, dead)))
    }
}
