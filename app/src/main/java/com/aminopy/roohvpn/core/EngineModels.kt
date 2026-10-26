package com.aminopy.roohvpn.core

enum class EngineId {
    XRAY,
    AETHER,
    SING_BOX,
    WIREGUARD
}

enum class TunnelMode {
    NATIVE_TUN,
    LOCAL_PROXY_WITH_TUN_BRIDGE
}

enum class EngineState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED
}

data class EngineCapabilities(
    val tunnelMode: TunnelMode,
    val supportsIpv4: Boolean,
    val supportsIpv6: Boolean,
    val supportsDnsRouting: Boolean,
    val supportsPerAppRouting: Boolean,
    val supportedConfigFormats: Set<String>
)

data class EngineStatus(
    val engineId: EngineId,
    val state: EngineState,
    val message: String? = null
)

/**
 * Do not put secrets, raw configuration, subscription URLs, or private keys
 * into this type. Messages may be written to diagnostics.
 */
data class EngineFailure(
    val code: String,
    val safeMessage: String,
    val retryable: Boolean
)
