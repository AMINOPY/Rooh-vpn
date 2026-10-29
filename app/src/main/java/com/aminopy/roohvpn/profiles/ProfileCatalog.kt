package com.aminopy.roohvpn.profiles

import java.security.MessageDigest
import java.util.Locale

/**
 * Non-secret index for saved profiles. Raw configuration bytes remain in
 * EncryptedProfileStore and are never retained by this catalog.
 */
data class ProfileSummary(
    val id: String,
    val displayName: String,
    val engineId: String,
    val configFingerprint: String,
    val sourceId: String? = null,
    val endpointHost: String? = null,
    val endpointPort: Int? = null,
    val measuredLatencyMs: Long? = null,
    val latencyMeasuredAtEpochMs: Long? = null,
    val isProtected: Boolean = false,
    val isValid: Boolean = true
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(displayName.length <= 256)
        require(engineId.isNotBlank() && engineId.length <= 32)
        require(configFingerprint.matches(Regex("[a-f0-9]{64}")))
        require(endpointPort == null || endpointPort in 1..65535)
        require(measuredLatencyMs == null || measuredLatencyMs >= 0)
        require(latencyMeasuredAtEpochMs == null || latencyMeasuredAtEpochMs >= 0)
    }
}

enum class ProfileSort {
    NAME,
    ENGINE,
    MEASURED_LATENCY
}

/**
 * Safe profile-list operations inspired by common VPN client workflows:
 * cross-source deduplication, multi-field search and ranking by measured RTT.
 *
 * A TCP reachability time is not a VPN handshake or throughput test. The
 * caller must label the measurement accurately and only supply observed values.
 */
object ProfileCatalog {
    fun fingerprint(configuration: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(configuration)
        return try {
            digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        } finally {
            digest.fill(0)
        }
    }

    fun search(profiles: Collection<ProfileSummary>, query: String): List<ProfileSummary> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return profiles.toList()
        return profiles.filter { profile ->
            val haystack = listOfNotNull(
                profile.displayName,
                profile.engineId,
                profile.sourceId,
                profile.endpointHost,
                profile.endpointPort?.toString()
            ).joinToString(" ").lowercase(Locale.ROOT)
            terms.all(haystack::contains)
        }
    }

    fun sort(
        profiles: Collection<ProfileSummary>,
        mode: ProfileSort,
        nowEpochMs: Long,
        freshnessWindowMs: Long = DEFAULT_LATENCY_FRESHNESS_MS
    ): List<ProfileSummary> {
        require(nowEpochMs >= 0)
        require(freshnessWindowMs >= 0)
        val stable = profiles.toList()
        return when (mode) {
            ProfileSort.NAME -> stable.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            ProfileSort.ENGINE -> stable.sortedWith(
                compareBy<ProfileSummary> { it.engineId.lowercase(Locale.ROOT) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            )
            ProfileSort.MEASURED_LATENCY -> stable.sortedWith(
                compareBy<ProfileSummary> { profile ->
                    val measuredAt = profile.latencyMeasuredAtEpochMs
                    val latency = profile.measuredLatencyMs
                    if (latency != null && measuredAt != null &&
                        measuredAt <= nowEpochMs &&
                        nowEpochMs - measuredAt <= freshnessWindowMs
                    ) 0 else if (latency != null && measuredAt != null) 1 else 2
                }.thenBy { it.measuredLatencyMs ?: Long.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            )
        }
    }

    /**
     * Removes duplicate configurations even when they came from different
     * subscriptions. The retained item is the one with the newest valid latency
     * measurement, then a valid profile, then a deterministic ID.
     */
    fun removeDuplicates(profiles: Collection<ProfileSummary>): List<ProfileSummary> {
        return profiles
            .groupBy { it.configFingerprint }
            .values
            .map { duplicates ->
                duplicates.maxWithOrNull(
                    compareBy<ProfileSummary> { it.latencyMeasuredAtEpochMs ?: -1L }
                        .thenBy { if (it.measuredLatencyMs != null) 1 else 0 }
                        .thenBy { if (it.isValid) 1 else 0 }
                        .thenBy { it.id }
                )!!
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }.thenBy { it.id })
    }

    /** Attach a real measurement; invalid or untested results are not fabricated. */
    fun withMeasuredLatency(
        profile: ProfileSummary,
        latencyMs: Long?,
        measuredAtEpochMs: Long
    ): ProfileSummary {
        require(measuredAtEpochMs >= 0)
        require(latencyMs == null || latencyMs >= 0)
        return profile.copy(
            measuredLatencyMs = latencyMs,
            latencyMeasuredAtEpochMs = if (latencyMs == null) null else measuredAtEpochMs
        )
    }

    const val DEFAULT_LATENCY_FRESHNESS_MS: Long = 30L * 60L * 1000L
}
