package com.aminopy.roohvpn

import android.app.Application
import com.aminopy.roohvpn.core.ConnectionCoordinator
import com.aminopy.roohvpn.core.DefaultEngineRegistry
import com.aminopy.roohvpn.core.EngineFailure
import com.aminopy.roohvpn.core.XrayFrontingAutoConnector

/**
 * Lazily constructs the shared VPN core. No engine or tunnel is started during
 * app startup; a user-consented connection action must invoke the coordinator.
 */
class RoohVpnApplication : Application() {
    val connectionCoordinator: ConnectionCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ConnectionCoordinator(DefaultEngineRegistry.create(applicationContext))
    }

    /**
     * Starts the signed no-manual-config fronting flow after Android VPN consent
     * has been granted. Call from a worker thread. The directory URL and pinned
     * Ed25519 public key must be supplied by the app's trusted release config;
     * this method intentionally has no untrusted/default provider.
     */
    fun connectXrayFrontingAutomatically(
        trustedDirectoryHttpsUrl: String,
        pinnedEd25519PublicKeyX509Base64: String
    ): EngineFailure? = XrayFrontingAutoConnector.connect(
        directoryUrl = trustedDirectoryHttpsUrl,
        pinnedEd25519PublicKeyX509Base64 = pinnedEd25519PublicKeyX509Base64,
        coordinator = connectionCoordinator
    )
}
