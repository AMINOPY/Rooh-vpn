package com.aminopy.roohvpn.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCatalogTest {
    private fun profile(
        id: String,
        name: String = id,
        fingerprint: String = ProfileCatalog.fingerprint(id.toByteArray()),
        latency: Long? = null,
        measuredAt: Long? = null,
        engine: String = "xray",
        source: String? = null,
        valid: Boolean = true
    ) = ProfileSummary(
        id = id,
        displayName = name,
        engineId = engine,
        configFingerprint = fingerprint,
        sourceId = source,
        endpointHost = "edge.example",
        endpointPort = 443,
        measuredLatencyMs = latency,
        latencyMeasuredAtEpochMs = measuredAt,
        isValid = valid
    )

    @Test
    fun fingerprintsAreStableAndDoNotExposeConfiguration() {
        val secret = "vless://user-secret@example.com:443".toByteArray()
        val fingerprint = ProfileCatalog.fingerprint(secret)
        assertEquals(64, fingerprint.length)
        assertEquals(fingerprint, ProfileCatalog.fingerprint(secret))
        assertFalse(fingerprint.contains("user-secret"))
        secret.fill(0)
    }

    @Test
    fun searchMatchesAcrossNameEngineSourceAndEndpoint() {
        val profiles = listOf(profile("a", name = "Frankfurt 01", source = "provider-eu"))
        assertEquals(1, ProfileCatalog.search(profiles, "frankfurt provider-eu").size)
        assertEquals(1, ProfileCatalog.search(profiles, "xray edge.example 443").size)
        assertTrue(ProfileCatalog.search(profiles, "not-found").isEmpty())
    }

    @Test
    fun measuredLatencySortPutsFreshMeasuredBeforeStaleAndUntested() {
        val profiles = listOf(
            profile("untested", latency = null),
            profile("stale", latency = 1, measuredAt = 1),
            profile("slow", latency = 90, measuredAt = 9_900),
            profile("fast", latency = 25, measuredAt = 9_950)
        )
        val sorted = ProfileCatalog.sort(
            profiles,
            ProfileSort.MEASURED_LATENCY,
            nowEpochMs = 10_000,
            freshnessWindowMs = 500
        )
        assertEquals(listOf("fast", "slow", "stale", "untested"), sorted.map { it.id })
    }

    @Test
    fun duplicateCleanupWorksAcrossSourcesAndPrefersRecentMeasuredProfile() {
        val sameFingerprint = "a".repeat(64)
        val kept = ProfileCatalog.removeDuplicates(
            listOf(
                profile("old", fingerprint = sameFingerprint, latency = 20, measuredAt = 100, source = "sub-a"),
                profile("new", fingerprint = sameFingerprint, latency = 35, measuredAt = 200, source = "sub-b"),
                profile("other", fingerprint = "b".repeat(64))
            )
        )
        assertEquals(2, kept.size)
        assertTrue(kept.any { it.id == "new" })
        assertFalse(kept.any { it.id == "old" })
    }

    @Test
    fun failedMeasurementClearsLatencyInsteadOfShowingAnOldValueAsCurrent() {
        val old = profile("p", latency = 15, measuredAt = 100)
        val failed = ProfileCatalog.withMeasuredLatency(old, latencyMs = null, measuredAtEpochMs = 200)
        assertEquals(null, failed.measuredLatencyMs)
        assertEquals(null, failed.latencyMeasuredAtEpochMs)
    }
}
