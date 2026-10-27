package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

class SingBoxProfilePreflightTest {
    private fun validate(json: String) = SingBoxProfilePreflight.validate(json.toByteArray(StandardCharsets.UTF_8))

    @Test fun acceptsStructurallyPlausibleOutbound() {
        assertNull(validate("""{"outbounds":[{"type":"vless","server":"example.invalid","server_port":443}]}"""))
    }

    @Test fun acceptsDirectOutboundWithoutServer() {
        assertNull(validate("""{"outbounds":[{"type":"direct"}]}"""))
    }

    @Test fun rejectsMalformedAndMissingOutbounds() {
        assertEquals("SING_BOX_CONFIG_INVALID", validate("{bad")?.code)
        assertEquals("SING_BOX_CONFIG_INVALID", validate("""{"inbounds":[]}""")?.code)
        assertEquals("SING_BOX_CONFIG_INVALID", validate("""{"outbounds":[]}""")?.code)
    }

    @Test fun rejectsInvalidServerPortAndMissingType() {
        assertEquals("SING_BOX_CONFIG_INVALID", validate("""{"outbounds":[{"type":"vless","server":"example.invalid","server_port":70000}]}""")?.code)
        assertEquals("SING_BOX_CONFIG_INVALID", validate("""{"outbounds":[{"server":"example.invalid","server_port":443}]}""")?.code)
    }

    @Test fun rejectsEmptyAndOversizedProfiles() {
        assertEquals("SING_BOX_CONFIG_INVALID", SingBoxProfilePreflight.validate(byteArrayOf())?.code)
        val oversized = ByteArray(1024 * 1024 + 1) { ' '.code.toByte() }
        assertEquals("SING_BOX_CONFIG_INVALID", SingBoxProfilePreflight.validate(oversized)?.code)
    }
}
