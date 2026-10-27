package com.aminopy.roohvpn.core

/**
 * In-process capacity reservation for NEW flows selected by PG CORE.
 *
 * Selection and reservation are intentionally separate: measurements can go
 * stale between them. Call acquire() immediately before handing a new flow to
 * an engine; a null result means refresh measurements and select again.
 * This allocator does not forward packets or implement a VPN tunnel.
 */
class PgCoreFusionAllocator {
    private val lock = Any()
    private val limits = mutableMapOf<String, Int>()
    private val active = mutableMapOf<String, Int>()

    /**
     * maxConcurrentFlows == 0 means unlimited. Updates never discard active
     * leases; a lowered limit simply prevents additional reservations.
     */
    fun configure(pathId: String, maxConcurrentFlows: Int): Boolean {
        if (pathId.isBlank() || maxConcurrentFlows < 0) return false
        synchronized(lock) {
            limits[pathId] = maxConcurrentFlows
            active.putIfAbsent(pathId, 0)
        }
        return true
    }

    fun acquire(pathId: String): Lease? {
        if (pathId.isBlank()) return null
        synchronized(lock) {
            val limit = limits[pathId] ?: return null
            val count = active[pathId] ?: 0
            if (limit != 0 && count >= limit) return null
            active[pathId] = count + 1
            return Lease(this, pathId)
        }
    }

    fun activeReservations(pathId: String): Int = synchronized(lock) {
        active[pathId] ?: 0
    }

    private fun release(pathId: String) {
        synchronized(lock) {
            val count = active[pathId] ?: return
            if (count <= 1) active[pathId] = 0 else active[pathId] = count - 1
        }
    }

    class Lease internal constructor(
        private val owner: PgCoreFusionAllocator,
        val pathId: String
    ) : AutoCloseable {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) owner.release(pathId)
        }
    }
}
