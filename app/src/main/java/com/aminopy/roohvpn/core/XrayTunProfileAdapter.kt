package com.aminopy.roohvpn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapts an Xray proxy profile to the pinned Android TUN inbound.
 * The Android VpnService owns the original TUN descriptor.
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

    private val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")

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

            // Full-device VPN traffic must not silently fall through to a direct,
            // blackhole, or unrelated outbound. Require one unambiguous proxy.
            val proxyOutbounds = (0 until outbounds.length()).mapNotNull { index ->
                val outbound = outbounds.optJSONObject(index) ?: return null
                if (outbound.optString("protocol").lowercase() in proxyProtocols) outbound else null
            }
            if (proxyOutbounds.size != 1) return null
            // Duplicate tags make routing ambiguous even when there is only
            // one proxy-protocol outbound. Reject them before adding the guard rule.
            val outboundTags = (0 until outbounds.length()).mapNotNull { index ->
                outbounds.optJSONObject(index)?.optString("tag")?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (outboundTags.size != outboundTags.distinct().size) return null

            val proxyOutbound = proxyOutbounds.single()
            var proxyTag = proxyOutbound.optString("tag").trim()
            if (proxyTag.isEmpty()) {
                proxyTag = "roohvpn-proxy"
                if ((0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("tag") == proxyTag }) {
                    return null
                }
                proxyOutbound.put("tag", proxyTag)
            }

            // Do not attach a second TUN to a descriptor owned by another service.
            val inbounds = root.optJSONArray("inbounds") ?: JSONArray()
            val inboundTags = (0 until inbounds.length()).mapNotNull { index ->
                val inbound = inbounds.optJSONObject(index) ?: return null
                val tag = inbound.optString("tag").trim()
                if (tag == TUN_TAG || inbound.optString("protocol").equals("tun", ignoreCase = true)) {
                    return null
                }
                tag.takeIf { it.isNotEmpty() }
            }
            if (inboundTags.size != inboundTags.distinct().size) return null

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

            val updatedInbounds = JSONArray().put(tunInbound)
            for (i in 0 until inbounds.length()) updatedInbounds.put(inbounds.get(i))
            root.put("inbounds", updatedInbounds)

            // Highest priority: packets entering the app-owned TUN use the
            // selected proxy outbound. Existing user rules remain after this
            // guard, so they cannot accidentally send full-tunnel traffic direct.
            val routing = root.optJSONObject("routing") ?: JSONObject().also {
                root.put("routing", it)
            }
            val rules = routing.optJSONArray("rules") ?: JSONArray().also {
                routing.put("rules", it)
            }
            val guardedRules = JSONArray().put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put(TUN_TAG))
                    .put("outboundTag", proxyTag)
            )
            for (i in 0 until rules.length()) guardedRules.put(rules.get(i))
            routing.put("rules", guardedRules)

            root.toString()
        } catch (_: Exception) {
            null
        }
    }
}
