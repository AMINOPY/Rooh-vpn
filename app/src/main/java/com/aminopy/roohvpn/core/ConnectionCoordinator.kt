package com.aminopy.roohvpn.core

/**
 * Serializes engine lifecycle transitions and refuses to report success until
 * a backend reports RUNNING. Call this from a worker thread, never the main
 * thread, because native engine start/stop operations may block.
 *
 * The configuration byte array is an ephemeral secret buffer and is zeroed
 * before this method returns. Callers retaining a profile must pass a copy.
 */
class ConnectionCoordinator(private val registry: EngineRegistry) {
    private val lock = Any()
    private var activeBackend: EngineBackend? = null

    fun connect(engineId: EngineId, configuration: ByteArray): EngineFailure? = synchronized(lock) {
        var candidate: EngineBackend? = null
        try {
            if (activeBackend != null) {
                return@synchronized EngineFailure(
                    code = "ENGINE_ALREADY_ACTIVE",
                    safeMessage = "A VPN engine is already active.",
                    retryable = false
                )
            }

            val backend = registry.backend(engineId)
                ?: return@synchronized EngineFailure(
                    code = "ENGINE_UNAVAILABLE",
                    safeMessage = "This VPN engine is not integrated in this build yet.",
                    retryable = false
                )
            candidate = backend

            backend.validate(configuration)?.let { return@synchronized it }

            backend.start(configuration)?.let { failure ->
                cleanupAfterFailedStart(backend)
                return@synchronized failure
            }

            val status = backend.status()
            if (status.state != EngineState.RUNNING) {
                cleanupAfterFailedStart(backend)
                return@synchronized EngineFailure(
                    code = "ENGINE_NOT_RUNNING",
                    safeMessage = "The engine did not confirm that it is running.",
                    retryable = true
                )
            }

            activeBackend = backend
            null
        } catch (_: Exception) {
            // Native adapters are untrusted boundaries: exceptions must not
            // escape to UI or leave a partially started process running.
            candidate?.let(::cleanupAfterFailedStart)
            EngineFailure(
                code = "ENGINE_INTERNAL_ERROR",
                safeMessage = "The VPN engine failed unexpectedly.",
                retryable = true
            )
        } finally {
            configuration.fill(0)
        }
    }

    /**
     * A failed cleanup must reserve the backend so a second engine cannot
     * start over a possibly live tunnel. A null stop result is the adapter's
     * explicit confirmation that cleanup completed.
     */
    private fun cleanupAfterFailedStart(backend: EngineBackend) {
        val cleanupFailure = try {
            backend.stop()
        } catch (_: Exception) {
            EngineFailure(
                code = "ENGINE_CLEANUP_FAILED",
                safeMessage = "The VPN engine could not be cleaned up safely.",
                retryable = true
            )
        }
        if (cleanupFailure != null) activeBackend = backend
    }

    fun disconnect(): EngineFailure? = synchronized(lock) {
        val backend = activeBackend ?: return@synchronized null
        try {
            val failure = backend.stop()
            // Keep the backend marked active when stop is not confirmed. This
            // blocks a second engine from starting over a possibly live tunnel.
            if (failure == null) activeBackend = null
            failure
        } catch (_: Exception) {
            EngineFailure(
                code = "ENGINE_STOP_FAILED",
                safeMessage = "The VPN engine could not stop cleanly.",
                retryable = true
            )
        }
    }

    fun activeEngine(): EngineId? = synchronized(lock) {
        activeBackend?.id
    }
}
