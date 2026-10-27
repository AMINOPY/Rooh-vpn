package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineRegistryTest {
    @Test
    fun emptyRegistryDoesNotAdvertiseEnginesWithoutAdapters() {
        val registry = EngineRegistry()

        assertEquals(emptySet<EngineId>(), registry.availableEngines())
        EngineId.values().forEach { id -> assertNull(registry.backend(id)) }
    }

    @Test
    fun registryContainsOnlyExplicitlyRegisteredBackends() {
        val backend = object : EngineBackend {
            override val id = EngineId.WIREGUARD
            override val capabilities = EngineCapabilities(
                tunnelMode = TunnelMode.NATIVE_TUN,
                supportsIpv4 = true,
                supportsIpv6 = true,
                supportsDnsRouting = true,
                supportsPerAppRouting = false,
                supportedConfigFormats = setOf("wireguard")
            )

            override fun validate(configuration: ByteArray) = null
            override fun start(configuration: ByteArray) = null
            override fun status() = EngineStatus(id, EngineState.STOPPED)
            override fun stop() = null
        }
        val registry = EngineRegistry(listOf(backend))

        assertEquals(setOf(EngineId.WIREGUARD), registry.availableEngines())
        assertEquals(backend, registry.backend(EngineId.WIREGUARD))
        assertNull(registry.backend(EngineId.AETHER))
        assertNull(registry.backend(EngineId.XRAY))
        assertNull(registry.backend(EngineId.SING_BOX))
        assertNull(registry.backend(EngineId.PG_CORE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateEngineIdsAreRejected() {
        val backend = object : EngineBackend {
            override val id = EngineId.WIREGUARD
            override val capabilities = EngineCapabilities(
                tunnelMode = TunnelMode.NATIVE_TUN,
                supportsIpv4 = true,
                supportsIpv6 = true,
                supportsDnsRouting = true,
                supportsPerAppRouting = false,
                supportedConfigFormats = setOf("wireguard")
            )
            override fun validate(configuration: ByteArray) = null
            override fun start(configuration: ByteArray) = null
            override fun status() = EngineStatus(id, EngineState.STOPPED)
            override fun stop() = null
        }
        EngineRegistry(listOf(backend, backend))
    }
}
