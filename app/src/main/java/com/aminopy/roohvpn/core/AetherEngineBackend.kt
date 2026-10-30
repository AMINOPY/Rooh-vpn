package com.aminopy.roohvpn.core

import android.content.Context
import com.aminopy.roohvpn.vpn.AetherVpnService

/** Aether's built-in WARP engine; no user-supplied profile is required. */
class AetherEngineBackend(context: Context) : EngineBackend {
    private val appContext = context.applicationContext

    override val id: EngineId = EngineId.AETHER
    override val capabilities = EngineCapabilities(
        tunnelMode = TunnelMode.LOCAL_PROXY_WITH_TUN_BRIDGE,
        supportsIpv4 = true,
        supportsIpv6 = true,
        supportsDnsRouting = true,
        supportsPerAppRouting = false,
        supportedConfigFormats = setOf("aether-default")
    )

    override fun validate(configuration: ByteArray): EngineFailure? =
        if (configuration.isEmpty() && AetherVpnService.isNativeRuntimePackaged(appContext)) null
        else EngineFailure(
            code = if (configuration.isNotEmpty()) "AETHER_PROFILE_NOT_SUPPORTED" else "AETHER_RUNTIME_NOT_PACKAGED",
            safeMessage = "Aether requires its packaged native runtime and uses its built-in connection profile.",
            retryable = false
        )

    override fun start(configuration: ByteArray): EngineFailure? {
        if (configuration.isNotEmpty()) {
            configuration.fill(0)
            return EngineFailure("AETHER_PROFILE_NOT_SUPPORTED", "Aether uses its built-in connection profile.", false)
        }
        return AetherVpnService.start(appContext)
    }

    override fun status(): EngineStatus = EngineStatus(
        engineId = id,
        state = if (AetherVpnService.isRunning()) EngineState.RUNNING else EngineState.STOPPED
    )

    override fun stop(): EngineFailure? = AetherVpnService.stop(appContext)
}
