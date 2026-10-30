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
        if (XrayProfilePreflight.validate(configuration) != null) return invalidConfiguration()
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

    override fun validate(
        configuration: ByteArray,
        options: EngineLaunchOptions
    ): EngineFailure? {
        if (options.hasSingBoxTransportOverrides()) {
            return EngineFailure(
                "ENGINE_OPTION_UNSUPPORTED",
                "sing-box transport overrides cannot be applied to an Xray profile.",
                false
            )
        }
        if (!options.hasXrayTransportOverrides()) return validate(configuration)
        if (XrayProfilePreflight.validate(configuration) != null) return invalidConfiguration()

        val overrides = XrayCdnProfileAdapter.Overrides(
            sni = options.xraySni,
            cdnEdgeIp = options.xrayCdnEdgeIp,
            hostHeader = options.xrayHostHeader
        )
        // The adapter returns an immutable String. Check its result without
        // pretending that String contents can be zeroed like a ByteArray.
        if (!XrayCdnProfileAdapter.apply(configuration, overrides).isSuccess) {
            return EngineFailure(
                code = "XRAY_TRANSPORT_OVERRIDE_REJECTED",
                safeMessage = "The SNI/CDN options are invalid or incompatible with this Xray profile.",
                retryable = false
            )
        }
        if (XrayTunProfileAdapter.prepare(configuration, overrides) == null) {
            return EngineFailure(
                code = "XRAY_TUN_ADAPTATION_FAILED",
                safeMessage = "This Xray profile cannot be adapted safely to the Android VPN tunnel.",
                retryable = false
            )
        }
        return null
    }

    override fun start(
        configuration: ByteArray,
        options: EngineLaunchOptions
    ): EngineFailure? {
        if (options.hasSingBoxTransportOverrides()) {
            configuration.fill(0)
            return EngineFailure(
                "ENGINE_OPTION_UNSUPPORTED",
                "sing-box transport overrides cannot be applied to an Xray profile.",
                false
            )
        }
        if (!options.hasXrayTransportOverrides()) return start(configuration)
        validate(configuration, options)?.let {
            configuration.fill(0)
            return it
        }
        return XrayVpnService.start(appContext, configuration, options)
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
