package com.aminopy.roohvpn.core

import java.io.Closeable
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Measures TCP + authenticated TLS handshake latency to a candidate CDN edge.
 *
 * This is a reachability preflight, NOT a VPN tunnel test: it cannot prove that
 * the CDN routes a later HTTP Host/path to the intended proxy origin. A real
 * Xray connection and egress check is still required before showing "connected".
 *
 * Call from a worker thread. Uses the platform trust store and never disables
 * certificate or hostname verification.
 */
object CdnEndpointProbe {
    data class Candidate(
        val edgeIp: String,
        val sni: String,
        val port: Int = 443,
        val hostHeader: String? = null,
        val regionHint: String? = null
    )

    data class Result(
        val candidate: Candidate,
        val successfulSamples: Int,
        val attempts: Int,
        val medianHandshakeMs: Long?,
        val jitterMs: Long?,
        val failureRatePercent: Int,
        val grade: Grade,
        val lastErrorCode: String?,
        val measuredAtEpochMs: Long
    ) {
        /** A TLS result is never labelled as a verified VPN connection. */
        val tunnelVerified: Boolean get() = false
        val isTlsReachable: Boolean get() = successfulSamples > 0
    }

    enum class Grade { EXCELLENT, GOOD, FAIR, POOR, UNREACHABLE }

    fun probe(
        candidate: Candidate,
        attempts: Int = 3,
        timeoutMs: Int = 3_000,
        socketFactory: SSLSocketFactory = SSLContext.getDefault().socketFactory
    ): Result {
        require(attempts in 1..5) { "attempts must be between 1 and 5" }
        require(timeoutMs in 500..10_000) { "timeoutMs must be between 500 and 10000" }
        val normalized = validate(candidate) ?: return Result(
            candidate, 0, attempts, null, null, 100, Grade.UNREACHABLE,
            "INVALID_CANDIDATE", System.currentTimeMillis()
        )
        val samples = ArrayList<Long>(attempts)
        var lastError: String? = null
        repeat(attempts) {
            val start = System.nanoTime()
            try {
                openVerifiedTls(normalized, timeoutMs, socketFactory).use { socket ->
                    // Force the TLS handshake before recording a successful sample.
                    socket.startHandshake()
                    samples += ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                }
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                lastError = "TLS_HANDSHAKE_FAILED"
            } catch (e: java.net.SocketTimeoutException) {
                lastError = "TIMEOUT"
            } catch (e: javax.net.ssl.SSLException) {
                lastError = "TLS_FAILED"
            } catch (e: Exception) {
                lastError = "CONNECT_FAILED"
            }
        }
        val sorted = samples.sorted()
        val median = sorted.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
        val jitter = sorted.takeIf { it.size >= 2 }?.let { it.last() - it.first() }
        val failure = ((attempts - samples.size) * 100.0 / attempts).toInt()
        val grade = when {
            samples.isEmpty() -> Grade.UNREACHABLE
            failure > 40 -> Grade.POOR
            median!! <= 180 && (jitter ?: 0L) <= 80 -> Grade.EXCELLENT
            median <= 350 && (jitter ?: 0L) <= 180 -> Grade.GOOD
            median <= 800 -> Grade.FAIR
            else -> Grade.POOR
        }
        return Result(
            normalized, samples.size, attempts, median, jitter, failure, grade,
            lastError, System.currentTimeMillis()
        )
    }

    private fun openVerifiedTls(
        candidate: Candidate,
        timeoutMs: Int,
        factory: SSLSocketFactory
    ): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(candidate.edgeIp, candidate.port), timeoutMs)
            raw.soTimeout = timeoutMs
            val tls = factory.createSocket(raw, candidate.sni, candidate.port, true) as? SSLSocket
                ?: throw javax.net.ssl.SSLException("TLS socket factory returned an unsupported socket")
            val parameters = tls.sslParameters
            parameters.serverNames = listOf(SNIHostName(candidate.sni))
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            tls.sslParameters = parameters
            tls.soTimeout = timeoutMs
            return tls
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) { }
            throw e
        }
    }

    private fun validate(candidate: Candidate): Candidate? {
        if (candidate.port !in 1..65535) return null
        val ip = normalizeIp(candidate.edgeIp) ?: return null
        val sni = normalizeHostname(candidate.sni) ?: return null
        val host = candidate.hostHeader?.let(::normalizeHostname)
        if (candidate.hostHeader != null && host == null) return null
        val region = candidate.regionHint?.trim()?.uppercase(Locale.ROOT)?.takeIf {
            it in setOf("IR", "US", "DE", "TR", "KR", "OTHER", "UNKNOWN")
        }
        return candidate.copy(edgeIp = ip, sni = sni, hostHeader = host, regionHint = region)
    }

    private fun normalizeHostname(value: String): String? {
        val host = value.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty() || host.length > 253 || host.any { it.isWhitespace() || it == '/' || it == '@' }) return null
        if (normalizeIp(value) != null) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any {
                it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') ||
                    it.any { ch -> !(ch.isLetterOrDigit() || ch == '-') }
            }) return null
        return host
    }

    private fun normalizeIp(value: String): String? {
        val input = value.trim()
        if (input.isEmpty() || input.length > 45 || '%' in input ||
            input.any { !(it.isDigit() || it in "abcdefABCDEF:.") }) return null
        if (':' !in input) {
            val parts = input.split('.')
            if (parts.size != 4) return null
            val octets = parts.map { it.toIntOrNull() ?: return null }
            if (octets.any { it !in 0..255 } || parts.any { it.length > 1 && it.startsWith('0') }) return null
            return octets.joinToString(".")
        }
        return try {
            val address = InetAddress.getByName(input)
            if (address !is Inet6Address) null else address.hostAddress.substringBefore('%')
        } catch (_: Exception) {
            null
        }
    }
}
