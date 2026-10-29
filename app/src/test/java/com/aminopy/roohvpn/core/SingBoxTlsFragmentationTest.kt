package com.aminopy.roohvpn.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SingBoxTlsFragmentationTest {
    @Test fun enablesRecordFragmentationOnlyForEnabledTlsOutbounds() {
        val original = """{"outbounds":[
            {"type":"vless","server":"one.example","server_port":443,"tls":{"enabled":true,"server_name":"one.example"}},
            {"type":"vless","server":"two.example","server_port":443,"tls":{"enabled":false}},
            {"type":"direct"}
        ]}""".toByteArray(StandardCharsets.UTF_8)
        val originalCopy = original.copyOf()

        val result = SingBoxTlsFragmentation.enableRecordFragmentation(original)

        assertNotNull(result)
        assertEquals(1, result!!.modifiedOutbounds)
        assertTrue(original.contentEquals(originalCopy))
        val outbounds = JSONObject(String(result.configuration, StandardCharsets.UTF_8)).getJSONArray("outbounds")
        assertTrue(outbounds.getJSONObject(0).getJSONObject("tls").getBoolean("record_fragment"))
        assertFalse(outbounds.getJSONObject(1).getJSONObject("tls").has("record_fragment"))
        assertFalse(outbounds.getJSONObject(2).has("tls"))
    }

    @Test fun returnsNullWhenNoEnabledTlsOutboundExists() {
        val json = """{"outbounds":[{"type":"vless","server":"example.invalid","server_port":443}]}"""
        assertNull(SingBoxTlsFragmentation.enableRecordFragmentation(json.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test fun rejectsMalformedUtf8AndOversizedInput() {
        assertNull(SingBoxTlsFragmentation.enableRecordFragmentation(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)))
        assertNull(SingBoxTlsFragmentation.enableRecordFragmentation(ByteArray(1024 * 1024 + 1)))
    }
}
