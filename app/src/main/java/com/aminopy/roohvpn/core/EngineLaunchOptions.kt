package com.aminopy.roohvpn.core

/**
 * Optional launch-time transport overrides. These are not credentials and are
 * applied only by engines that explicitly support them; other engines ignore
 * the empty default and reject unsupported non-empty options.
 */
data class EngineLaunchOptions(
    val xraySni: String? = null,
    val xrayCdnEdgeIp: String? = null,
    val xrayHostHeader: String? = null
) {
    fun hasXrayTransportOverrides(): Boolean =
        xraySni != null || xrayCdnEdgeIp != null || xrayHostHeader != null
}
