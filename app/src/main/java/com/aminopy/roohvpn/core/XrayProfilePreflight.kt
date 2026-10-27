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
                if (!hasNonBlankString(outbound, "protocol")) return invalid()
                if (!hasOptionalObject(outbound, "settings")) return invalid()
            }

            val inbounds = root.opt("inbounds")
            if (inbounds != null) {
                if (inbounds !is org.json.JSONArray) return invalid()
                for (index in 0 until inbounds.length()) {
                    val inbound = inbounds.optJSONObject(index) ?: return invalid()
                    if (!hasNonBlankString(inbound, "protocol")) return invalid()
                    if (!hasOptionalObject(inbound, "settings")) return invalid()
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

    private fun hasNonBlankString(json: JSONObject, key: String): Boolean =
        json.opt(key) is String && (json.opt(key) as String).isNotBlank()

    private fun hasOptionalObject(json: JSONObject, key: String): Boolean {
        if (!json.has(key)) return true
        if (json.isNull(key)) return false
        return json.optJSONObject(key) != null
    }

    private fun invalid() = EngineFailure(
        code = "XRAY_CONFIG_PREFLIGHT_FAILED",
        safeMessage = "The Xray profile is malformed or structurally incomplete.",
        retryable = false
    )
}
