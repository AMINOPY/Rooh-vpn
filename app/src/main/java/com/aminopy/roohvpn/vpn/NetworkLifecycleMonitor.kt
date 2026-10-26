package com.aminopy.roohvpn.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observes the Android default network without claiming that a VPN tunnel is healthy.
 *
 * A validated default network only means Android validated the underlying network;
 * it does not prove that VPN traffic, DNS, or the remote engine works. Consumers must
 * perform an engine-specific health/traffic check before reporting the VPN connected.
 */
class NetworkLifecycleMonitor(
    private val connectivityManager: ConnectivityManager,
    private val listener: Listener
) : Closeable {
    fun interface Listener {
        fun onNetworkStateChanged(state: State)
    }

    enum class State {
        NO_DEFAULT_NETWORK,
        AVAILABLE_UNVALIDATED,
        AVAILABLE_VALIDATED
    }

    private val closed = AtomicBoolean(false)
    @Volatile private var currentState = State.NO_DEFAULT_NETWORK

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publish(stateFor(network))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            publish(if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) State.AVAILABLE_VALIDATED else State.AVAILABLE_UNVALIDATED)
        }

        override fun onLost(network: Network) {
            // Android may report loss of a previous network during Wi-Fi/mobile handoff.
            // Re-read the default network instead of assuming the device is offline.
            publish(readDefaultState())
        }
    }

    fun start() {
        check(!closed.get()) { "Monitor is already closed" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
        }
        publish(readDefaultState())
    }

    fun state(): State = currentState

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Safe when registration failed or Android already removed the callback.
        }
    }

    private fun stateFor(network: Network): State {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return State.AVAILABLE_UNVALIDATED
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) State.AVAILABLE_VALIDATED else State.AVAILABLE_UNVALIDATED
    }

    private fun readDefaultState(): State {
        val network = connectivityManager.activeNetwork ?: return State.NO_DEFAULT_NETWORK
        return stateFor(network)
    }

    private fun publish(next: State) {
        if (closed.get() || currentState == next) return
        currentState = next
        listener.onNetworkStateChanged(next)
    }
}
