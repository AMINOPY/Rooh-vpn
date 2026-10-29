package com.aminopy.roohvpn.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxCdnProfileAdapterTest {
    private fun wsProfile() = """
        {
          "outbounds": [{
            "type": "vless",
            "tag": "proxy",
            "server": "origin.example.net",
            "server_port": 443,
            "uuid": "00000000-0000-0000-0000-000000000001",
            "tls": {"enabled": true, "server_name": "origin.example.net", "insecure": false},
            "transport": {"type": "ws", "path": "/tunnel", "headers": {"X-Test": "preserve"}}
          }]
        }
    """.trimIndent().toByteArray()

    @Test fun appliesEdgeIpSniAndSeparateWebsocketHost() {
        val result = SingBoxCdnProfileAdapter.apply(
            wsProfile(),
            SingBoxCdnProfileAdapter.Overrides("Front.Example.com", "192.0.2.10", "origin.example.net")
        )
        assertTrue(result.errorCode ?: "unexpected transform failure", result.isSuccess)
        val root = JSONObject(String(result.configuration!!, Charsets.UTF_8))
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("192.0.2.10", outbound.getString("server"))
        assertEquals("front.example.com", outbound.getJSONObject("tls").getString("server_name"))
        val transport = outbound.getJSONObject("transport")
        assertEquals("origin.example.net", transport.getJSONObject("headers").getString("Host"))
        assertEquals("preserve", transport.getJSONObject("headers").getString("X-Test"))
        assertEquals("/tunnel", transport.getString("path"))
        assertFalse(outbound.getJSONObject("tls").getBoolean("insecure"))
    }

    @Test fun sniOnlyDoesNotChangeEndpointOrDisableVerification() {
        val result = SingBoxCdnProfileAdapter.apply(
            wsProfile(),
            SingBoxCdnProfileAdapter.Overrides(sni = "front.example.com")
        )
        assertTrue(result.errorCode ?: "unexpected transform failure", result.isSuccess)
        val outbound = JSONObject(String(result.configuration!!, Charsets.UTF_8))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("origin.example.net", outbound.getString("server"))
        assertEquals("front.example.com", outbound.getJSONObject("tls").getString("server_name"))
        assertFalse(outbound.getJSONObject("tls").getBoolean("insecure"))
    }

    @Test fun rejectsCdnWithoutTlsAndHostHeaderOnNonWebsocketTransport() {
        val noTls = """{"outbounds":[{"type":"vless","server":"origin.example.net","server_port":443}]}""".toByteArray()
        assertEquals("TLS_REQUIRED", SingBoxCdnProfileAdapter.apply(
            noTls, SingBoxCdnProfileAdapter.Overrides(sni = "front.example.com", cdnEdgeIp = "192.0.2.1")
        ).errorCode)

        val grpc = """{"outbounds":[{"type":"vless","server":"origin.example.net","server_port":443,"tls":{"enabled":true},"transport":{"type":"grpc"}}]}""".toByteArray()
        assertEquals("HOST_HEADER_TRANSPORT_UNSUPPORTED", SingBoxCdnProfileAdapter.apply(
            grpc, SingBoxCdnProfileAdapter.Overrides(hostHeader = "origin.example.net")
        ).errorCode)
    }

    @Test fun rejectsInvalidHostnameAndIpLiteral() {
        assertEquals("INVALID_SNI", SingBoxCdnProfileAdapter.apply(
            wsProfile(), SingBoxCdnProfileAdapter.Overrides(sni = "not a hostname")
        ).errorCode)
        assertEquals("INVALID_CDN_IP", SingBoxCdnProfileAdapter.apply(
            wsProfile(), SingBoxCdnProfileAdapter.Overrides(cdnEdgeIp = "not-an-ip")
        ).errorCode)
    }
}
