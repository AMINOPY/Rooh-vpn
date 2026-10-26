package com.aminopy.roohvpn.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerCandidateValidationTest {
    @Test
    fun acceptsDnsNameAndIpv4Literal() {
        assertEquals("vpn.example.org", candidate("vpn.example.org").host)
        assertEquals("192.0.2.10", candidate("192.0.2.10").host)
    }

    @Test
    fun acceptsIpv6Literal() {
        assertEquals("2001:db8::10", candidate("2001:db8::10").host)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUrlInsteadOfHost() {
        candidate("https://vpn.example.org/path")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsControlCharacters() {
        candidate("vpn.example.org\r\nHost: attacker.example")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedDnsLabels() {
        candidate("-bad.example.org")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsScopedIpv6Literal() {
        candidate("fe80::1%wlan0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathSeparatorsInServerId() {
        ServerCandidate("region/server", "vpn.example.org", 443, "EU", "wireguard")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedIdentifiers() {
        ServerCandidate("x".repeat(129), "vpn.example.org", 443, "EU", "wireguard")
    }

    private fun candidate(host: String) = ServerCandidate(
        id = "server-1",
        host = host,
        port = 443,
        region = "EU",
        engineId = "wireguard"
    )
}
