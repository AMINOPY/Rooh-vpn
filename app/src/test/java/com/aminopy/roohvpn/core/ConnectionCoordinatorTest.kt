package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConnectionCoordinatorTest {
    @Test(expected = IllegalArgumentException::class)
    fun duplicateEngineRegistrationsAreRejected() {
        EngineRegistry(listOf(FakeBackend(), FakeBackend()))
    }

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
    fun failedStopKeepsEngineMarkedActiveToPreventOverlappingTunnels() {
        val backend = FakeBackend(stopFailure = EngineFailure("STOP_FAILED", "Stop failed.", true))
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))

        assertNull(coordinator.connect(EngineId.XRAY, byteArrayOf(1)))
        assertEquals("STOP_FAILED", coordinator.disconnect()?.code)
        assertEquals(EngineId.XRAY, coordinator.activeEngine())
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
    fun failedStartCleanupFailureKeepsBackendReserved() {
        val backend = FakeBackend(
            startFailure = EngineFailure("START_FAILED", "Could not start engine.", true),
            stopFailure = EngineFailure("STOP_FAILED", "Stop could not be confirmed.", true)
        )
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))

        val firstFailure = coordinator.connect(EngineId.XRAY, byteArrayOf(4))
        val secondFailure = coordinator.connect(EngineId.XRAY, byteArrayOf(5))

        assertEquals("START_FAILED", firstFailure?.code)
        assertEquals(EngineId.XRAY, coordinator.activeEngine())
        assertEquals("ENGINE_ALREADY_ACTIVE", secondFailure?.code)
        assertEquals(1, backend.stopCalls)
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
    fun nativeLinkageFailureIsRedactedAndTriggersCleanup() {
        val backend = FakeBackend(throwLinkageErrorOnStart = true)
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))
        val config = byteArrayOf(9, 8, 7)

        val failure = coordinator.connect(EngineId.XRAY, config)

        assertEquals("ENGINE_NATIVE_UNAVAILABLE", failure?.code)
        assertEquals(1, backend.stopCalls)
        assertFalse(config.any { it.toInt() != 0 })
        assertNull(coordinator.activeEngine())
    }

    @Test
    fun statusFromAnotherEngineIsRejectedAndCleanedUp() {
        val backend = FakeBackend(statusEngineId = EngineId.SING_BOX)
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))

        val failure = coordinator.connect(EngineId.XRAY, byteArrayOf(1))

        assertEquals("ENGINE_STATUS_MISMATCH", failure?.code)
        assertEquals(1, backend.stopCalls)
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


    @Test
    fun uiStateReadDoesNotWaitForSlowNativeStart() {
        val enteredStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val backend = object : EngineBackend {
            override val id = EngineId.WIREGUARD
            override val capabilities = EngineCapabilities(
                tunnelMode = TunnelMode.NATIVE_TUN,
                supportsIpv4 = true,
                supportsIpv6 = true,
                supportsDnsRouting = true,
                supportsPerAppRouting = false,
                supportedConfigFormats = setOf("test")
            )
            override fun validate(configuration: ByteArray): EngineFailure? = null
            override fun start(configuration: ByteArray): EngineFailure? {
                enteredStart.countDown()
                check(releaseStart.await(3, TimeUnit.SECONDS))
                return null
            }
            override fun status() = EngineStatus(id, EngineState.RUNNING)
            override fun stop(): EngineFailure? = null
        }
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))
        val result = AtomicReference<EngineFailure?>()
        val connectThread = Thread { result.set(coordinator.connect(EngineId.WIREGUARD, byteArrayOf(1))) }

        connectThread.start()
        assertEquals(true, enteredStart.await(1, TimeUnit.SECONDS))
        // These are the reads the Activity performs while native start is in flight.
        assertNull(coordinator.activeEngine())
        assertEquals(true, coordinator.isOperationInProgress())
        releaseStart.countDown()
        connectThread.join(2_000)

        assertEquals(false, connectThread.isAlive)
        assertNull(result.get())
        assertEquals(EngineId.WIREGUARD, coordinator.activeEngine())
    }

    @Test
    fun secondConnectIsRejectedWhileFirstBackendIsStarting() {
        val enteredStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val backend = object : EngineBackend {
            override val id = EngineId.WIREGUARD
            override val capabilities = EngineCapabilities(
                tunnelMode = TunnelMode.NATIVE_TUN,
                supportsIpv4 = true,
                supportsIpv6 = true,
                supportsDnsRouting = true,
                supportsPerAppRouting = false,
                supportedConfigFormats = setOf("test")
            )
            override fun validate(configuration: ByteArray): EngineFailure? = null
            override fun start(configuration: ByteArray): EngineFailure? {
                enteredStart.countDown()
                check(releaseStart.await(3, TimeUnit.SECONDS))
                return null
            }
            override fun status() = EngineStatus(id, EngineState.RUNNING)
            override fun stop(): EngineFailure? = null
        }
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))
        val firstResult = AtomicReference<EngineFailure?>()
        val connectThread = Thread { firstResult.set(coordinator.connect(EngineId.WIREGUARD, byteArrayOf(1))) }

        connectThread.start()
        assertEquals(true, enteredStart.await(1, TimeUnit.SECONDS))
        val secondConfig = byteArrayOf(2)
        val secondFailure = coordinator.connect(EngineId.WIREGUARD, secondConfig)
        assertEquals("ENGINE_ALREADY_ACTIVE", secondFailure?.code)
        assertFalse(secondConfig.any { it.toInt() != 0 })

        releaseStart.countDown()
        connectThread.join(2_000)
        assertEquals(false, connectThread.isAlive)
        assertNull(firstResult.get())
    }


    @Test
    fun nativeStatusFailureIsRedactedAndTriggersCleanup() {
        val backend = FakeBackend(throwLinkageErrorOnStatus = true)
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))

        val failure = coordinator.connect(EngineId.XRAY, byteArrayOf(1, 2))

        assertEquals("ENGINE_NATIVE_UNAVAILABLE", failure?.code)
        assertEquals(1, backend.stopCalls)
        assertNull(coordinator.activeEngine())
    }

    @Test
    fun nativeStopFailureKeepsBackendReserved() {
        val backend = FakeBackend(throwLinkageErrorOnStop = true)
        val coordinator = ConnectionCoordinator(EngineRegistry(listOf(backend)))

        assertNull(coordinator.connect(EngineId.XRAY, byteArrayOf(1)))
        val failure = coordinator.disconnect()

        assertEquals("ENGINE_STOP_FAILED", failure?.code)
        assertEquals(EngineId.XRAY, coordinator.activeEngine())
    }

    private class FakeBackend(
        private val stateAfterStart: EngineState = EngineState.RUNNING,
        private val statusEngineId: EngineId = EngineId.XRAY,
        private val startFailure: EngineFailure? = null,
        private val throwOnStart: Boolean = false,
        private val throwLinkageErrorOnStart: Boolean = false,
        private val throwLinkageErrorOnStatus: Boolean = false,
        private val throwLinkageErrorOnStop: Boolean = false,
        private val stopFailure: EngineFailure? = null
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
            if (throwLinkageErrorOnStart) throw UnsatisfiedLinkError("native library missing")
            return startFailure
        }
        override fun status(): EngineStatus {
            if (throwLinkageErrorOnStatus) throw UnsatisfiedLinkError("native status unavailable")
            return EngineStatus(statusEngineId, stateAfterStart)
        }
        override fun stop(): EngineFailure? {
            stopCalls += 1
            if (throwLinkageErrorOnStop) throw UnsatisfiedLinkError("native stop unavailable")
            return stopFailure
        }
    }
}
