package com.aminopy.roohvpn.vpn

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies a conservative recovery policy to default-network changes.
 *
 * A concrete tunnel owner must supply a real end-to-end health check and reconnect
 * operation. Android network validation or a live native process is not evidence
 * that the VPN itself is healthy. All blocking checks run off the network callback thread.
 */
class NetworkRecoveryController(
    private val tunnel: TunnelRecoveryPort,
    private val statusListener: (Status) -> Unit,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "roohvpn-network-recovery").apply { isDaemon = true }
    }
) : Closeable {
    interface TunnelRecoveryPort {
        fun isTunnelActive(): Boolean
        /** Must perform a real end-to-end traffic check, not only inspect process/TUN state. */
        fun validateTunnelTraffic(): Boolean
        /** Re-establish the same authorized profile and return only after startup is confirmed. */
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
        if (closed.get()) return

        if (state != NetworkLifecycleMonitor.State.AVAILABLE_VALIDATED) {
            // A network callback must remain non-blocking. Do not call into the
            // engine here; it may cross a JNI boundary or wait on native state.
            publish(Status.WAITING_FOR_NETWORK)
            return
        }

        // Coalesce Wi-Fi/mobile callbacks into one active validation/recovery task.
        // All tunnel/backend calls, including isTunnelActive(), belong on the worker.
        if (!recoveryQueued.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    if (closed.get()) return@execute
                    if (!tunnel.isTunnelActive()) return@execute
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
        } catch (_: RejectedExecutionException) {
            recoveryQueued.set(false)
            publish(Status.RECOVERY_FAILED)
        }
    }

    fun status(): Status = lastStatus

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.shutdownNow()
        publish(Status.CLOSED)
    }

    private fun publish(status: Status) {
        if (closed.get() && status != Status.CLOSED) return
        lastStatus = status
        runCatching { statusListener(status) }
    }
}
