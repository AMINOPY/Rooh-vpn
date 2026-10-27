package com.aminopy.roohvpn.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Bounded structural checks for legacy Xray JSON profiles.
 *
 * This is deliberately not a replacement for Xray's native config loader.
 */
object XrayProfilePreflight {
    private const val MAX_CONFIG_BYTES = 1024 * 1024

    fun validate(configuration: ByteArray): EngineFailure? {
        if (configuration.isEmpty() || configuration.size > MAX_CONFIG_BYTES) return invalid()

        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(configuration))
                .toString()
                .trim()
        } catch (_: Exception) {
            return invalid()
        }
        if (text.isEmpty() || !text.startsWith("{")) return invalid()

        return try {
            val root = JSONObject(text)
            val outbounds = root.optJSONArray("outbounds") ?: return invalid()
            if (outbounds.length() == 0) return invalid()

            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: return invalid()
                val protocolValue = outbound.opt("protocol")
                if (protocolValue !is String || protocolValue.isBlank()) return invalid()
                if (outbound.has("settings") && outbound.isNull("settings")) return invalid()
                if (outbound.has("settings") && outbound.optJSONObject("settings") == null) {
                    return invalid()
                }
            }

            val inbounds = root.opt("inbounds")
            if (inbounds != null) {
                if (inbounds !is org.json.JSONArray) return invalid()
                for (index in 0 until inbounds.length()) {
                    if (inbounds.optJSONObject(index) == null) return invalid()
                }
            }

            val routing = root.opt("routing")
            if (routing != null) {
                if (routing !is JSONObject) return invalid()
                if (routing.has("rules")) {
                    val rules = routing.optJSONArray("rules") ?: return invalid()
                    for (index in 0 until rules.length()) {
                        if (rules.optJSONObject(index) == null) return invalid()
                    }
                }
            }
            null
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun invalid() = EngineFailure(
        code = "XRAY_CONFIG_PREFLIGHT_FAILED",
        safeMessage = "The Xray profile is malformed or structurally incomplete.",
        retryable = false
    )
}
