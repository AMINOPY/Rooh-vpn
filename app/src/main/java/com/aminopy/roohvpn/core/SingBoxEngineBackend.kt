package com.aminopy.roohvpn.core

import android.content.Context
import com.aminopy.roohvpn.vpn.SingBoxVpnService

/** sing-box backend. Runtime must be packaged by the singbox engine flavor. */
class SingBoxEngineBackend(context: Context) : EngineBackend {
    private val appContext = context.applicationContext

    override val id: EngineId = EngineId.SING_BOX
    override val capabilities = EngineCapabilities(
        tunnelMode = TunnelMode.NATIVE_TUN,
        supportsIpv4 = true,
        supportsIpv6 = true,
        supportsDnsRouting = true,
        supportsPerAppRouting = true,
        supportedConfigFormats = setOf("sing-box-json")
    )

    override fun validate(configuration: ByteArray): EngineFailure? =
        SingBoxNativeConfigValidator.validate(appContext, configuration)

    override fun start(configuration: ByteArray): EngineFailure? {
        validate(configuration)?.let {
            configuration.fill(0)
            return it
        }
        return SingBoxVpnService.start(appContext, configuration)
    }

    override fun status(): EngineStatus = EngineStatus(
        engineId = id,
        state = if (SingBoxVpnService.isRunning()) EngineState.RUNNING else EngineState.STOPPED
    )

    override fun stop(): EngineFailure? = SingBoxVpnService.stop(appContext)
}
