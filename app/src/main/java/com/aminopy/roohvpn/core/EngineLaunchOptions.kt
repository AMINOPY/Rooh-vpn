package com.aminopy.roohvpn.core

/**
 * Optional launch-time transport and upstream settings. Values are applied only
 * by engines that explicitly support them.
 */
data class EngineLaunchOptions(
    val xraySni: String? = null,
    val xrayCdnEdgeIp: String? = null,
    val xrayHostHeader: String? = null,
    val singBoxSni: String? = null,
    val singBoxCdnEdgeIp: String? = null,
    val singBoxHostHeader: String? = null,
    val psiphonProfile: PsiphonProfile? = null
) {
    fun hasXrayTransportOverrides(): Boolean =
        xraySni != null || xrayCdnEdgeIp != null || xrayHostHeader != null

    fun hasSingBoxTransportOverrides(): Boolean =
        singBoxSni != null || singBoxCdnEdgeIp != null || singBoxHostHeader != null

    fun hasPsiphonUpstream(): Boolean = psiphonProfile != null

    fun hasAnyTransportOverrides(): Boolean =
        hasXrayTransportOverrides() || hasSingBoxTransportOverrides() || hasPsiphonUpstream()
}
