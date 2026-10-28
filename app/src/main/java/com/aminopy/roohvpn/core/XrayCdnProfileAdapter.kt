package com.aminopy.roohvpn.core

import org.json.JSONObject
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Applies explicit SNI/CDN endpoint overrides to a standard Xray JSON profile.
 *
 * This only prepares configuration; it does not make an incompatible CDN
 * origin work. The remote endpoint must be configured to accept the selected
 * TLS SNI and, for WS/gRPC, the HTTP Host/authority.
 */
internal object XrayCdnProfileAdapter {
    data class Overrides(
        val sni: String? = null,
        val cdnEdgeIp: String? = null,
        val hostHeader: String? = null
    )

    data class Result(
        val configuration: String?,
        val errorCode: String? = null
    ) {
        val isSuccess: Boolean get() = configuration != null && errorCode == null
    }

    private val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")

    fun apply(configuration: ByteArray, overrides: Overrides): Result {
        if (overrides.sni == null && overrides.cdnEdgeIp == null && overrides.hostHeader == null) {
            return Result(null, "NO_OVERRIDES")
        }
        val sni = overrides.sni?.let(::normalizeHostname)
        if (overrides.sni != null && sni == null) return Result(null, "INVALID_SNI")
        val host = overrides.hostHeader?.let(::normalizeHostname)
        if (overrides.hostHeader != null && host == null) return Result(null, "INVALID_HOST_HEADER")
        val edgeIp = overrides.cdnEdgeIp?.let(::normalizeIpLiteral)
        if (overrides.cdnEdgeIp != null && edgeIp == null) return Result(null, "INVALID_CDN_IP")

        if (XrayProfilePreflight.validate(configuration) != null) {
            return Result(null, "INVALID_XRAY_PROFILE")
        }

        return try {
            val root = JSONObject(configuration.toString(Charsets.UTF_8))
            val outbounds = root.optJSONArray("outbounds") ?: return Result(null, "MISSING_OUTBOUNDS")
            val proxyIndexes = (0 until outbounds.length()).filter { index ->
                (outbounds.optJSONObject(index)?.optString("protocol")?.lowercase() ?: "") in proxyProtocols
            }
            if (proxyIndexes.size != 1) return Result(null, "AMBIGUOUS_PROXY_OUTBOUND")
            val outbound = outbounds.getJSONObject(proxyIndexes.single())
            val settings = outbound.optJSONObject("settings") ?: return Result(null, "MISSING_SERVER_SETTINGS")
            val stream = outbound.optJSONObject("streamSettings")
                ?: return Result(null, "MISSING_STREAM_SETTINGS")
            val security = stream.optString("security", "none").lowercase()
            val network = stream.optString("network", "tcp").lowercase()

            if (edgeIp != null) {
                if (security != "tls") return Result(null, "CDN_REQUIRES_TLS")
                if (network != "ws" && network != "grpc" && network != "xhttp") {
                    return Result(null, "CDN_TRANSPORT_UNSUPPORTED")
                }
                if (sni == null && host == null) return Result(null, "CDN_REQUIRES_SNI_OR_HOST")
                val effectiveSni = sni ?: host
                val tls = stream.optJSONObject("tlsSettings")
                    ?: return Result(null, "MISSING_TLS_SETTINGS")
                if (effectiveSni != null) tls.put("serverName", effectiveSni)
                val effectiveHost = host ?: sni
                if (network == "ws") {
                    val ws = stream.optJSONObject("wsSettings")
                        ?: return Result(null, "MISSING_WS_SETTINGS")
                    val headers = ws.optJSONObject("headers") ?: JSONObject().also { ws.put("headers", it) }
                    if (effectiveHost != null) headers.put("Host", effectiveHost)
                } else if (network == "grpc") {
                    val grpc = stream.optJSONObject("grpcSettings")
                        ?: return Result(null, "MISSING_GRPC_SETTINGS")
                    if (effectiveHost != null) grpc.put("authority", effectiveHost)
                } else {
                    // XHTTP's Host is configured in xhttpSettings.host, not
                    // headers or grpcSettings.authority. Preserve path/mode/extra.
                    val xhttp = stream.optJSONObject("xhttpSettings")
                        ?: return Result(null, "MISSING_XHTTP_SETTINGS")
                    if (effectiveHost != null) xhttp.put("host", effectiveHost)
                }
                if (!replaceSingleServerAddress(settings, outbound.optString("protocol"), edgeIp)) {
                    return Result(null, "UNSUPPORTED_SERVER_LAYOUT")
                }
            } else if (sni != null) {
                when (security) {
                    "tls" -> {
                        val tls = stream.optJSONObject("tlsSettings")
                            ?: return Result(null, "MISSING_TLS_SETTINGS")
                        tls.put("serverName", sni)
                    }
                    "reality" -> {
                        val reality = stream.optJSONObject("realitySettings")
                            ?: return Result(null, "MISSING_REALITY_SETTINGS")
                        reality.put("serverName", sni)
                    }
                    else -> return Result(null, "SNI_REQUIRES_TLS_OR_REALITY")
                }
            }

            if (host != null && edgeIp == null) {
                when (network) {
                    "ws" -> {
                        val ws = stream.optJSONObject("wsSettings")
                            ?: return Result(null, "MISSING_WS_SETTINGS")
                        val headers = ws.optJSONObject("headers") ?: JSONObject().also { ws.put("headers", it) }
                        headers.put("Host", host)
                    }
                    "grpc" -> {
                        val grpc = stream.optJSONObject("grpcSettings")
                            ?: return Result(null, "MISSING_GRPC_SETTINGS")
                        grpc.put("authority", host)
                    }
                    "xhttp" -> {
                        val xhttp = stream.optJSONObject("xhttpSettings")
                            ?: return Result(null, "MISSING_XHTTP_SETTINGS")
                        xhttp.put("host", host)
                    }
                    else -> return Result(null, "HOST_HEADER_TRANSPORT_UNSUPPORTED")
                }
            }
            Result(root.toString())
        } catch (_: Exception) {
            Result(null, "INVALID_XRAY_PROFILE")
        }
    }

    private fun replaceSingleServerAddress(settings: JSONObject, protocol: String, ip: String): Boolean {
        val key = if (protocol.equals("trojan", true) || protocol.equals("shadowsocks", true)) {
            "servers"
        } else {
            "vnext"
        }
        val servers = settings.optJSONArray(key) ?: return false
        if (servers.length() != 1) return false
        val server = servers.optJSONObject(0) ?: return false
        server.put("address", ip)
        return true
    }

    private fun normalizeHostname(value: String): String? {
        val input = value.trim().trimEnd('.')
        if (input.isEmpty() || input.length > 253 || input.any { it.isWhitespace() || it == '/' || it == '@' }) {
            return null
        }
        // A hostname override is not an IP-literal field.
        if (normalizeIpLiteral(input) != null) return null
        val ascii = try { IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).lowercase() } catch (_: Exception) {
            return null
        }
        if (ascii.length > 253) return null
        val labels = ascii.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) {
            return null
        }
        return ascii
    }

    private fun normalizeIpLiteral(value: String): String? {
        val input = value.trim()
        if (input.isEmpty() || input.length > 45 || '%' in input || input.any { !(it.isDigit() || it in "abcdefABCDEF:.") }) {
            return null
        }
        // Dotted IPv4 is checked without DNS; IPv6 is parsed only when a colon exists.
        if (':' !in input) {
            val parts = input.split('.')
            if (parts.size != 4) return null
            val octets = parts.map { it.toIntOrNull() ?: return null }
            if (octets.any { it !in 0..255 } || parts.any { it.length > 1 && it.startsWith('0') }) return null
            return octets.joinToString(".")
        }
        return try {
            val address = InetAddress.getByName(input)
            if (address !is Inet6Address) null else address.hostAddress.substringBefore('%')
        } catch (_: Exception) {
            null
        }
    }
}
