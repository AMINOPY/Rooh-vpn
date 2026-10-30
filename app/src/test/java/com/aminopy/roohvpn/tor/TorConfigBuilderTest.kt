package com.aminopy.roohvpn.tor

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TorConfigBuilderTest {
    @Rule @JvmField
    val temporary = TemporaryFolder()

    @Test
    fun directTorConfigBindsSocksOnlyToLoopback() {
        val native = temporary.newFolder("native")
        val data = temporary.newFolder("data")
        val tor = temporary.newFolder("tor")
        val config = TorConfigBuilder.build(TorSettings(), native, data, tor)

        assertTrue(config.contains("SocksPort 127.0.0.1:9250"))
        assertTrue(config.contains("UseBridges 0"))
        assertFalse(config.contains("SocksPort 0.0.0.0"))
    }

    @Test
    fun geoIpFilesAreReferencedOnlyWhenPresent() {
        val native = temporary.newFolder("native")
        val data = temporary.newFolder("data")
        val tor = temporary.newFolder("tor")
        File(tor, "geoip").writeText("geoip")
        File(tor, "geoip6").writeText("geoip6")
        val config = TorConfigBuilder.build(TorSettings(), native, data, tor)

        assertTrue(config.contains("GeoIPFile ${File(tor, "geoip").absolutePath}"))
        assertTrue(config.contains("GeoIPv6File ${File(tor, "geoip6").absolutePath}"))
        assertTrue(config.contains("VirtualAddrNetworkIPv6 [FC00::]/7"))
    }

    @Test
    fun snowflakeConfigUsesPackagedTransportAndDefaultAnchors() {
        val native = temporary.newFolder("native")
        File(native, "libsnowflake.so").apply {
            createNewFile()
            setExecutable(true)
        }
        val data = temporary.newFolder("data")
        val tor = temporary.newFolder("tor")
        val settings = TorSettings(bridgeMode = "default", bridgeTransport = "snowflake")
        val config = TorConfigBuilder.build(settings, native, data, tor)

        assertTrue(config.contains("ClientTransportPlugin snowflake exec"))
        assertTrue(config.contains("Bridge snowflake 192.0.2.3:80"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nodeSelectorsCannotInjectTorrcDirectives() {
        val native = temporary.newFolder("native")
        val data = temporary.newFolder("data")
        val tor = temporary.newFolder("tor")
        TorConfigBuilder.build(TorSettings(entryNodes = "US\nSocksPort 0.0.0.0:1"), native, data, tor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun customBridgeCannotInjectAdditionalDirectives() {
        val native = temporary.newFolder("native")
        File(native, "libobfs4proxy.so").apply {
            createNewFile()
            setExecutable(true)
        }
        val data = temporary.newFolder("data")
        val tor = temporary.newFolder("tor")
        val settings = TorSettings(bridgeMode = "own", ownBridges = "obfs4 127.0.0.1:443\nSocksPort 0.0.0.0:1")
        TorConfigBuilder.build(settings, native, data, tor)
    }
}
