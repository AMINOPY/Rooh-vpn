package com.aminopy.roohvpn.server

import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures TCP connection establishment only. Never use this as a substitute
 * for an authenticated engine handshake or a real traffic-through-tunnel test.
 * Run on a background thread; connect() is blocking.
 */
class TcpEndpointProbe(private val timeoutMs: Int = 1800) : EndpointProbe {
    init {
        require(timeoutMs in 100..10_000)
    }

    override fun measureLatency(candidate: ServerCandidate): Long? {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(candidate.host, candidate.port), timeoutMs)
            }
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        } catch (_: Exception) {
            null
        }
    }
}
