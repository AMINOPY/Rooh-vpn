package com.aminopy.roohvpn.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XraySniCdnProfileAdapterTest {
    private fun vlessProfile(): ByteArray = """
        {
          "outbounds": [{
            "tag": "proxy",
            "protocol": "vless",
            "settings": {
              "vnext": [{
                "address": "origin.example.com",
                "port": 443,
                "users": [{"id": "00000000-0000-0000-0000-000000000000", "encryption": "none"}]
              }]
            },
            "streamSettings": {
              "network": "tcp",
              "security": "tls",
              "tlsSettings": {"serverName": "origin.example.com"}
            }
          }],
          "routing": {"rules": []}
        }
    """.trimIndent().toByteArray()

    private val options = XraySniCdnProfileAdapter.Options(
        edgeAddress = "203.0.113.20",
        sni = "tls.example.net",
        hostHeader = "origin.example.com",
        websocketPath = "/tunnel",
        port = 443
    )

    @Test
    fun setsEdgeSniHostAndWebsocketWhilePreservingRoutingAndCredentials() {
        val root = JSONObject(XraySniCdnProfileAdapter.prepare(vlessProfile(), options)!!)
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        val server = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")

        assertEquals("203.0.113.20", server.getString("address"))
        assertEquals(443, server.getInt("port"))
        assertEquals("ws", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        assertEquals("tls.example.net", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertFalse(stream.getJSONObject("tlsSettings").getBoolean("allowInsecure"))
        assertEquals("http/1.1", stream.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
        assertEquals("origin.example.com", stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
        assertEquals("/tunnel", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("00000000-0000-0000-0000-000000000000",
            server.getJSONArray("users").getJSONObject(0).getString("id"))
        assertEquals(0, root.getJSONObject("routing").getJSONArray("rules").length())
    }

    @Test
    fun acceptsTrojanServerLayout() {
        val input = """
            {"outbounds":[{"protocol":"trojan","settings":{"servers":[{"address":"origin.example.com","port":443,"password":"test"}]}}]}
        """.trimIndent().toByteArray()
        val root = JSONObject(XraySniCdnProfileAdapter.prepare(input, options)!!)
        val server = root.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("203.0.113.20", server.getString("address"))
    }

    @Test
    fun rejectsUnsupportedProtocolAndRealityTransport() {
        val unsupported = """{"outbounds":[{"protocol":"freedom","settings":{}}]}""".toByteArray()
        assertNull(XraySniCdnProfileAdapter.prepare(unsupported, options))
        val reality = """
            {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"origin.example.com","port":443,"users":[{"id":"x"}]}]},"streamSettings":{"security":"reality","realitySettings":{}}}]}
        """.trimIndent().toByteArray()
        assertNull(XraySniCdnProfileAdapter.prepare(reality, options))
    }

    @Test
    fun rejectsInvalidHostsPathAndPort() {
        assertNull(XraySniCdnProfileAdapter.prepare(vlessProfile(), options.copy(sni = "bad host")))
        assertNull(XraySniCdnProfileAdapter.prepare(vlessProfile(), options.copy(hostHeader = "host\r\nInjected: yes")))
        assertNull(XraySniCdnProfileAdapter.prepare(vlessProfile(), options.copy(websocketPath = "no-leading-slash")))
        assertNull(XraySniCdnProfileAdapter.prepare(vlessProfile(), options.copy(websocketPath = "/ok\r\nInjected")))
        assertNull(XraySniCdnProfileAdapter.prepare(vlessProfile(), options.copy(port = 70000)))
        assertNull(XraySniCdnProfileAdapter.prepare(vlessProfile(), options.copy(edgeAddress = "999.1.1.1")))
    }

    @Test
    fun doesNotDisableCertificateValidationOrInventAnEndpoint() {
        val prepared = XraySniCdnProfileAdapter.prepare(vlessProfile(), options)!!
        assertTrue(prepared.contains("\"allowInsecure\":false"))
        assertFalse(prepared.contains("insecure\":true"))
        assertNull(XraySniCdnProfileAdapter.prepare(vlessProfile(), options.copy(edgeAddress = "")))
    }
}
