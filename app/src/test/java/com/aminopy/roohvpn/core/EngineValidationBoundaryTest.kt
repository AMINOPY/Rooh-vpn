package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EngineValidationBoundaryTest {
    @Test
    fun rejectedProfileNeverStartsTunnelAndIsCleared() {
        val backend = ValidationBackend()
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))
        val secretBuffer = byteArrayOf(11, 22, 33)

        val failure = coordinator.connect(EngineId.XRAY, secretBuffer)

        assertEquals("PROFILE_REJECTED", failure?.code)
        assertEquals(0, backend.startCalls)
        assertEquals(0, backend.stopCalls)
        assertNull(coordinator.activeEngine())
        assertFalse(secretBuffer.any { it.toInt() != 0 })
    }

    private class ValidationBackend : EngineBackend {
        override val id = EngineId.XRAY
        override val capabilities = EngineCapabilities(
            tunnelMode = TunnelMode.LOCAL_PROXY_WITH_TUN_BRIDGE,
            supportsIpv4 = true,
            supportsIpv6 = true,
            supportsDnsRouting = true,
            supportsPerAppRouting = false,
            supportedConfigFormats = setOf("test")
        )

        var startCalls = 0
        var stopCalls = 0

        override fun validate(configuration: ByteArray) = EngineFailure(
            code = "PROFILE_REJECTED",
            safeMessage = "The profile is invalid.",
            retryable = false
        )

        override fun start(configuration: ByteArray): EngineFailure? {
            startCalls++
            return null
        }

        override fun status() = EngineStatus(id, EngineState.STOPPED)

        override fun stop(): EngineFailure? {
            stopCalls++
            return null
        }
    }
}
