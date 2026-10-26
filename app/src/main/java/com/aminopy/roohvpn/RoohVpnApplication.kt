package com.aminopy.roohvpn

import android.app.Application
import com.aminopy.roohvpn.core.ConnectionCoordinator
import com.aminopy.roohvpn.core.DefaultEngineRegistry

/**
 * Lazily constructs the shared VPN core. No engine or tunnel is started during
 * app startup; a user-consented connection action must invoke the coordinator.
 */
class RoohVpnApplication : Application() {
    val connectionCoordinator: ConnectionCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ConnectionCoordinator(DefaultEngineRegistry.create(applicationContext))
    }
}
