package com.aminopy.roohvpn.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Bounded, side-effect-free format detection for the import flow.
 * Detection is not validation and never means that an engine is installed.
 */
enum class ImportedProfileFormat {
    WIREGUARD,
    XRAY_JSON,
    SING_BOX_JSON,
    SHARE_LINK,
    UNKNOWN
}

data class ProfileClassification(
    val format: ImportedProfileFormat,
    val engineId: EngineId?,
    val requiresEngineIntegration: Boolean
)

object EngineConfigClassifier {
    private const val MAX_CONFIG_BYTES = 1024 * 1024

    fun classify(input: ByteArray): ProfileClassification {
        if (input.isEmpty() || input.size > MAX_CONFIG_BYTES) return unknown()
        val value = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input))
                .toString()
                .trim()
        } catch (_: Exception) {
            return unknown()
        }
        if (value.isEmpty()) return unknown()

        if (value.startsWith("[Interface]", ignoreCase = true) &&
            value.contains("[Peer]", ignoreCase = true)
        ) {
            return ProfileClassification(ImportedProfileFormat.WIREGUARD, EngineId.WIREGUARD, false)
        }

        // Share-link schemes overlap across engines and transport implementations.
        // Detect the format only; do not silently route every URI to Xray.
        if (listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "tuic://")
                .any { value.startsWith(it, ignoreCase = true) }
        ) {
            return ProfileClassification(ImportedProfileFormat.SHARE_LINK, null, true)
        }

        if (!value.startsWith("{")) return unknown()
        return try {
            val root = JSONObject(value)
            val outbounds = root.optJSONArray("outbounds") ?: return unknown()
            if (outbounds.length() == 0) return unknown()
            val first = outbounds.optJSONObject(0) ?: return unknown()
            when {
                first.opt("protocol") is String ->
                    ProfileClassification(ImportedProfileFormat.XRAY_JSON, EngineId.XRAY, true)
                first.opt("type") is String ->
                    ProfileClassification(ImportedProfileFormat.SING_BOX_JSON, EngineId.SING_BOX, true)
                else -> unknown()
            }
        } catch (_: Exception) {
            unknown()
        }
    }

    private fun unknown() = ProfileClassification(ImportedProfileFormat.UNKNOWN, null, true)
}
