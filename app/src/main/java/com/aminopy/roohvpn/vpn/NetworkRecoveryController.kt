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
    @Volatile private var latestNetworkState = NetworkLifecycleMonitor.State.NO_DEFAULT_NETWORK

    fun onNetworkStateChanged(state: NetworkLifecycleMonitor.State) {
        if (closed.get()) return
        latestNetworkState = state

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
                    if (!hasValidatedNetwork()) {
                        publish(Status.WAITING_FOR_NETWORK)
                        return@execute
                    }
                    if (!tunnel.isTunnelActive()) return@execute

                    publish(Status.CHECKING_TUNNEL)
                    if (!hasValidatedNetwork()) {
                        publish(Status.WAITING_FOR_NETWORK)
                        return@execute
                    }

                    if (tunnel.validateTunnelTraffic()) {
                        publish(if (hasValidatedNetwork()) Status.RECOVERED else Status.WAITING_FOR_NETWORK)
                        return@execute
                    }

                    // Do not restart an engine against a network that disappeared
                    // while the health probe was in flight.
                    if (!hasValidatedNetwork()) {
                        publish(Status.WAITING_FOR_NETWORK)
                        return@execute
                    }
                    if (!tunnel.reconnect()) {
                        publish(if (hasValidatedNetwork()) Status.RECOVERY_FAILED else Status.WAITING_FOR_NETWORK)
                        return@execute
                    }

                    publish(
                        if (hasValidatedNetwork() && tunnel.validateTunnelTraffic()) {
                            Status.RECOVERED
                        } else if (hasValidatedNetwork()) {
                            Status.RECOVERY_FAILED
                        } else {
                            Status.WAITING_FOR_NETWORK
                        }
                    )
                } catch (_: Exception) {
                    publish(if (hasValidatedNetwork()) Status.RECOVERY_FAILED else Status.WAITING_FOR_NETWORK)
                } catch (_: LinkageError) {
                    // Native engine/JNI failures are not Exceptions. Keep the recovery
                    // worker alive and never leave the UI stuck in CHECKING_TUNNEL.
                    publish(if (hasValidatedNetwork()) Status.RECOVERY_FAILED else Status.WAITING_FOR_NETWORK)
                } finally {
                    recoveryQueued.set(false)
                    // If a validated network arrived while this task was finishing,
                    // queue one fresh pass instead of losing the transition.
                    if (!closed.get() &&
                        hasValidatedNetwork() &&
                        lastStatus == Status.WAITING_FOR_NETWORK
                    ) {
                        onNetworkStateChanged(NetworkLifecycleMonitor.State.AVAILABLE_VALIDATED)
                    }
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

    private fun hasValidatedNetwork(): Boolean =
        latestNetworkState == NetworkLifecycleMonitor.State.AVAILABLE_VALIDATED

    private fun publish(status: Status) {
        if (closed.get() && status != Status.CLOSED) return
        lastStatus = status
        runCatching { statusListener(status) }
    }
}
