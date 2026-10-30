package com.aminopy.roohvpn.core

import android.content.Context
import android.net.VpnService
import ca.psiphon.PsiphonTunnel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs Psiphon as an upstream SOCKS provider inside the already-authorized VPN
 * service. It never establishes a second Android TUN; its sockets are protected
 * through the one VpnService that owns the selected Xray/sing-box TUN.
 */
internal class PsiphonTunnelController(
    private val service: VpnService,
    private val profile: PsiphonProfile,
    private val onUnexpectedStop: (String?) -> Unit
) {
    private val started = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    @Volatile private var socksPort: Int = -1
    @Volatile private var failure: String? = null
    @Volatile private var tunnel: PsiphonTunnel? = null

    private val host = object : PsiphonTunnel.HostService {
        override fun getContext(): Context = service
        override fun getPsiphonConfig(): String =
            profile.toConfig(service.noBackupFilesDir.resolve("psiphon").apply { mkdirs() }.absolutePath)

        override fun bindToDevice(fileDescriptor: Long) {
            if (fileDescriptor !in 0..Int.MAX_VALUE.toLong() || !service.protect(fileDescriptor.toInt())) {
                throw IllegalStateException("Psiphon socket protection failed")
            }
        }

        override fun onDiagnosticMessage(message: String) = Unit
        override fun onListeningSocksProxyPort(port: Int) {
            if (port in 1..65535) {
                socksPort = port
                if (tunnel != null) ready.countDown()
            } else fail("Psiphon returned an invalid local SOCKS port")
        }
        override fun onSocksProxyPortInUse(port: Int) = fail("Psiphon local SOCKS port is already in use")
        override fun onConnecting() = Unit
        override fun onConnected() {
            if (socksPort in 1..65535) ready.countDown()
        }
        override fun onConnectedServerRegion(region: String) = Unit
        override fun onUpstreamProxyError(message: String) = fail("Psiphon upstream failed")
        override fun onInproxyMustUpgrade() = fail("This Psiphon mode requires a newer runtime")
        override fun onExiting() {
            if (started.compareAndSet(true, false)) onUnexpectedStop(failure)
        }
    }

    @Synchronized
    fun start(timeoutSeconds: Long = 45): Int {
        check(!started.get()) { "Psiphon is already running" }
        val instance = PsiphonTunnel.newPsiphonTunnel(host)
        tunnel = instance
        instance.setVpnMode(true)
        started.set(true)
        instance.startTunneling("")
        if (!ready.await(timeoutSeconds, TimeUnit.SECONDS)) {
            stop()
            throw IllegalStateException(failure ?: "Psiphon did not establish its SOCKS upstream in time")
        }
        failure?.let {
            stop()
            throw IllegalStateException(it)
        }
        return socksPort
    }

    @Synchronized
    fun stop() {
        started.set(false)
        val current = tunnel
        tunnel = null
        if (current != null) runCatching { current.stop() }
    }

    private fun fail(reason: String) {
        failure = reason
        ready.countDown()
        if (started.compareAndSet(true, false)) onUnexpectedStop(reason)
    }
}
