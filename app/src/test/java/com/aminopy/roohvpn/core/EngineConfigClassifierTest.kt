package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class EngineConfigClassifierTest {
    private fun classify(text: String) =
        EngineConfigClassifier.classify(text.toByteArray(StandardCharsets.UTF_8))

    @Test fun identifiesWireGuardWithoutClaimingAdditionalIntegration() {
        val result = classify("[Interface]\nPrivateKey = redacted\n[Peer]\nPublicKey = redacted")
        assertEquals(ImportedProfileFormat.WIREGUARD, result.format)
        assertEquals(EngineId.WIREGUARD, result.engineId)
        assertFalse(result.requiresEngineIntegration)
    }

    @Test fun identifiesXrayJsonAsUnintegrated() {
        val result = classify("""{"outbounds":[{"protocol":"vless","settings":{}}]}""")
        assertEquals(ImportedProfileFormat.XRAY_JSON, result.format)
        assertEquals(EngineId.XRAY, result.engineId)
        assertTrue(result.requiresEngineIntegration)
    }

    @Test fun identifiesSingBoxJsonAsUnintegrated() {
        val result = classify("""{"outbounds":[{"type":"vless","server":"example.invalid"}]}""")
        assertEquals(ImportedProfileFormat.SING_BOX_JSON, result.format)
        assertEquals(EngineId.SING_BOX, result.engineId)
        assertTrue(result.requiresEngineIntegration)
    }

    @Test fun identifiesShareLinksWithoutTreatingThemAsValidatedProfiles() {
        val result = classify("vless://example")
        assertEquals(ImportedProfileFormat.SHARE_LINK, result.format)
        assertEquals(null, result.engineId)
        assertTrue(result.requiresEngineIntegration)
        val hysteria = classify("hysteria2://example")
        assertEquals(ImportedProfileFormat.SHARE_LINK, hysteria.format)
        assertEquals(null, hysteria.engineId)
    }

    @Test fun rejectsMalformedUnknownEmptyAndOversizedInputs() {
        assertEquals(ImportedProfileFormat.UNKNOWN, classify("").format)
        assertEquals(ImportedProfileFormat.UNKNOWN, classify("{bad json").format)
        assertEquals(ImportedProfileFormat.UNKNOWN, classify("""{"outbounds":[]}""").format)
        val oversized = ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() }
        assertEquals(ImportedProfileFormat.UNKNOWN, EngineConfigClassifier.classify(oversized).format)
    }

    @Test fun rejectsMalformedUtf8InsteadOfClassifyingReplacementCharacters() {
        val malformed = byteArrayOf(
            0x7B, 0x22, 0x6F, 0x75, 0x74, 0x62, 0x6F, 0x75, 0x6E, 0x64, 0x73,
            0x22, 0x3A, 0x5B, 0x7B, 0x22, 0x70, 0x72, 0x6F, 0x74, 0x6F, 0x63,
            0x6F, 0x6C, 0x22, 0x3A, 0x22, 0x76, 0x6C, 0x65, 0x73, 0x73, 0x22,
            0x7D, 0x5D, 0x7D, 0xC3.toByte(), 0x28
        )
        assertEquals(
            ImportedProfileFormat.UNKNOWN,
            EngineConfigClassifier.classify(malformed).format
        )
    }

    @Test fun doesNotClassifyNonStringProtocolAsXray() {
        assertEquals(
            ImportedProfileFormat.UNKNOWN,
            classify("""{"outbounds":[{"protocol":42}]}""").format
        )
    }
}
