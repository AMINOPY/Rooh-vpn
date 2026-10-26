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
    fun boundsProbeCountForLargeDirectory() {
        var probeCalls = 0
        val candidates = (0 until 40).map { index ->
            ServerCandidate("server-$index", "server-$index.example", 443, "EU", "xray")
        }
        val selector = ServerSelector(EndpointProbe {
            synchronized(this) { probeCalls++ }
            25L
        })

        selector.select(candidates)

        assertEquals(32, probeCalls)
    }

    @Test
    fun doesNotUseTcpProbeForWireGuardUdpEndpoints() {
        var probeCalls = 0
        val lowPriority = ServerCandidate("wg-low", "low.example", 51820, "EU", "wireguard", priority = 1)
        val highPriority = ServerCandidate("wg-high", "high.example", 51820, "EU", "wireguard", priority = 10)
        val selector = ServerSelector(EndpointProbe {
            probeCalls++
            null
        })

        val selected = selector.select(listOf(lowPriority, highPriority))

        assertEquals("wg-high", selected?.candidate?.id)
        assertEquals(Long.MAX_VALUE, selected?.latencyMs)
        assertEquals(0, probeCalls)
    }

    @Test
    fun skipsWireGuardUdpPeersWhenProbingMixedDirectoryAndFallsBackIfNeeded() {
        var probedIds = mutableListOf<String>()
        val wireGuard = ServerCandidate("wg", "wg.example", 51820, "EU", "wireguard", priority = 5)
        val xray = ServerCandidate("xray", "xray.example", 443, "EU", "xray", priority = 1)
        val selector = ServerSelector(EndpointProbe { candidate ->
            probedIds += candidate.id
            null
        })

        val selected = selector.select(listOf(wireGuard, xray))

        assertEquals(listOf("xray"), probedIds)
        assertEquals("wg", selected?.candidate?.id)
        assertEquals(Long.MAX_VALUE, selected?.latencyMs)
    }

    @Test
    fun returnsNullWhenAllCandidatesFail() {
        val selector = ServerSelector(EndpointProbe { null })

        assertNull(selector.select(listOf(fast, dead)))
    }
}
