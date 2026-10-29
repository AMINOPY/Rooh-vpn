package com.aminopy.roohvpn.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WireGuardProfileParserTest {
    @Test
    fun rejectsEmptyConfiguration() {
        assertNull(WireGuardProfileParser.parse(byteArrayOf()))
    }

    @Test
    fun rejectsMalformedConfiguration() {
        assertNull(WireGuardProfileParser.parse("[Interface".toByteArray()))
    }

    @Test
    fun rejectsInterfaceWithoutPeers() {
        val profile = """
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.0.0.2/32
        """.trimIndent()
        assertNull(WireGuardProfileParser.parse(profile.toByteArray()))
    }

    @Test
    fun rejectsOversizedConfiguration() {
        assertNull(WireGuardProfileParser.parse(ByteArray(64 * 1024 + 1)))
    }

    @Test
    fun acceptsWellFormedWireGuardClientProfile() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val profile = """
            [Interface]
            PrivateKey = $key
            Address = 10.0.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = $key
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 198.51.100.10:51820
        """.trimIndent()

        assertNotNull(WireGuardProfileParser.parse(profile.toByteArray()))
    }
}
