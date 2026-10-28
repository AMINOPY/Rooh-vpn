package com.aminopy.roohvpn.core

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * No-manual-config bootstrap for a deployment that operates a signed fronting
 * directory. The app owner must pin the directory's Ed25519 key and provide its
 * HTTPS URL; this client never trusts arbitrary unsigned public config lists.
 *
 * Call from a worker thread. TCP edge RTT is only a reachability preflight, not
 * proof that the origin accepts the selected SNI/Host or that VPN traffic works.
 */
internal object XrayFrontingAutoConnector {
    fun connect(
        directoryUrl: String,
        pinnedEd25519PublicKeyX509Base64: String,
        coordinator: ConnectionCoordinator,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): EngineFailure? {
        val envelope = fetchSignedEnvelope(directoryUrl)
            ?: return failure("FRONTING_DIRECTORY_UNAVAILABLE", true)
        val parsed = XrayFrontingDirectory.parseSigned(
            envelope,
            pinnedEd25519PublicKeyX509Base64,
            nowEpochSeconds
        )
        envelope.fill(0)
        if (!parsed.isSuccess) {
            return failure("FRONTING_DIRECTORY_REJECTED", false)
        }

        val measurements = parsed.candidates
            .sortedByDescending { it.priority }
            .take(MAX_PROBES)
            .map { candidate -> probe(candidate) }
        val ranked = XrayFrontingDirectory.rank(
            measurements,
            System.currentTimeMillis(),
            System.currentTimeMillis() / 1000L
        )
        if (ranked.isEmpty()) return failure("NO_REACHABLE_FRONTING_EDGE", true)

        var lastFailure: EngineFailure? = null
        for (candidate in ranked) {
            val result = coordinator.connect(
                EngineId.XRAY,
                candidate.profile.copyOf(),
                EngineLaunchOptions(
                    xraySni = candidate.sni,
                    xrayCdnEdgeIp = candidate.cdnEdgeIp,
                    xrayHostHeader = candidate.hostHeader
                )
            )
            if (result == null) return null
            lastFailure = result
            // If cleanup was not confirmed, the coordinator deliberately reserves
            // the engine. Never try another engine over a possibly live TUN.
            if (coordinator.activeEngine() != null) return result
        }
        return lastFailure ?: failure("FRONTING_CONNECT_FAILED", true)
    }

    private fun fetchSignedEnvelope(rawUrl: String): ByteArray? {
        var connection: HttpsURLConnection? = null
        return try {
            val url = URL(rawUrl)
            if (!url.protocol.equals("https", ignoreCase = true) ||
                url.host.isBlank() || url.userInfo != null || url.ref != null
            ) return null
            connection = (url.openConnection() as? HttpsURLConnection) ?: return null
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) return null
            val length = connection.contentLengthLong
            if (length > MAX_ENVELOPE_BYTES) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_ENVELOPE_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun probe(candidate: XrayFrontingDirectory.Candidate): XrayFrontingDirectory.MeasuredCandidate {
        val startedAt = System.nanoTime()
        val nowMillis = System.currentTimeMillis()
        val reachable = try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(
                    InetSocketAddress(candidate.cdnEdgeIp, CDN_TLS_PORT),
                    EDGE_CONNECT_TIMEOUT_MILLIS
                )
            }
            true
        } catch (_: Exception) {
            false
        }
        val elapsedMillis = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
        return XrayFrontingDirectory.MeasuredCandidate(
            candidate = candidate,
            rttMillis = if (reachable) elapsedMillis else 0L,
            measuredAtEpochMillis = nowMillis,
            connectable = reachable
        )
    }

    private fun failure(code: String, retryable: Boolean) = EngineFailure(
        code = code,
        safeMessage = when (code) {
            "FRONTING_DIRECTORY_UNAVAILABLE" -> "Automatic fronting service is unavailable."
            "FRONTING_DIRECTORY_REJECTED" -> "Automatic fronting directory failed its trust or expiry checks."
            "NO_REACHABLE_FRONTING_EDGE" -> "No fronting edge passed the current network reachability check."
            else -> "Automatic fronting connection could not be started."
        },
        retryable = retryable
    )

    private const val CDN_TLS_PORT = 443
    private const val MAX_PROBES = 12
    private const val MAX_ENVELOPE_BYTES = 2 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MILLIS = 4_000
    private const val READ_TIMEOUT_MILLIS = 4_000
    private const val EDGE_CONNECT_TIMEOUT_MILLIS = 1_500
}
