package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

class XrayProfilePreflightTest {
    private fun validate(value: String) =
        XrayProfilePreflight.validate(value.toByteArray(StandardCharsets.UTF_8))

    @Test fun acceptsStructurallyCompleteProfile() {
        assertNull(validate("""{"outbounds":[{"protocol":"freedom","settings":{}}]}"""))
    }

    @Test fun rejectsMissingOrEmptyOutbounds() {
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[]}""")?.code)
    }

    @Test fun rejectsOutboundWithoutStringProtocolOrWithInvalidSettings() {
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"settings":{}}]}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":123}]}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":{}}]}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":"vless","settings":[]}]}""")?.code)
    }

    @Test fun rejectsMalformedJsonWrongOptionalTypesAndEmptyInput() {
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("{broken")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{}],"routing":[]}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("")?.code)
    }

    @Test fun rejectsOversizedInput() {
        val oversized = ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() }
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", XrayProfilePreflight.validate(oversized)?.code)
    }

    @Test fun failureDoesNotEchoProfileContents() {
        val secret = """{"outbounds":[{"protocol":"vless","settings":{"id":"private-secret"}}]}"""
        val failure = validate(secret.replace("vless", ""))
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", failure?.code)
        org.junit.Assert.assertFalse(failure?.safeMessage?.contains("private-secret") == true)
    }

    @Test fun rejectsMalformedUtf8WithoutExposingInput() {
        val malformed = byteArrayOf(0x7B, 0x22, 0xC3.toByte(), 0x28, 0x22, 0x7D)
        val failure = XrayProfilePreflight.validate(malformed)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", failure?.code)
        org.junit.Assert.assertFalse(failure?.safeMessage?.contains("C3") == true)
    }

    @Test fun rejectsInvalidInboundAndNullSettings() {
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":"vless","settings":null}]}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":"freedom"}],"inbounds":{}}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[null]}""")?.code)
    }

    @Test fun acceptsEmptyOptionalInboundArrayAndRoutingObject() {
        assertNull(validate("""{"outbounds":[{"protocol":"freedom"}],"inbounds":[],"routing":{}}"""))
    }

    @Test fun rejectsNonObjectInboundEntriesAndInvalidRoutingRules() {
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":"freedom"}],"inbounds":[null]}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":"freedom"}],"routing":{"rules":{}}}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":"freedom"}],"routing":{"rules":[null]}}""")?.code)
    }

    @Test fun rejectsInboundWithoutProtocolOrWithInvalidSettings() {
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":"freedom"}],"inbounds":[{}]}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":"freedom"}],"inbounds":[{"protocol":false}]}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":"freedom"}],"inbounds":[{"protocol":"socks","settings":null}]}""")?.code)
        assertEquals("XRAY_CONFIG_PREFLIGHT_FAILED", validate("""{"outbounds":[{"protocol":"freedom"}],"inbounds":[{"protocol":"socks","settings":[]}]}""")?.code)
    }

    @Test fun acceptsObjectInboundAndRoutingRuleArray() {
        assertNull(validate("""{"inbounds":[{"protocol":"socks","port":1080}],"outbounds":[{"protocol":"freedom"}],"routing":{"rules":[]}}"""))
    }
}
