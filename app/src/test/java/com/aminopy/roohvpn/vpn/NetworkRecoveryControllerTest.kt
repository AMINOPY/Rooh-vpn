package com.aminopy.roohvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
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
        val checked = CountDownLatch(1)
        val controller = NetworkRecoveryController(
            tunnel = object : NetworkRecoveryController.TunnelRecoveryPort {
                override fun isTunnelActive() = true
                override fun validateTunnelTraffic(): Boolean { checked.countDown(); return true }
                override fun reconnect(): Boolean { reconnects.incrementAndGet(); return true }
            },
            statusListener = {}
        )
        try {
            controller.onNetworkStateChanged(NetworkLifecycleMonitor.State.AVAILABLE_VALIDATED)
            assertTrue(checked.await(2, TimeUnit.SECONDS))
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
            controller.onNetworkStateChanged(NetworkLifecycleMonitor.State.AVAILABLE_UNVALIDATED)
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
            assertFalse(controller.status() == NetworkRecoveryController.Status.CHECKING_TUNNEL)
            assertEquals(0, reconnects.get())
        } finally {
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
