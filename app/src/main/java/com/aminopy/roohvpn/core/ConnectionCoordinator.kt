package com.aminopy.roohvpn.core

/**
 * Serializes engine lifecycle transitions and refuses to report success until
 * a backend reports RUNNING. Call this from a worker thread, never the main
 * thread, because native engine start/stop operations may block.
 *
 * The configuration byte array is treated as an ephemeral secret buffer and
 * zeroed before this method returns. Callers that retain a profile must pass a
 * separate temporary copy.
 */
class ConnectionCoordinator(private val registry: EngineRegistry) {
    private val lock = Any()
    private var activeBackend: EngineBackend? = null

    fun connect(engineId: EngineId, configuration: ByteArray): EngineFailure? = synchronized(lock) {
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

            backend.validate(configuration)?.let { return@synchronized it }

            backend.start(configuration)?.let { failure ->
                // Best-effort cleanup in case start partially allocated resources.
                backend.stop()
                return@synchronized failure
            }

            val status = backend.status()
            if (status.state != EngineState.RUNNING) {
                backend.stop()
                return@synchronized EngineFailure(
                    code = "ENGINE_NOT_RUNNING",
                    safeMessage = "The engine did not confirm that it is running.",
                    retryable = true
                )
            }

            activeBackend = backend
            null
        } finally {
            configuration.fill(0)
        }
    }

    fun disconnect(): EngineFailure? = synchronized(lock) {
        val backend = activeBackend ?: return@synchronized null
        activeBackend = null
        backend.stop()
    }

    fun activeEngine(): EngineId? = synchronized(lock) {
        activeBackend?.id
    }
}
