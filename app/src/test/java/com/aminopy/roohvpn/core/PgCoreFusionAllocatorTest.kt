package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class PgCoreFusionAllocatorTest {
    @Test
    fun capacityLimitRejectsAdditionalReservations() {
        val allocator = PgCoreFusionAllocator()
        assertTrue(allocator.configure("path-a", 1))
        val first = allocator.acquire("path-a")
        assertNotNull(first)
        assertNull(allocator.acquire("path-a"))
        assertEquals(1, allocator.activeReservations("path-a"))
        first!!.close()
        assertEquals(0, allocator.activeReservations("path-a"))
        assertNotNull(allocator.acquire("path-a"))
    }

    @Test
    fun leaseCloseIsIdempotent() {
        val allocator = PgCoreFusionAllocator()
        allocator.configure("path-a", 2)
        val lease = allocator.acquire("path-a")!!
        lease.close()
        lease.close()
        assertEquals(0, allocator.activeReservations("path-a"))
    }

    @Test
    fun unknownAndInvalidPathsFailClosed() {
        val allocator = PgCoreFusionAllocator()
        assertNull(allocator.acquire("unknown"))
        assertFalse(allocator.configure("", 1))
        assertFalse(allocator.configure("path-a", -1))
    }

    @Test
    fun unlimitedPathCanReserveMultipleFlows() {
        val allocator = PgCoreFusionAllocator()
        allocator.configure("path-a", 0)
        val first = allocator.acquire("path-a")
        val second = allocator.acquire("path-a")
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(2, allocator.activeReservations("path-a"))
        first!!.close()
        second!!.close()
    }

    @Test
    fun concurrentAcquireNeverExceedsConfiguredCapacity() {
        val allocator = PgCoreFusionAllocator()
        allocator.configure("path-a", 3)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val threads = (0 until 24).map {
            Thread {
                start.await()
                if (allocator.acquire("path-a") != null) successes.incrementAndGet()
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(2_000) }
        assertEquals(3, successes.get())
        assertEquals(3, allocator.activeReservations("path-a"))
    }
}
