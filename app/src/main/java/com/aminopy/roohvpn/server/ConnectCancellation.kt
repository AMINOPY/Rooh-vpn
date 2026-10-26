package com.aminopy.roohvpn.server

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cooperative cancellation for an auto-connect attempt.
 *
 * cancel() interrupts the worker so interruptible probes/backoff can stop.
 * Providers should also set bounded connect/read timeouts because not every
 * platform I/O implementation reacts immediately to thread interruption.
 */
class ConnectCancellation {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var runner: Thread? = null

    fun cancel() {
        cancelled.set(true)
        runner?.interrupt()
    }

    fun isCancelled(): Boolean =
        cancelled.get() || (runner === Thread.currentThread() && Thread.currentThread().isInterrupted)

    internal fun attachCurrentThread() {
        runner = Thread.currentThread()
        if (cancelled.get()) runner?.interrupt()
    }

    internal fun detachCurrentThread() {
        if (runner === Thread.currentThread()) runner = null
    }
}

/** Injectable so retry delays can be deterministic in unit tests. */
fun interface RetrySleeper {
    @Throws(InterruptedException::class)
    fun sleep(milliseconds: Long)
}
