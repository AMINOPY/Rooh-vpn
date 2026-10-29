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
            {"tag":"proxy","protocol":"vless",
             "settings":{"vnext":[{"address":"origin.example.net","port":443,"users":[{"id":"x","encryption":"none"}]}]},
             "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"origin.example.net"}}}
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
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        assertEquals(XrayTunProfileAdapter.TUN_TAG, rules.getJSONObject(0).getJSONArray("inboundTag").getString(0))
        assertEquals("proxy", rules.getJSONObject(0).getString("outboundTag"))
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
    fun rejectsTlsProfileThatDisablesCertificateValidation() {
        val input = """
            {"outbounds":[{"tag":"proxy","protocol":"vless",
              "settings":{"vnext":[{"address":"origin.example.net","port":443,"users":[{"id":"x"}]}]},
              "streamSettings":{"network":"tcp","security":"tls",
                "tlsSettings":{"serverName":"origin.example.net","allowInsecure":true}}}]}
        """.trimIndent().toByteArray()
        assertNull(XrayTunProfileAdapter.prepare(input))
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
        assertEquals(XrayTunProfileAdapter.TUN_TAG,
            root.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
                .getJSONArray("inboundTag").getString(0))
    }

    @Test
    fun putsTunnelGuardBeforeExistingRulesAndPreservesTheirOrder() {
        val input = """
            {
              "outbounds":[
                {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"origin.example.net","port":443,"users":[{"id":"x"}]}]}},
                {"tag":"direct","protocol":"freedom","settings":{}}
              ],
              "routing":{"rules":[{"type":"field","domain":["example.org"],"outboundTag":"direct"}]}
            }
        """.trimIndent().toByteArray()
        val root = JSONObject(XrayTunProfileAdapter.prepare(input)!!)
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        assertEquals(2, rules.length())
        assertEquals("proxy", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("direct", rules.getJSONObject(1).getString("outboundTag"))
        assertEquals("example.org", rules.getJSONObject(1).getJSONArray("domain").getString(0))
    }

    @Test
    fun rejectsDuplicateOutboundTagsBeforeAddingTunnelGuard() {
        val input = """
            {"outbounds":[
              {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"one.example.net","port":443,"users":[{"id":"1"}]}]}},
              {"tag":"proxy","protocol":"freedom","settings":{}}
            ]}
        """.trimIndent().toByteArray()
        assertNull(XrayTunProfileAdapter.prepare(input))
    }

    @Test
    fun rejectsDuplicateInboundTagsAndReservedTunnelTag() {
        val duplicate = profile("""
            [
              {"tag":"local","protocol":"socks","settings":{"auth":"noauth"}},
              {"tag":"local","protocol":"http","settings":{}}
            ]
        """.trimIndent())
        assertNull(XrayTunProfileAdapter.prepare(duplicate))

        val reserved = profile("""
            [{"tag":"roohvpn-tun","protocol":"socks","settings":{"auth":"noauth"}}]
        """.trimIndent())
        assertNull(XrayTunProfileAdapter.prepare(reserved))
    }

    @Test
    fun rejectsProfilesWithoutExactlyOneProxyOutbound() {
        val directOnly = """{"outbounds":[{"tag":"direct","protocol":"freedom","settings":{}}]}""".toByteArray()
        assertNull(XrayTunProfileAdapter.prepare(directOnly))

        val ambiguous = """
            {"outbounds":[
              {"tag":"p1","protocol":"vless","settings":{"vnext":[{"address":"one.example.net","port":443,"users":[{"id":"1"}]}]}},
              {"tag":"p2","protocol":"vmess","settings":{"vnext":[{"address":"two.example.net","port":443,"users":[{"id":"2"}]}]}}
            ]}
        """.trimIndent().toByteArray()
        assertNull(XrayTunProfileAdapter.prepare(ambiguous))
    }
}
