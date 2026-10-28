package com.aminopy.roohvpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

/**
 * Adds an explicitly supplied CDN edge + TLS SNI + HTTP Host configuration to a
 * compatible Xray VLESS/VMess/Trojan WebSocket profile.
 *
 * This does not discover public CDN addresses and does not make an arbitrary CDN
 * a tunnel relay: the remote server/CDN route must already support this protocol.
 */
internal object XraySniCdnProfileAdapter {
    data class Options(
        val edgeAddress: String,
        val sni: String,
        val hostHeader: String,
        val websocketPath: String = "/",
        val port: Int = 443
    )

    private val supportedProtocols = setOf("vless", "vmess", "trojan")

    fun prepare(configuration: ByteArray, options: Options): String? {
        if (XrayProfilePreflight.validate(configuration) != null || !valid(options)) return null

        return try {
            val root = JSONObject(configuration.toString(Charsets.UTF_8))
            val outbounds = root.optJSONArray("outbounds") ?: return null
            val selected = (0 until outbounds.length())
                .mapNotNull { outbounds.optJSONObject(it) }
                .firstOrNull { outbound ->
                    outbound.optString("protocol").lowercase() in supportedProtocols
                } ?: return null

            val protocol = selected.optString("protocol").lowercase()
            val settings = selected.optJSONObject("settings") ?: return null
            val server = when (protocol) {
                "vless", "vmess" -> settings.optJSONArray("vnext")?.optJSONObject(0)
                "trojan" -> settings.optJSONArray("servers")?.optJSONObject(0)
                else -> null
            } ?: return null

            val stream = selected.optJSONObject("streamSettings") ?: JSONObject()
            val security = stream.optString("security")
            // REALITY is a different handshake, not TLS-to-CDN WebSocket.
            if (security.equals("reality", ignoreCase = true)) return null

            server.put("address", options.edgeAddress)
            server.put("port", options.port)
            stream.put("network", "ws")
            stream.put("security", "tls")

            val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
            tls.put("serverName", options.sni)
            tls.put("allowInsecure", false)
            tls.put("alpn", JSONArray().put("http/1.1"))
            stream.put("tlsSettings", tls)

            val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
            ws.put("path", options.websocketPath)
            val headers = ws.optJSONObject("headers") ?: JSONObject()
            headers.put("Host", options.hostHeader)
            ws.put("headers", headers)
            stream.put("wsSettings", ws)
            selected.put("streamSettings", stream)

            root.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun valid(options: Options): Boolean =
        validHost(options.edgeAddress) &&
            validHost(options.sni) &&
            validHost(options.hostHeader) &&
            options.port in 1..65535 &&
            options.websocketPath.length in 1..2048 &&
            options.websocketPath.startsWith("/") &&
            options.websocketPath.none { it == '\r' || it == '\n' || it == '\u0000' }

    private fun validHost(value: String): Boolean {
        if (value.isBlank() || value.length > 253 || value.any { it.isWhitespace() }) return false
        if (value.startsWith("[") || value.endsWith("]")) return false
        if (value.contains(':')) {
            // IPv6 literal only; do not resolve hostnames or perform network I/O here.
            return value.all { it in "0123456789abcdefABCDEF:." } &&
                value.count { it == ':' } >= 2
        }
        if (value.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))) {
            return value.split('.').all { it.toIntOrNull() in 0..255 }
        }
        return try {
            val ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES)
            ascii.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.first().isLetterOrDigit() && label.last().isLetterOrDigit()
            }
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
