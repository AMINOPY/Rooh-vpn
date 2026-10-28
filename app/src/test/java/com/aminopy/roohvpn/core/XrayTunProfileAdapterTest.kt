package com.aminopy.roohvpn.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class XrayTunProfileAdapterTest {
    private fun profile(inbounds: String = "[]") = """
        {
          "inbounds": $inbounds,
          "outbounds": [
            {"tag":"proxy","protocol":"freedom","settings":{}}
          ]
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)

    @Test
    fun addsAndroidTunInboundAndPreservesOutbounds() {
        val adapted = XrayTunProfileAdapter.prepare(profile())!!
        val root = JSONObject(adapted)
        val inbounds = root.getJSONArray("inbounds")
        val tun = inbounds.getJSONObject(0)

        assertEquals(XrayTunProfileAdapter.TUN_TAG, tun.getString("tag"))
        assertEquals("tun", tun.getString("protocol"))
        assertEquals(1500, tun.getJSONObject("settings").getInt("MTU"))
        assertEquals("proxy", root.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
    }

    @Test
    fun appliesSniCdnOverridesBeforeAddingTunInbound() {
        val input = """
            {"outbounds":[{"tag":"proxy","protocol":"vless",
              "settings":{"vnext":[{"address":"origin.example.net","port":443,"users":[{"id":"x"}]}]},
              "streamSettings":{"network":"ws","security":"tls",
                "wsSettings":{"path":"/tunnel","headers":{"Host":"origin.example.net"}},
                "tlsSettings":{"serverName":"origin.example.net","allowInsecure":false}}}]}
        """.trimIndent().toByteArray()
        val adapted = XrayTunProfileAdapter.prepare(
            input,
            XrayCdnProfileAdapter.Overrides(
                sni = "front.example.com",
                cdnEdgeIp = "192.0.2.10",
                hostHeader = "origin.example.net"
            )
        )!!
        val root = JSONObject(adapted)
        val inbounds = root.getJSONArray("inbounds")
        assertEquals(XrayTunProfileAdapter.TUN_TAG, inbounds.getJSONObject(0).getString("tag"))
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("192.0.2.10", outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertEquals("front.example.com", outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("origin.example.net", outbound.getJSONObject("streamSettings").getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
    }

    @Test
    fun rejectsUserSuppliedTunInboundToAvoidDescriptorOwnershipConflict() {
        val input = profile("""[{"protocol":"tun","settings":{}}]""")
        assertNull(XrayTunProfileAdapter.prepare(input))
    }

    @Test
    fun rejectsMalformedOrIncompleteProfile() {
        assertNull(XrayTunProfileAdapter.prepare("""{"outbounds":[]}""".toByteArray()))
        assertNull(XrayTunProfileAdapter.prepare("not-json".toByteArray()))
    }

    @Test
    fun injectsTunWithoutDroppingExistingInbounds() {
        val input = profile("""[{"tag":"local","protocol":"socks","settings":{"auth":"noauth"}}]""")
        val root = JSONObject(XrayTunProfileAdapter.prepare(input)!!)
        val inbounds = root.getJSONArray("inbounds")

        assertEquals(2, inbounds.length())
        assertEquals(XrayTunProfileAdapter.TUN_TAG, inbounds.getJSONObject(0).getString("tag"))
        assertEquals("local", inbounds.getJSONObject(1).getString("tag"))
        assertNotNull(root.getJSONArray("outbounds"))
    }
}
