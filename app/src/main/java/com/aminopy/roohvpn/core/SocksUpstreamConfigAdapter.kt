package com.aminopy.roohvpn.core

import org.json.JSONArray
import org.json.JSONObject

/** Adds a loopback-only SOCKS upstream without changing unrelated profile settings. */
internal object SocksUpstreamConfigAdapter {
    private const val TAG = "rooh-upstream-socks"
    private val xrayProxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")
    private val singBoxProxyTypes = setOf("vless", "vmess", "trojan", "shadowsocks")

    fun applyXray(configuration: ByteArray, port: Int): ByteArray? {
        if (port !in 1..65535) return null
        return runCatching {
            val root = JSONObject(configuration.toString(Charsets.UTF_8))
            val outbounds = root.optJSONArray("outbounds") ?: return null
            val candidates = (0 until outbounds.length()).filter {
                outbounds.optJSONObject(it)?.optString("protocol")?.lowercase() in xrayProxyProtocols
            }
            if (candidates.size != 1) return null
            val proxy = outbounds.getJSONObject(candidates.single())
            if (proxy.has("proxySettings")) return null
            if ((0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("tag") == TAG }) return null

            val socks = JSONObject()
                .put("tag", TAG)
                .put("protocol", "socks")
                .put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject().put("address", "127.0.0.1").put("port", port)
                )))
                .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("mark", 0)))
            proxy.put("proxySettings", JSONObject().put("tag", TAG))
            outbounds.put(socks)
            root.toString().toByteArray(Charsets.UTF_8)
        }.getOrNull()
    }

    fun applySingBox(configuration: ByteArray, port: Int): ByteArray? {
        if (port !in 1..65535) return null
        return runCatching {
            val root = JSONObject(configuration.toString(Charsets.UTF_8))
            val outbounds = root.optJSONArray("outbounds") ?: return null
            val candidates = (0 until outbounds.length()).filter {
                outbounds.optJSONObject(it)?.optString("type")?.lowercase() in singBoxProxyTypes
            }
            if (candidates.size != 1) return null
            val proxy = outbounds.getJSONObject(candidates.single())
            if (proxy.has("detour")) return null
            if ((0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("tag") == TAG }) return null

            val socks = JSONObject()
                .put("type", "socks")
                .put("tag", TAG)
                .put("server", "127.0.0.1")
                .put("server_port", port)
            proxy.put("detour", TAG)
            outbounds.put(socks)
            root.toString().toByteArray(Charsets.UTF_8)
        }.getOrNull()
    }
}
