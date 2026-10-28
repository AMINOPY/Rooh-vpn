package com.aminopy.roohvpn.core

import android.content.Context
import com.aminopy.roohvpn.vpn.XrayVpnService

/** Xray backend using the pinned AndroidLibXrayLite binding and Android TUN FD. */
class XrayEngineBackend(context: Context) : EngineBackend {
    private val appContext = context.applicationContext

    override val id: EngineId = EngineId.XRAY
    override val capabilities = EngineCapabilities(
        tunnelMode = TunnelMode.NATIVE_TUN,
        supportsIpv4 = true,
        supportsIpv6 = true,
        supportsDnsRouting = true,
        supportsPerAppRouting = false,
        supportedConfigFormats = setOf("xray-json")
    )

    override fun validate(configuration: ByteArray): EngineFailure? {
        if (XrayProfilePreflight.validate(configuration) != null) {
            return invalidConfiguration()
        }
        if (XrayTunProfileAdapter.prepare(configuration) == null) {
            return EngineFailure(
                code = "XRAY_TUN_ADAPTATION_FAILED",
                safeMessage = "This Xray profile cannot be adapted safely to the Android VPN tunnel.",
                retryable = false
            )
        }
        return null
    }

    override fun start(configuration: ByteArray): EngineFailure? {
        validate(configuration)?.let {
            configuration.fill(0)
            return it
        }
        return XrayVpnService.start(appContext, configuration)
    }

    override fun status(): EngineStatus = EngineStatus(
        engineId = id,
        state = if (XrayVpnService.isRunning()) EngineState.RUNNING else EngineState.STOPPED
    )

    override fun stop(): EngineFailure? = XrayVpnService.stop(appContext)

    private fun invalidConfiguration() = EngineFailure(
        code = "XRAY_INVALID_CONFIG",
        safeMessage = "The Xray profile is invalid or unsupported.",
        retryable = false
    )
}
