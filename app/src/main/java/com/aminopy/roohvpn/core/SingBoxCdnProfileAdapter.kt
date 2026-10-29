package com.aminopy.roohvpn.core

import org.json.JSONObject
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Applies explicit SNI/CDN overrides to one unambiguous sing-box proxy outbound.
 * It never disables TLS certificate validation and never invents transport settings.
 */
internal object SingBoxCdnProfileAdapter {
    data class Overrides(
        val sni: String? = null,
        val cdnEdgeIp: String? = null,
        val hostHeader: String? = null
    )

    data class Result(val configuration: ByteArray?, val errorCode: String? = null) {
        val isSuccess: Boolean get() = configuration != null && errorCode == null
    }

    private val proxyTypes = setOf("vless", "vmess", "trojan", "shadowsocks")

    fun apply(configuration: ByteArray, overrides: Overrides): Result {
        if (overrides.sni == null && overrides.cdnEdgeIp == null && overrides.hostHeader == null) {
            return Result(null, "NO_OVERRIDES")
        }
        if (SingBoxProfilePreflight.validate(configuration) != null) return Result(null, "INVALID_PROFILE")
        val sni = overrides.sni?.let(::normalizeHostname)
        if (overrides.sni != null && sni == null) return Result(null, "INVALID_SNI")
        val host = overrides.hostHeader?.let(::normalizeHostname)
        if (overrides.hostHeader != null && host == null) return Result(null, "INVALID_HOST")
        val edgeIp = overrides.cdnEdgeIp?.let(::normalizeIpLiteral)
        if (overrides.cdnEdgeIp != null && edgeIp == null) return Result(null, "INVALID_CDN_IP")

        return try {
            val source = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(configuration)).toString()
            val root = JSONObject(source)
            val outbounds = root.optJSONArray("outbounds") ?: return Result(null, "MISSING_OUTBOUNDS")
            val indexes = (0 until outbounds.length()).filter { i ->
                outbounds.optJSONObject(i)?.optString("type")?.lowercase() in proxyTypes
            }
            if (indexes.size != 1) return Result(null, "AMBIGUOUS_PROXY_OUTBOUND")
            val outbound = outbounds.getJSONObject(indexes.single())
            val tls = outbound.optJSONObject("tls") ?: return Result(null, "TLS_REQUIRED")
            if (!tls.optBoolean("enabled", false)) return Result(null, "TLS_REQUIRED")
            val transport = outbound.optJSONObject("transport")
            val transportType = transport?.optString("type", "tcp")?.lowercase() ?: "tcp"

            if (edgeIp != null) {
                if (transportType !in setOf("ws", "grpc", "http", "httpupgrade")) {
                    return Result(null, "CDN_TRANSPORT_UNSUPPORTED")
                }
                if (sni == null && host == null) return Result(null, "CDN_REQUIRES_SNI_OR_HOST")
                outbound.put("server", edgeIp)
            }
            val effectiveSni = sni ?: if (edgeIp != null) host else null
            if (effectiveSni != null) tls.put("server_name", effectiveSni)

            if (host != null) {
                if (transportType != "ws") return Result(null, "HOST_HEADER_TRANSPORT_UNSUPPORTED")
                val updatedTransport = transport ?: return Result(null, "MISSING_TRANSPORT")
                val headers = updatedTransport.optJSONObject("headers") ?: JSONObject()
                headers.put("Host", host)
                updatedTransport.put("headers", headers)
            } else if (edgeIp != null && transportType == "ws" && sni != null) {
                val updatedTransport = transport ?: return Result(null, "MISSING_TRANSPORT")
                val headers = updatedTransport.optJSONObject("headers") ?: JSONObject()
                if (!headers.has("Host")) headers.put("Host", sni)
                updatedTransport.put("headers", headers)
            }

            Result(root.toString().toByteArray(StandardCharsets.UTF_8))
        } catch (_: Exception) {
            Result(null, "INVALID_PROFILE")
        }
    }

    private fun normalizeHostname(value: String): String? {
        val input = value.trim().trimEnd('.')
        if (input.isEmpty() || input.length > 253 || input.any { it.isWhitespace() || it == '/' || it == '@' }) return null
        if (normalizeIpLiteral(input) != null) return null
        val ascii = try { IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).lowercase() } catch (_: Exception) { return null }
        val labels = ascii.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return null
        return ascii
    }

    private fun normalizeIpLiteral(value: String): String? {
        val input = value.trim()
        if (input.isEmpty() || input.length > 45 || '%' in input ||
            input.any { !(it.isDigit() || it in "abcdefABCDEF:.") }) return null
        if (':' !in input) {
            val parts = input.split('.')
            if (parts.size != 4) return null
            val octets = parts.map { it.toIntOrNull() ?: return null }
            if (octets.any { it !in 0..255 } || parts.any { it.length > 1 && it.startsWith('0') }) return null
            return octets.joinToString(".")
        }
        return try {
            val address = InetAddress.getByName(input)
            if (address !is Inet6Address) null else address.hostAddress?.substringBefore('%')
        } catch (_: Exception) { null }
    }
}
