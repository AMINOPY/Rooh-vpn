package com.aminopy.roohvpn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapts a normal Xray outbound profile to the pinned Android Xray TUN inbound.
 * The Android VpnService remains the owner of the original TUN descriptor.
 */
internal object XrayTunProfileAdapter {
    const val TUN_TAG = "roohvpn-tun"
    const val MTU = 1500
    const val IPV4_ADDRESS = "172.19.0.2"
    const val IPV4_PREFIX = 30
    const val IPV6_ADDRESS = "fdfe:dcba:9876::2"
    const val IPV6_PREFIX = 126
    const val IPV4_DNS = "1.1.1.1"
    const val IPV6_DNS = "2606:4700:4700::1111"

    fun prepare(
        configuration: ByteArray,
        transportOverrides: XrayCdnProfileAdapter.Overrides? = null
    ): String? {
        if (XrayProfilePreflight.validate(configuration) != null) return null
        val effectiveConfiguration = if (transportOverrides == null) {
            configuration.toString(Charsets.UTF_8)
        } else {
            XrayCdnProfileAdapter.apply(configuration, transportOverrides).configuration ?: return null
        }
        return try {
            val root = JSONObject(effectiveConfiguration)
            val outbounds = root.optJSONArray("outbounds") ?: return null
            if (outbounds.length() == 0) return null

            // Avoid accidentally starting a user-supplied tun inbound against a
            // descriptor whose lifetime/addresses are owned by another service.
            val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also {
                root.put("inbounds", it)
            }
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(i) ?: return null
                if (inbound.optString("protocol").equals("tun", ignoreCase = true)) {
                    return null
                }
            }

            val tunSettings = JSONObject()
                .put("name", "roohvpn0")
                .put("MTU", MTU)
                .put("gateway", JSONArray()
                    .put("$IPV4_ADDRESS/$IPV4_PREFIX")
                    .put("$IPV6_ADDRESS/$IPV6_PREFIX"))
                .put("DNS", JSONArray().put(IPV4_DNS).put(IPV6_DNS))

            val tunInbound = JSONObject()
                .put("tag", TUN_TAG)
                .put("protocol", "tun")
                .put("settings", tunSettings)

            // Put TUN first so inbound ordering remains deterministic. Existing
            // user routing rules and outbound tags are preserved unchanged.
            val updated = JSONArray().put(tunInbound)
            for (i in 0 until inbounds.length()) updated.put(inbounds.get(i))
            root.put("inbounds", updated)
            root.toString()
        } catch (_: Exception) {
            null
        }
    }
}
