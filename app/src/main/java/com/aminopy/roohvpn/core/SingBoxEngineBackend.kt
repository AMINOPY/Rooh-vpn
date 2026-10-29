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

    override fun validate(
        configuration: ByteArray,
        options: EngineLaunchOptions
    ): EngineFailure? {
        if (options.hasXrayTransportOverrides()) {
            return EngineFailure("ENGINE_OPTION_UNSUPPORTED", "These transport overrides belong to Xray profiles.", false)
        }
        if (!options.hasSingBoxTransportOverrides()) return validate(configuration)
        val transformed = SingBoxCdnProfileAdapter.apply(
            configuration,
            SingBoxCdnProfileAdapter.Overrides(
                sni = options.singBoxSni,
                cdnEdgeIp = options.singBoxCdnEdgeIp,
                hostHeader = options.singBoxHostHeader
            )
        )
        if (!transformed.isSuccess) {
            transformed.configuration?.fill(0)
            return EngineFailure(
                code = "SING_BOX_TRANSPORT_OVERRIDE_REJECTED",
                safeMessage = "The SNI/CDN options are invalid or incompatible with this sing-box profile.",
                retryable = false
            )
        }
        val bytes = transformed.configuration ?: return EngineFailure(
            "SING_BOX_TRANSPORT_OVERRIDE_REJECTED",
            "The SNI/CDN options are invalid or incompatible with this sing-box profile.",
            false
        )
        return try { SingBoxNativeConfigValidator.validate(appContext, bytes) } finally { bytes.fill(0) }
    }

    override fun start(
        configuration: ByteArray,
        options: EngineLaunchOptions
    ): EngineFailure? {
        if (!options.hasSingBoxTransportOverrides()) return start(configuration)
        validate(configuration, options)?.let {
            configuration.fill(0)
            return it
        }
        val transformed = SingBoxCdnProfileAdapter.apply(
            configuration,
            SingBoxCdnProfileAdapter.Overrides(
                sni = options.singBoxSni,
                cdnEdgeIp = options.singBoxCdnEdgeIp,
                hostHeader = options.singBoxHostHeader
            )
        )
        if (!transformed.isSuccess) {
            configuration.fill(0)
            return EngineFailure(
                "SING_BOX_TRANSPORT_OVERRIDE_REJECTED",
                "The SNI/CDN options are invalid or incompatible with this sing-box profile.",
                false
            )
        }
        val adapted = transformed.configuration ?: run {
            configuration.fill(0)
            return EngineFailure("SING_BOX_TRANSPORT_OVERRIDE_REJECTED", "The profile could not be adapted.", false)
        }
        configuration.fill(0)
        return SingBoxVpnService.start(appContext, adapted)
    }

    override fun status(): EngineStatus = EngineStatus(
        engineId = id,
        state = if (SingBoxVpnService.isRunning()) EngineState.RUNNING else EngineState.STOPPED
    )

    override fun stop(): EngineFailure? = SingBoxVpnService.stop(appContext)
}
