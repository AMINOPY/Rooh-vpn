package com.aminopy.roohvpn.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayCdnProfileAdapterTest {
    private fun wsProfile(): ByteArray = """
        {
          "outbounds": [{
            "tag": "proxy",
            "protocol": "vless",
            "settings": {
              "vnext": [{
                "address": "origin.example.net",
                "port": 443,
                "users": [{"id": "00000000-0000-0000-0000-000000000001", "encryption": "none"}]
              }]
            },
            "streamSettings": {
              "network": "ws",
              "security": "tls",
              "wsSettings": {"path": "/tunnel", "headers": {"Host": "origin.example.net"}},
              "tlsSettings": {"serverName": "origin.example.net", "allowInsecure": false}
            }
          }]
        }
    """.trimIndent().toByteArray()

    @Test fun appliesCdnIpSniAndSeparateWebsocketHostWithoutDisablingTlsValidation() {
        val result = XrayCdnProfileAdapter.apply(
            wsProfile(),
            XrayCdnProfileAdapter.Overrides(
                sni = "Front.Example.com",
                cdnEdgeIp = "192.0.2.10",
                hostHeader = "origin.example.net"
            )
        )
        assertTrue(result.errorCode ?: "unexpected transform failure", result.isSuccess)
        val root = JSONObject(result.configuration!!)
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        val server = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")

        assertEquals("192.0.2.10", server.getString("address"))
        assertEquals("front.example.com", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("origin.example.net", stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
        assertFalse(stream.getJSONObject("tlsSettings").getBoolean("allowInsecure"))
        assertEquals("/tunnel", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("00000000-0000-0000-0000-000000000001",
            server.getJSONArray("users").getJSONObject(0).getString("id"))
    }

    @Test fun usesSniAsDefaultWebsocketHostWhenNoSeparateHostIsProvided() {
        val result = XrayCdnProfileAdapter.apply(
            wsProfile(),
            XrayCdnProfileAdapter.Overrides(sni = "cdn.example.com", cdnEdgeIp = "192.0.2.15")
        )
        assertTrue(result.errorCode ?: "unexpected transform failure", result.isSuccess)
        val stream = JSONObject(result.configuration!!)
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("cdn.example.com", stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
    }

    @Test fun supportsGrpcAuthorityAndCdnAddress() {
        val input = """
            {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"origin.example.net","port":443,"users":[{"id":"x"}]}]},
            "streamSettings":{"network":"grpc","security":"tls","grpcSettings":{"serviceName":"edge","authority":"old.example.net"},
            "tlsSettings":{"serverName":"old.example.net","allowInsecure":false}}}]}
        """.trimIndent().toByteArray()
        val result = XrayCdnProfileAdapter.apply(
            input,
            XrayCdnProfileAdapter.Overrides("front.example.com", "192.0.2.20", "origin.example.net")
        )
        assertTrue(result.errorCode ?: "unexpected transform failure", result.isSuccess)
        val outbound = JSONObject(result.configuration!!).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("192.0.2.20", outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("front.example.com", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("origin.example.net", stream.getJSONObject("grpcSettings").getString("authority"))
        assertEquals("edge", stream.getJSONObject("grpcSettings").getString("serviceName"))
    }

    @Test fun defaultsTlsSniToHostWhenOnlyHostAndCdnIpAreProvided() {
        val result = XrayCdnProfileAdapter.apply(
            wsProfile(),
            XrayCdnProfileAdapter.Overrides(cdnEdgeIp = "192.0.2.16", hostHeader = "origin.example.net")
        )
        assertTrue(result.errorCode ?: "unexpected transform failure", result.isSuccess)
        val stream = JSONObject(result.configuration!!)
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("origin.example.net", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("origin.example.net", stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
    }

    @Test fun supportsSniOnlyForTlsWithoutChangingServerAddress() {
        val result = XrayCdnProfileAdapter.apply(
            wsProfile(),
            XrayCdnProfileAdapter.Overrides(sni = "tls.example.com")
        )
        assertTrue(result.errorCode ?: "unexpected transform failure", result.isSuccess)
        val outbound = JSONObject(result.configuration!!).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("origin.example.net", outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertEquals("tls.example.com", outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test fun supportsRealitySniWithoutCdnIp() {
        val input = """
            {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"server.example.net","port":443,"users":[{"id":"x"}]}]},
            "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"old.example.net","publicKey":"key","shortId":"abcd"}}}]}
        """.trimIndent().toByteArray()
        val result = XrayCdnProfileAdapter.apply(input, XrayCdnProfileAdapter.Overrides(sni = "front.example.com"))
        assertTrue(result.errorCode ?: "unexpected transform failure", result.isSuccess)
        val outbound = JSONObject(result.configuration!!).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("front.example.com", outbound.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("serverName"))
        assertEquals("server.example.net", outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
    }

    @Test fun rejectsCdnForRealityAndUnsupportedTransport() {
        val reality = """
            {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"server.example.net","port":443,"users":[{"id":"x"}]}]},
            "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"old.example.net"}}}]}
        """.trimIndent().toByteArray()
        val result = XrayCdnProfileAdapter.apply(
            reality,
            XrayCdnProfileAdapter.Overrides(sni = "front.example.com", cdnEdgeIp = "192.0.2.30")
        )
        assertNull(result.configuration)
        assertEquals("CDN_REQUIRES_TLS", result.errorCode)
    }

    @Test fun rejectsInvalidSniHostAndNonIpCdnAddress() {
        assertEquals("INVALID_SNI", XrayCdnProfileAdapter.apply(
            wsProfile(), XrayCdnProfileAdapter.Overrides(sni = "bad host")
        ).errorCode)
        assertEquals("INVALID_HOST_HEADER", XrayCdnProfileAdapter.apply(
            wsProfile(), XrayCdnProfileAdapter.Overrides(hostHeader = "bad/path.example")
        ).errorCode)
        assertEquals("INVALID_CDN_IP", XrayCdnProfileAdapter.apply(
            wsProfile(), XrayCdnProfileAdapter.Overrides(sni = "cdn.example.com", cdnEdgeIp = "not-an-ip.example")
        ).errorCode)
    }

    @Test fun rejectsAmbiguousMultipleProxyOutboundsAndMultipleServers() {
        val multiple = """
            {"outbounds":[
              {"protocol":"vless","settings":{"vnext":[{"address":"a.example.com","port":443,"users":[{"id":"x"}]}]},"streamSettings":{"network":"ws","security":"tls","wsSettings":{},"tlsSettings":{}}},
              {"protocol":"freedom","settings":{}},
              {"protocol":"vmess","settings":{"vnext":[{"address":"b.example.com","port":443,"users":[{"id":"y"}]}]},"streamSettings":{"network":"ws","security":"tls","wsSettings":{},"tlsSettings":{}}}
            ]}
        """.trimIndent().toByteArray()
        assertEquals("AMBIGUOUS_PROXY_OUTBOUND", XrayCdnProfileAdapter.apply(
            multiple, XrayCdnProfileAdapter.Overrides(sni = "cdn.example.com")
        ).errorCode)

        val manyRoot = JSONObject(wsProfile().toString(Charsets.UTF_8))
        val manyOutbound = manyRoot.getJSONArray("outbounds").getJSONObject(0)
        manyOutbound.getJSONObject("settings").getJSONArray("vnext")
            .put(JSONObject().put("address", "other.example.net").put("port", 443)
                .put("users", org.json.JSONArray().put(JSONObject().put("id", "other"))))
        val manyServers = manyRoot.toString().toByteArray()
        val manyResult = XrayCdnProfileAdapter.apply(
            manyServers, XrayCdnProfileAdapter.Overrides(sni = "cdn.example.com", cdnEdgeIp = "192.0.2.50")
        )
        assertNull(manyResult.configuration)
        assertEquals("UNSUPPORTED_SERVER_LAYOUT", manyResult.errorCode)
    }

    @Test fun noOverridesIsNotAValidTransform() {
        val result = XrayCdnProfileAdapter.apply(wsProfile(), XrayCdnProfileAdapter.Overrides())
        assertFalse(result.isSuccess)
        assertNotNull(result.errorCode)
        assertEquals("NO_OVERRIDES", result.errorCode)
    }
}
