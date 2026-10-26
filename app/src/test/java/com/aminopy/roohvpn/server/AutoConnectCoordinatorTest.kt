package com.aminopy.roohvpn.server

import com.aminopy.roohvpn.core.ConnectionCoordinator
import com.aminopy.roohvpn.core.EngineBackend
import com.aminopy.roohvpn.core.EngineCapabilities
import com.aminopy.roohvpn.core.EngineId
import com.aminopy.roohvpn.core.EngineRegistry
import com.aminopy.roohvpn.core.EngineState
import com.aminopy.roohvpn.core.EngineStatus
import com.aminopy.roohvpn.core.TunnelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectCoordinatorTest {
    private val server = ServerCandidate("test-eu", "vpn.example", 443, "EU", "xray")

    @Test
    fun oneTapFlowDiscoversSelectsProvisionsAndStartsBackend() {
        val backend = FakeBackend()
        val config = byteArrayOf(11, 12, 13)
        val coordinator = AutoConnectCoordinator(
            directory = ServerDirectory { listOf(server) },
            selector = ServerSelector(EndpointProbe { 18L }),
            profiles = ServerProfileProvider { config },
            connections = ConnectionCoordinator(EngineRegistry(listOf(backend)))
        )

        val result = coordinator.connect()

        assertTrue(result.connected)
        assertEquals(server.id, result.selectedServer?.id)
        assertNull(result.failure)
        assertEquals(EngineId.XRAY, backend.id)
        assertFalse(config.any { it.toInt() != 0 })
    }

    @Test
    fun emptyDirectoryDoesNotAttemptToConnect() {
        val backend = FakeBackend()
        val coordinator = AutoConnectCoordinator(
            directory = ServerDirectory { emptyList() },
            selector = ServerSelector(EndpointProbe { 18L }),
            profiles = ServerProfileProvider { byteArrayOf(1) },
            connections = ConnectionCoordinator(EngineRegistry(listOf(backend)))
        )

        val result = coordinator.connect()

        assertFalse(result.connected)
        assertEquals("NO_SERVERS", result.failure?.code)
        assertNull(result.selectedServer)
        assertEquals(0, backend.startCalls)
    }

    @Test
    fun oversizedDirectoryIsRejectedBeforeProbing() {
        var probeCalls = 0
        val candidates = (0..500).map { server.copy(id = "server-$it") }
        val coordinator = AutoConnectCoordinator(
            directory = ServerDirectory { candidates },
            selector = ServerSelector(EndpointProbe {
                probeCalls++
                18L
            }),
            profiles = ServerProfileProvider { byteArrayOf(1) },
            connections = ConnectionCoordinator(EngineRegistry(listOf(FakeBackend())))
        )

        val result = coordinator.connect()

        assertEquals("SERVER_DIRECTORY_LIMIT_EXCEEDED", result.failure?.code)
        assertEquals(0, probeCalls)
    }

    @Test
    fun invalidProfileIsClearedAndNeverStartsEngine() {
        val backend = FakeBackend()
        val oversizedConfig = ByteArray(1024 * 1024 + 1) { 7 }
        val coordinator = AutoConnectCoordinator(
            directory = ServerDirectory { listOf(server) },
            selector = ServerSelector(EndpointProbe { 18L }),
            profiles = ServerProfileProvider { oversizedConfig },
            connections = ConnectionCoordinator(EngineRegistry(listOf(backend)))
        )

        val result = coordinator.connect()

        assertEquals("INVALID_PROFILE_SIZE", result.failure?.code)
        assertTrue(oversizedConfig.all { it.toInt() == 0 })
        assertEquals(0, backend.startCalls)
    }

    @Test
    fun unsupportedEngineIsRejectedBeforeProfileProvisioning() {
        var provisionCalls = 0
        val unknown = server.copy(engineId = "unknown-engine")
        val coordinator = AutoConnectCoordinator(
            directory = ServerDirectory { listOf(unknown) },
            selector = ServerSelector(EndpointProbe { 18L }),
            profiles = ServerProfileProvider {
                provisionCalls += 1
                byteArrayOf(1)
            },
            connections = ConnectionCoordinator(EngineRegistry())
        )

        val result = coordinator.connect()

        assertEquals("UNSUPPORTED_ENGINE", result.failure?.code)
        assertEquals(0, provisionCalls)
    }

    private class FakeBackend : EngineBackend {
        override val id = EngineId.XRAY
        override val capabilities = EngineCapabilities(
            tunnelMode = TunnelMode.NATIVE_TUN,
            supportsIpv4 = true,
            supportsIpv6 = true,
            supportsDnsRouting = true,
            supportsPerAppRouting = false,
            supportedConfigFormats = setOf("test")
        )
        var startCalls = 0
        override fun validate(configuration: ByteArray) = null
        override fun start(configuration: ByteArray): com.aminopy.roohvpn.core.EngineFailure? {
            startCalls++
            return null
        }
        override fun status() = EngineStatus(id, EngineState.RUNNING)
        override fun stop() = null
    }
}
