package com.aminopy.roohvpn.core

import org.json.JSONObject

/**
 * Safe, non-networking preflight for SNI/CDN options.
 *
 * This reports what the imported profile's structure can support. It does not
 * probe the CDN, prove the origin accepts the Host/SNI pair, or claim that a
 * tunnel will connect.
 */
internal object XrayTransportCapabilityAnalyzer {
    data class Report(
        val protocol: String,
        val network: String,
        val security: String,
        val supportsSniOverride: Boolean,
        val supportsCdnEdgeOverride: Boolean,
        val requiredForCdn: List<String>,
        val limitationCode: String? = null
    )

    data class Result(
        val report: Report?,
        val errorCode: String? = null
    ) {
        val isSuccess: Boolean get() = report != null && errorCode == null
    }

    private val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")
    private val cdnNetworks = setOf("ws", "grpc", "httpupgrade", "xhttp", "splithttp")

    fun analyze(configuration: ByteArray): Result {
        if (XrayProfilePreflight.validate(configuration) != null) {
            return Result(null, "INVALID_XRAY_PROFILE")
        }
        return try {
            val root = JSONObject(configuration.toString(Charsets.UTF_8))
            val outbounds = root.optJSONArray("outbounds")
                ?: return Result(null, "MISSING_OUTBOUNDS")
            val proxies = (0 until outbounds.length()).mapNotNull { index ->
                outbounds.optJSONObject(index)?.takeIf {
                    it.optString("protocol").lowercase() in proxyProtocols
                }
            }
            if (proxies.size != 1) return Result(null, "AMBIGUOUS_PROXY_OUTBOUND")

            val outbound = proxies.single()
            val protocol = outbound.optString("protocol").lowercase()
            val settings = outbound.optJSONObject("settings")
                ?: return Result(null, "MISSING_SERVER_SETTINGS")
            val stream = outbound.optJSONObject("streamSettings")
                ?: return Result(null, "MISSING_STREAM_SETTINGS")
            val network = stream.optString("network", "tcp").lowercase()
            val security = stream.optString("security", "none").lowercase()

            val sniSupported = when (security) {
                "tls" -> stream.optJSONObject("tlsSettings") != null
                "reality" -> stream.optJSONObject("realitySettings") != null
                else -> false
            }
            val settingsKey = if (protocol == "trojan" || protocol == "shadowsocks") "servers" else "vnext"
            val serverList = settings.optJSONArray(settingsKey)
            val singleServer = serverList?.length() == 1 && serverList.optJSONObject(0) != null
            val transportSettingsPresent = when (network) {
                "ws" -> stream.optJSONObject("wsSettings") != null
                "grpc" -> stream.optJSONObject("grpcSettings") != null
                "httpupgrade" -> stream.optJSONObject("httpupgradeSettings") != null
                "xhttp" -> stream.optJSONObject("xhttpSettings") != null
                "splithttp" -> stream.optJSONObject("xhttpSettings") != null ||
                    stream.optJSONObject("splithttpSettings") != null
                else -> false
            }
            val cdnSupported = security == "tls" && network in cdnNetworks &&
                transportSettingsPresent && singleServer

            val limitation = when {
                security == "reality" && network in cdnNetworks ->
                    "REALITY_CDN_EDGE_OVERRIDE_UNSUPPORTED"
                security != "tls" && network in cdnNetworks ->
                    "CDN_REQUIRES_TLS"
                network !in cdnNetworks ->
                    "TRANSPORT_NOT_CDN_COMPATIBLE"
                !transportSettingsPresent ->
                    "TRANSPORT_SETTINGS_MISSING"
                !singleServer ->
                    "CDN_REQUIRES_ONE_SERVER_ENDPOINT"
                else -> null
            }
            val required = when (network) {
                "ws" -> listOf("TLS SNI", "WebSocket Host", "path", "CDN edge IP")
                "grpc" -> listOf("TLS SNI", "gRPC authority", "serviceName", "CDN edge IP")
                "httpupgrade" -> listOf("TLS SNI", "HTTPUpgrade Host", "path", "CDN edge IP")
                "xhttp", "splithttp" -> listOf("TLS SNI", "XHTTP Host", "path/mode as required", "CDN edge IP")
                else -> emptyList()
            }
            Result(
                Report(
                    protocol = protocol,
                    network = network,
                    security = security,
                    supportsSniOverride = sniSupported,
                    supportsCdnEdgeOverride = cdnSupported,
                    requiredForCdn = required,
                    limitationCode = if (cdnSupported) null else limitation
                )
            )
        } catch (_: Exception) {
            Result(null, "INVALID_XRAY_PROFILE")
        }
    }
}
