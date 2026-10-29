package com.aminopy.roohvpn.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Bounded structural preflight for sing-box JSON.
 * This is not a replacement for the pinned libbox native config checker.
 */
object SingBoxProfilePreflight {
    private const val MAX_CONFIG_BYTES = 1024 * 1024

    fun validate(configuration: ByteArray): EngineFailure? {
        if (configuration.isEmpty() || configuration.size > MAX_CONFIG_BYTES) return invalid()
        val source = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(configuration))
                .toString()
                .trim()
        } catch (_: Exception) {
            return invalid()
        }
        if (source.isEmpty()) return invalid()

        return try {
            val root = JSONObject(source)
            val outbounds = root.optJSONArray("outbounds") ?: return invalid()
            if (outbounds.length() == 0) return invalid()
            var hasUsableOutbound = false
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: return invalid()
                val type = outbound.optString("type").trim()
                if (type.isEmpty()) return invalid()
                if (type in setOf("selector", "urltest", "direct", "block", "dns", "wireguard", "tailscale", "tor")) {
                    hasUsableOutbound = true
                } else if (outbound.optString("server").isNotBlank() && outbound.optInt("server_port", 0) in 1..65535) {
                    hasUsableOutbound = true
                }
            }
            if (hasUsableOutbound) null else invalid()
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun invalid() = EngineFailure(
        code = "SING_BOX_CONFIG_INVALID",
        safeMessage = "The sing-box profile is empty, malformed, or structurally incomplete.",
        retryable = false
    )
}
