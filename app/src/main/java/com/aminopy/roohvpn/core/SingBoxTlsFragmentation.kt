package com.aminopy.roohvpn.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Applies sing-box TLS record fragmentation to TLS-enabled outbound entries only.
 *
 * The original profile bytes are never modified. The caller owns and should
 * clear both the original and returned byte arrays when no longer needed.
 * This only changes configuration; it does not prove connectivity or bypass
 * behavior on any particular network.
 */
object SingBoxTlsFragmentation {
    private const val MAX_CONFIG_BYTES = 1024 * 1024

    data class Result(
        val configuration: ByteArray,
        val modifiedOutbounds: Int
    )

    fun enableRecordFragmentation(configuration: ByteArray): Result? {
        if (configuration.isEmpty() || configuration.size > MAX_CONFIG_BYTES) return null

        val source = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(configuration))
                .toString()
        } catch (_: Exception) {
            return null
        }

        return try {
            val root = JSONObject(source)
            val outbounds = root.optJSONArray("outbounds") ?: return null
            var changed = 0
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                val tls = outbound.optJSONObject("tls") ?: continue
                if (tls.optBoolean("enabled", false)) {
                    tls.put("record_fragment", true)
                    changed++
                }
            }
            if (changed == 0) return null
            Result(root.toString().toByteArray(StandardCharsets.UTF_8), changed)
        } catch (_: Exception) {
            null
        }
    }
}
