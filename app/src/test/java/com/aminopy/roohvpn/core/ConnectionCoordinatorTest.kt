package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ConnectionCoordinatorTest {
    @Test
    fun unavailableEngineFailsClosedAndClearsTemporaryConfig() {
        val coordinator = ConnectionCoordinator(EngineRegistry())
        val config = byteArrayOf(1, 2, 3)

        val failure = coordinator.connect(EngineId.XRAY, config)

        assertEquals("ENGINE_UNAVAILABLE", failure?.code)
        assertNull(coordinator.activeEngine())
        assertFalse(config.any { it.toInt() != 0 })
    }

    @Test
    fun reportsConnectedOnlyWhenBackendConfirmsRunning() {
        val backend = FakeBackend(stateAfterStart = EngineState.RUNNING)
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))

        val failure = coordinator.connect(EngineId.XRAY, byteArrayOf(7, 8))

        assertNull(failure)
        assertEquals(EngineId.XRAY, coordinator.activeEngine())
        assertNull(coordinator.disconnect())
        assertNull(coordinator.activeEngine())
    }

    @Test
    fun partialStartFailureTriggersCleanup() {
        val backend = FakeBackend(startFailure = EngineFailure("START_FAILED", "Could not start engine.", true))
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))

        val failure = coordinator.connect(EngineId.XRAY, byteArrayOf(4))

        assertEquals("START_FAILED", failure?.code)
        assertEquals(1, backend.stopCalls)
        assertNull(coordinator.activeEngine())
    }

    @Test
    fun adapterExceptionIsRedactedAndTriggersCleanup() {
        val backend = FakeBackend(throwOnStart = true)
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))
        val config = byteArrayOf(9, 8, 7)

        val failure = coordinator.connect(EngineId.XRAY, config)

        assertEquals("ENGINE_INTERNAL_ERROR", failure?.code)
        assertEquals(1, backend.stopCalls)
        assertFalse(config.any { it.toInt() != 0 })
        assertNull(coordinator.activeEngine())
    }

    @Test
    fun nonRunningStatusIsNotReportedAsSuccess() {
        val backend = FakeBackend(stateAfterStart = EngineState.STARTING)
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))

        val failure = coordinator.connect(EngineId.XRAY, byteArrayOf(1))

        assertNotNull(failure)
        assertEquals("ENGINE_NOT_RUNNING", failure?.code)
        assertEquals(1, backend.stopCalls)
        assertNull(coordinator.activeEngine())
    }

    private class FakeBackend(
        private val stateAfterStart: EngineState = EngineState.RUNNING,
        private val startFailure: EngineFailure? = null,
        private val throwOnStart: Boolean = false
    ) : EngineBackend {
        override val id = EngineId.XRAY
        override val capabilities = EngineCapabilities(
            tunnelMode = TunnelMode.NATIVE_TUN,
            supportsIpv4 = true,
            supportsIpv6 = false,
            supportsDnsRouting = true,
            supportsPerAppRouting = false,
            supportedConfigFormats = setOf("test")
        )
        var stopCalls = 0

        override fun validate(configuration: ByteArray): EngineFailure? = null
        override fun start(configuration: ByteArray): EngineFailure? {
            if (throwOnStart) error("simulated native adapter exception")
            return startFailure
        }
        override fun status() = EngineStatus(id, stateAfterStart)
        override fun stop(): EngineFailure? {
            stopCalls += 1
            return null
        }
    }
}
