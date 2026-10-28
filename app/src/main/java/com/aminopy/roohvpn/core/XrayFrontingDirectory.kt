package com.aminopy.roohvpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Signed bootstrap directory for zero-manual-config fronting.
 *
 * A directory must be hosted by an operator that actually controls compatible
 * relays and must be signed by an app-pinned Ed25519 key. SNI/CDN IP discovery
 * alone cannot create a tunnel without a compatible remote endpoint and client
 * credentials; this parser intentionally refuses unsigned public lists.
 */
internal object XrayFrontingDirectory {
    data class Candidate(
        val id: String,
        val profile: ByteArray,
        val sni: String,
        val cdnEdgeIp: String,
        val hostHeader: String,
        val expiresAtEpochSeconds: Long,
        val priority: Int
    ) {
        fun overrides() = XrayCdnProfileAdapter.Overrides(
            sni = sni,
            cdnEdgeIp = cdnEdgeIp,
            hostHeader = hostHeader
        )
    }

    data class MeasuredCandidate(
        val candidate: Candidate,
        val rttMillis: Long,
        val measuredAtEpochMillis: Long,
        val connectable: Boolean
    )

    data class Result(val candidates: List<Candidate>, val errorCode: String? = null) {
        val isSuccess: Boolean get() = errorCode == null
    }

    /**
     * Envelope format:
     * { "payload": "<base64 raw UTF-8 JSON>", "signature": "<base64 Ed25519>" }
     * The signature covers the exact decoded payload bytes, avoiding JSON
     * canonicalization ambiguities.
     *
     * payload format: { "version": 1, "expiresAt": epochSeconds,
     *   "candidates": [{ "id", "profile", "sni", "edgeIp", "host",
     *   "expiresAt", "priority" }] }
     */
    fun parseSigned(
        envelopeBytes: ByteArray,
        pinnedEd25519PublicKeyX509Base64: String,
        nowEpochSeconds: Long
    ): Result {
        if (envelopeBytes.isEmpty() || envelopeBytes.size > MAX_ENVELOPE_BYTES) {
            return Result(emptyList(), "INVALID_ENVELOPE_SIZE")
        }
        return try {
            val envelope = JSONObject(envelopeBytes.toString(Charsets.UTF_8))
            val payloadBytes = Base64.getDecoder().decode(envelope.getString("payload"))
            val signatureBytes = Base64.getDecoder().decode(envelope.getString("signature"))
            if (payloadBytes.isEmpty() || payloadBytes.size > MAX_PAYLOAD_BYTES) {
                return Result(emptyList(), "INVALID_PAYLOAD_SIZE")
            }
            val keyBytes = Base64.getDecoder().decode(pinnedEd25519PublicKeyX509Base64)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update(payloadBytes)
            if (!verifier.verify(signatureBytes)) return Result(emptyList(), "SIGNATURE_INVALID")

            val payload = JSONObject(payloadBytes.toString(Charsets.UTF_8))
            if (payload.optInt("version", -1) != 1) return Result(emptyList(), "UNSUPPORTED_DIRECTORY_VERSION")
            val directoryExpiry = payload.optLong("expiresAt", 0L)
            if (directoryExpiry <= nowEpochSeconds) return Result(emptyList(), "DIRECTORY_EXPIRED")
            val array = payload.optJSONArray("candidates") ?: return Result(emptyList(), "CANDIDATES_MISSING")
            val candidates = ArrayList<Candidate>(minOf(array.length(), MAX_CANDIDATES))
            for (index in 0 until minOf(array.length(), MAX_CANDIDATES)) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val profileText = item.optString("profile")
                val sni = item.optString("sni")
                val edgeIp = item.optString("edgeIp")
                val host = item.optString("host")
                val expiry = item.optLong("expiresAt", 0L)
                val priority = item.optInt("priority", 0)
                if (id.isBlank() || id.length > 96 || expiry <= nowEpochSeconds ||
                    expiry > directoryExpiry || profileText.isBlank() ||
                    profileText.toByteArray(Charsets.UTF_8).size > MAX_PROFILE_BYTES ||
                    sni.isBlank() || edgeIp.isBlank() || host.isBlank() ||
                    priority !in -1000..1000
                ) continue

                val profile = profileText.toByteArray(Charsets.UTF_8)
                if (XrayProfilePreflight.validate(profile) != null) {
                    profile.fill(0)
                    continue
                }
                val transformed = XrayCdnProfileAdapter.apply(
                    profile,
                    XrayCdnProfileAdapter.Overrides(sni, edgeIp, host)
                )
                if (!transformed.isSuccess || XrayTunProfileAdapter.prepare(profile, Candidate(
                    id, profile, sni, edgeIp, host, expiry, priority
                ).overrides()) == null) {
                    profile.fill(0)
                    continue
                }
                candidates.add(Candidate(id, profile, sni, edgeIp, host, expiry, priority))
            }
            if (candidates.isEmpty()) Result(emptyList(), "NO_VALID_CANDIDATES")
            else Result(candidates.distinctBy { it.id })
        } catch (_: Exception) {
            Result(emptyList(), "DIRECTORY_INVALID")
        } catch (_: LinkageError) {
            Result(emptyList(), "CRYPTO_PROVIDER_UNAVAILABLE")
        }
    }

    /**
     * Chooses only a recently measured, connectable candidate. Never interprets
     * missing/stale RTT as zero and never selects an endpoint based on priority
     * alone when it has not passed the live probe.
     */
    fun choose(
        measurements: List<MeasuredCandidate>,
        nowEpochMillis: Long,
        nowEpochSeconds: Long
    ): Candidate? = measurements.asSequence()
        .filter { it.connectable && it.rttMillis in 1..MAX_RTT_MILLIS }
        .filter { it.measuredAtEpochMillis > 0L && nowEpochMillis >= it.measuredAtEpochMillis }
        .filter { nowEpochMillis - it.measuredAtEpochMillis <= MAX_MEASUREMENT_AGE_MILLIS }
        .filter { it.candidate.expiresAtEpochSeconds > nowEpochSeconds }
        .sortedWith(
            compareBy<MeasuredCandidate> { it.rttMillis }
                .thenByDescending { it.candidate.priority }
                .thenBy { it.candidate.id }
        )
        .map { it.candidate }
        .firstOrNull()

    private const val MAX_ENVELOPE_BYTES = 2 * 1024 * 1024
    private const val MAX_PAYLOAD_BYTES = 1536 * 1024
    private const val MAX_PROFILE_BYTES = 256 * 1024
    private const val MAX_CANDIDATES = 64
    private const val MAX_RTT_MILLIS = 60_000L
    private const val MAX_MEASUREMENT_AGE_MILLIS = 60_000L
}
