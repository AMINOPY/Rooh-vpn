package com.aminopy.roohvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkRecoveryControllerTest {
    @Test
    fun noNetworkWaitsWithoutTryingToReconnect() {
        val reconnects = AtomicInteger()
        val statuses = mutableListOf<NetworkRecoveryController.Status>()
        val controller = NetworkRecoveryController(
            tunnel = fakeTunnel(reconnects, healthy = true),
            statusListener = { synchronized(statuses) { statuses += it } }
        )
        try {
            controller.onNetworkStateChanged(NetworkLifecycleMonitor.State.NO_DEFAULT_NETWORK)
            assertEquals(NetworkRecoveryController.Status.WAITING_FOR_NETWORK, controller.status())
            assertEquals(0, reconnects.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun healthyTunnelIsValidatedBeforeAnyReconnect() {
        val reconnects = AtomicInteger()
        val recovered = CountDownLatch(1)
        val controller = NetworkRecoveryController(
            tunnel = object : NetworkRecoveryController.TunnelRecoveryPort {
                override fun isTunnelActive() = true
                override fun validateTunnelTraffic() = true
                override fun reconnect(): Boolean { reconnects.incrementAndGet(); return true }
            },
            statusListener = { if (it == NetworkRecoveryController.Status.RECOVERED) recovered.countDown() }
        )
        try {
            controller.onNetworkStateChanged(NetworkLifecycleMonitor.State.AVAILABLE_VALIDATED)
            assertTrue(recovered.await(2, TimeUnit.SECONDS))
            assertEquals(0, reconnects.get())
            assertEquals(NetworkRecoveryController.Status.RECOVERED, controller.status())
        } finally {
            controller.close()
        }
    }

    @Test
    fun unhealthyTunnelReconnectsAndRequiresPostReconnectTrafficValidation() {
        val reconnects = AtomicInteger()
        val checks = AtomicInteger()
        val finished = CountDownLatch(1)
        val controller = NetworkRecoveryController(
            tunnel = object : NetworkRecoveryController.TunnelRecoveryPort {
                override fun isTunnelActive() = true
                override fun validateTunnelTraffic(): Boolean = checks.incrementAndGet() >= 2
                override fun reconnect(): Boolean { reconnects.incrementAndGet(); return true }
            },
            statusListener = { if (it == NetworkRecoveryController.Status.RECOVERED) finished.countDown() }
        )
        try {
            controller.onNetworkStateChanged(NetworkLifecycleMonitor.State.AVAILABLE_VALIDATED)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, reconnects.get())
            assertEquals(2, checks.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedReconnectDoesNotReportRecovered() {
        val finished = CountDownLatch(1)
        val controller = NetworkRecoveryController(
            tunnel = object : NetworkRecoveryController.TunnelRecoveryPort {
                override fun isTunnelActive() = true
                override fun validateTunnelTraffic() = false
                override fun reconnect() = false
            },
            statusListener = { if (it == NetworkRecoveryController.Status.RECOVERY_FAILED) finished.countDown() }
        )
        try {
            controller.onNetworkStateChanged(NetworkLifecycleMonitor.State.AVAILABLE_VALIDATED)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(NetworkRecoveryController.Status.RECOVERY_FAILED, controller.status())
        } finally {
            controller.close()
        }
    }

    @Test
    fun inactiveTunnelDoesNotScheduleRecovery() {
        val reconnects = AtomicInteger()
        val controller = NetworkRecoveryController(
            tunnel = object : NetworkRecoveryController.TunnelRecoveryPort {
                override fun isTunnelActive() = false
                override fun validateTunnelTraffic() = false
                override fun reconnect(): Boolean { reconnects.incrementAndGet(); return true }
            },
            statusListener = {}
        )
        try {
            controller.onNetworkStateChanged(NetworkLifecycleMonitor.State.AVAILABLE_VALIDATED)
            Thread.sleep(30)
            assertFalse(controller.status() == NetworkRecoveryController.Status.CHECKING_TUNNEL)
            assertEquals(0, reconnects.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun nativeLinkageFailureReportsRecoveryFailureInsteadOfStickingChecking() {
        val finished = CountDownLatch(1)
        val controller = NetworkRecoveryController(
            tunnel = object : NetworkRecoveryController.TunnelRecoveryPort {
                override fun isTunnelActive() = true
                override fun validateTunnelTraffic(): Boolean = throw UnsatisfiedLinkError("native bridge unavailable")
                override fun reconnect() = false
            },
            statusListener = { if (it == NetworkRecoveryController.Status.RECOVERY_FAILED) finished.countDown() }
        )
        try {
            controller.onNetworkStateChanged(NetworkLifecycleMonitor.State.AVAILABLE_VALIDATED)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(NetworkRecoveryController.Status.RECOVERY_FAILED, controller.status())
        } finally {
            controller.close()
        }
    }

    @Test
    fun networkLossDuringHealthCheckPreventsReconnect() {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        val reconnects = AtomicInteger()
        val controller = NetworkRecoveryController(
            tunnel = object : NetworkRecoveryController.TunnelRecoveryPort {
                override fun isTunnelActive() = true
                override fun validateTunnelTraffic(): Boolean {
                    checkStarted.countDown()
                    releaseCheck.await(2, TimeUnit.SECONDS)
                    return false
                }
                override fun reconnect(): Boolean {
                    reconnects.incrementAndGet()
                    return true
                }
            },
            statusListener = {}
        )
        try {
            controller.onNetworkStateChanged(NetworkLifecycleMonitor.State.AVAILABLE_VALIDATED)
            assertTrue(checkStarted.await(2, TimeUnit.SECONDS))
            controller.onNetworkStateChanged(NetworkLifecycleMonitor.State.NO_DEFAULT_NETWORK)
            releaseCheck.countDown()
            Thread.sleep(80)
            assertEquals(0, reconnects.get())
            assertEquals(NetworkRecoveryController.Status.WAITING_FOR_NETWORK, controller.status())
        } finally {
            releaseCheck.countDown()
            controller.close()
        }
    }

    private fun fakeTunnel(
        reconnects: AtomicInteger,
        healthy: Boolean
    ) = object : NetworkRecoveryController.TunnelRecoveryPort {
        override fun isTunnelActive() = true
        override fun validateTunnelTraffic() = healthy
        override fun reconnect(): Boolean { reconnects.incrementAndGet(); return true }
    }
}
