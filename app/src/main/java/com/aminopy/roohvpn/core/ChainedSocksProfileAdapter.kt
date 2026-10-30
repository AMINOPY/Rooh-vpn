package com.aminopy.roohvpn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Adds a loopback SOCKS upstream to a validated Xray or sing-box profile.
 *
 * This is only the profile-side bridge. It does not start Psiphon or Aether;
 * callers must start and health-check the local SOCKS service first, then pass
 * its loopback address here. Never expose this listener on a LAN interface.
 */
internal object ChainedSocksProfileAdapter {
    data class Result(val configuration: ByteArray?, val errorCode: String? = null) {
        val isSuccess: Boolean get() = configuration != null && errorCode == null
    }

    private const val TAG = "roohvpn-chained-upstream"
    private val xrayProxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")
    private val singBoxProxyTypes = setOf("vless", "vmess", "trojan", "shadowsocks")

    fun applyXray(configuration: ByteArray, host: String = "127.0.0.1", port: Int): Result {
        if (!validLoopback(host) || port !in 1..65535) return Result(null, "INVALID_LOCAL_SOCKS")
        return try {
            val root = JSONObject(configuration.toString(Charsets.UTF_8))
            val outbounds = root.optJSONArray("outbounds") ?: return Result(null, "MISSING_OUTBOUNDS")
            val candidates = (0 until outbounds.length()).mapNotNull { i ->
                outbounds.optJSONObject(i)?.takeIf {
                    it.optString("protocol").lowercase() in xrayProxyProtocols
                }
            }
            if (candidates.size != 1) return Result(null, "AMBIGUOUS_PROXY_OUTBOUND")
            val proxy = candidates.single()
            if (proxy.has("proxySettings")) return Result(null, "EXISTING_PROXY_CHAIN_UNSUPPORTED")
            if ((0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("tag") == TAG }) {
                return Result(null, "UPSTREAM_TAG_COLLISION")
            }
            val socks = JSONObject()
                .put("tag", TAG)
                .put("protocol", "socks")
                .put("settings", JSONObject().put(
                    "servers",
                    JSONArray().put(JSONObject().put("address", host).put("port", port))
                ))
            proxy.put("proxySettings", JSONObject().put("tag", TAG))
            outbounds.put(socks)
            Result(root.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            Result(null, "INVALID_XRAY_PROFILE")
        }
    }

    fun applySingBox(configuration: ByteArray, host: String = "127.0.0.1", port: Int): Result {
        if (!validLoopback(host) || port !in 1..65535) return Result(null, "INVALID_LOCAL_SOCKS")
        return try {
            val root = JSONObject(configuration.toString(Charsets.UTF_8))
            val outbounds = root.optJSONArray("outbounds") ?: return Result(null, "MISSING_OUTBOUNDS")
            val candidates = (0 until outbounds.length()).mapNotNull { i ->
                outbounds.optJSONObject(i)?.takeIf {
                    it.optString("type").lowercase() in singBoxProxyTypes
                }
            }
            if (candidates.size != 1) return Result(null, "AMBIGUOUS_PROXY_OUTBOUND")
            val proxy = candidates.single()
            if (proxy.has("detour")) return Result(null, "EXISTING_PROXY_CHAIN_UNSUPPORTED")
            if ((0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("tag") == TAG }) {
                return Result(null, "UPSTREAM_TAG_COLLISION")
            }
            val socks = JSONObject()
                .put("type", "socks")
                .put("tag", TAG)
                .put("server", host)
                .put("server_port", port)
            proxy.put("detour", TAG)
            // Place the upstream before the outbound that references it.
            outbounds.put(0, socks)
            Result(root.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            Result(null, "INVALID_SING_BOX_PROFILE")
        }
    }

    private fun validLoopback(host: String): Boolean =
        host == "127.0.0.1" || host == "::1"
}
