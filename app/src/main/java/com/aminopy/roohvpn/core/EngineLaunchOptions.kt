package com.aminopy.roohvpn.core

/**
 * Optional launch-time transport overrides. They are applied only by engines
 * that explicitly support them. SNI is a hostname, CDN edge is an IP literal,
 * and Host/authority is a separate transport-layer hostname.
 */
data class EngineLaunchOptions(
    val xraySni: String? = null,
    val xrayCdnEdgeIp: String? = null,
    val xrayHostHeader: String? = null,
    val singBoxSni: String? = null,
    val singBoxCdnEdgeIp: String? = null,
    val singBoxHostHeader: String? = null
) {
    fun hasXrayTransportOverrides(): Boolean =
        xraySni != null || xrayCdnEdgeIp != null || xrayHostHeader != null

    fun hasSingBoxTransportOverrides(): Boolean =
        singBoxSni != null || singBoxCdnEdgeIp != null || singBoxHostHeader != null

    fun hasAnyTransportOverrides(): Boolean =
        hasXrayTransportOverrides() || hasSingBoxTransportOverrides()
}
