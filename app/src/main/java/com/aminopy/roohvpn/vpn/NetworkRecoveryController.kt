package com.aminopy.roohvpn.vpn

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies a conservative recovery policy to default-network changes.
 *
 * This is deliberately an adapter boundary: a concrete tunnel owner must provide
 * a real end-to-end health check and a reconnect operation. A validated Android
 * network or a live native process is not sufficient evidence of tunnel health.
 * Callbacks run on a dedicated worker, never on ConnectivityManager's callback thread.
 */
class NetworkRecoveryController(
    private val tunnel: TunnelRecoveryPort,
    private val statusListener: (Status) -> Unit,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "roohvpn-network-recovery").apply { isDaemon = true }
    }
) : Closeable {
    interface TunnelRecoveryPort {
        /** True only while this app owns a tunnel that may need recovery. */
        fun isTunnelActive(): Boolean

        /** Must perform a real end-to-end check, not only inspect process/TUN state. */
        fun validateTunnelTraffic(): Boolean

        /** Re-establishes the same authorized profile and returns only after startup is confirmed. */
        fun reconnect(): Boolean
    }

    enum class Status {
        IDLE,
        WAITING_FOR_NETWORK,
        CHECKING_TUNNEL,
        RECOVERED,
        RECOVERY_FAILED,
        CLOSED
    }

    private val closed = AtomicBoolean(false)
    private val recoveryQueued = AtomicBoolean(false)
    @Volatile private var lastStatus = Status.IDLE

    fun onNetworkStateChanged(state: NetworkLifecycleMonitor.State) {
        if (closed.get() || !tunnel.isTunnelActive()) return

        if (state == NetworkLifecycleMonitor.State.NO_DEFAULT_NETWORK) {
            publish(Status.WAITING_FOR_NETWORK)
            return
        }

        // One recovery task at a time; multiple Wi-Fi/mobile callbacks are coalesced.
        if (!recoveryQueued.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    if (closed.get() || !tunnel.isTunnelActive()) return@execute
                    publish(Status.CHECKING_TUNNEL)
                    if (tunnel.validateTunnelTraffic()) {
                        publish(Status.RECOVERED)
                        return@execute
                    }

                    if (!tunnel.reconnect()) {
                        publish(Status.RECOVERY_FAILED)
                        return@execute
                    }

                    publish(
                        if (tunnel.validateTunnelTraffic()) Status.RECOVERED
                        else Status.RECOVERY_FAILED
                    )
                } catch (_: Exception) {
                    publish(Status.RECOVERY_FAILED)
                } finally {
                    recoveryQueued.set(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            recoveryQueued.set(false)
            if (!closed.get()) publish(Status.RECOVERY_FAILED)
        }
    }

    fun status(): Status = lastStatus

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.shutdownNow()
        publish(Status.CLOSED)
    }

    private fun publish(status: Status) {
        lastStatus = status
        runCatching { statusListener(status) }
    }
}
