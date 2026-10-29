package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayTransportCapabilityAnalyzerTest {
    private fun profile(network: String, security: String, streamOptions: String): ByteArray = """
        {
          "outbounds": [{
            "tag": "proxy",
            "protocol": "vless",
            "settings": {"vnext": [{"address": "origin.example.net", "port": 443, "users": [{"id": "x"}]}]},
            "streamSettings": {
              "network": "$network",
              "security": "$security",
              $streamOptions
            }
          }]
        }
    """.trimIndent().toByteArray()

    @Test
    fun websocketTlsReportsSniAndCdnSupportAndRequirements() {
        val result = XrayTransportCapabilityAnalyzer.analyze(
            profile(
                "ws", "tls",
                "\"wsSettings\": {\"path\": \"/tunnel\"}, \"tlsSettings\": {\"serverName\": \"origin.example.net\"}"
            )
        )
        assertTrue(result.errorCode ?: "unexpected analysis failure", result.isSuccess)
        val report = result.report!!
        assertTrue(report.supportsSniOverride)
        assertTrue(report.supportsCdnEdgeOverride)
        assertEquals(listOf("TLS SNI", "WebSocket Host", "path", "CDN edge IP"), report.requiredForCdn)
        assertNull(report.limitationCode)
    }

    @Test
    fun realitySupportsSniButDoesNotClaimCdnEdgeOverrideSupport() {
        val result = XrayTransportCapabilityAnalyzer.analyze(
            profile("tcp", "reality", "\"realitySettings\": {\"serverName\": \"origin.example.net\"}")
        )
        assertTrue(result.isSuccess)
        assertTrue(result.report!!.supportsSniOverride)
        assertFalse(result.report.supportsCdnEdgeOverride)
        assertEquals("TRANSPORT_NOT_CDN_COMPATIBLE", result.report.limitationCode)
    }

    @Test
    fun websocketWithoutTlsIsNotMarkedCdnCompatible() {
        val result = XrayTransportCapabilityAnalyzer.analyze(
            profile("ws", "none", "\"wsSettings\": {\"path\": \"/tunnel\"}")
        )
        assertTrue(result.isSuccess)
        assertFalse(result.report!!.supportsSniOverride)
        assertFalse(result.report.supportsCdnEdgeOverride)
        assertEquals("CDN_REQUIRES_TLS", result.report.limitationCode)
    }

    @Test
    fun tcpTlsSupportsSniButNotCdnEdgeIpOverride() {
        val result = XrayTransportCapabilityAnalyzer.analyze(
            profile("tcp", "tls", "\"tlsSettings\": {\"serverName\": \"origin.example.net\"}")
        )
        assertTrue(result.isSuccess)
        assertTrue(result.report!!.supportsSniOverride)
        assertFalse(result.report.supportsCdnEdgeOverride)
    }

    @Test
    fun malformedProfileIsRejectedWithoutLeakingInput() {
        val result = XrayTransportCapabilityAnalyzer.analyze("not-json".toByteArray())
        assertFalse(result.isSuccess)
        assertNull(result.report)
        assertEquals("INVALID_XRAY_PROFILE", result.errorCode)
    }
}
