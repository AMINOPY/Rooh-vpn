package com.aminopy.roohvpn.core

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Bounded SOCKS5 readiness probe for a local no-auth listener.
 *
 * The probe never connects to a remote host and never sends profile data.
 * Authentication-requiring SOCKS listeners are intentionally rejected until
 * their credential lifecycle can be handled without embedding secrets.
 */
internal object LoopbackSocksProbe {
    private const val CONNECT_TIMEOUT_MS = 900
    private const val READ_TIMEOUT_MS = 900

    fun isReady(port: Int): Boolean {
        if (port !in 1..65535) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(byteArrayOf(0x05, 0x01, 0x00))
                    flush()
                }
                val input = socket.getInputStream()
                val version = input.read()
                val method = input.read()
                version == 0x05 && method == 0x00
            }
        } catch (_: Exception) {
            false
        }
    }
}
