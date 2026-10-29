package com.aminopy.roohvpn.core

/**
 * Serializes engine lifecycle transitions without holding the coordinator lock
 * while native start/stop calls run. UI state reads therefore never wait for a
 * potentially slow JNI operation.
 *
 * The configuration byte array is ephemeral and is zeroed before this method
 * returns. Callers retaining a profile must pass a copy.
 */
class ConnectionCoordinator(private val registry: EngineRegistry) {
    private val lock = Any()

    @Volatile private var activeBackend: EngineBackend? = null
    @Volatile private var reservedBackend: EngineBackend? = null
    @Volatile private var operationInProgress = false

    fun connect(engineId: EngineId, configuration: ByteArray): EngineFailure? =
        connect(engineId, configuration, EngineLaunchOptions())

    fun connect(
        engineId: EngineId,
        configuration: ByteArray,
        options: EngineLaunchOptions
    ): EngineFailure? {
        val backend = synchronized(lock) {
            if (operationInProgress || activeBackend != null || reservedBackend != null) {
                configuration.fill(0)
                return EngineFailure(
                    code = "ENGINE_ALREADY_ACTIVE",
                    safeMessage = "A VPN engine is already active or changing state.",
                    retryable = false
                )
            }

            val selected = registry.backend(engineId)
                ?: run {
                    configuration.fill(0)
                    return EngineFailure(
                        code = "ENGINE_UNAVAILABLE",
                        safeMessage = "This VPN engine is not integrated in this build yet.",
                        retryable = false
                    )
                }
            reservedBackend = selected
            operationInProgress = true
            selected
        }

        try {
            backend.validate(configuration, options)?.let { return it }

            backend.start(configuration, options)?.let { failure ->
                cleanupAfterFailedStart(backend)
                return failure
            }

            val status = backend.status()
            if (status.engineId != engineId) {
                cleanupAfterFailedStart(backend)
                return EngineFailure(
                    code = "ENGINE_STATUS_MISMATCH",
                    safeMessage = "The engine returned a status for a different engine.",
                    retryable = false
                )
            }
            if (status.state != EngineState.RUNNING) {
                cleanupAfterFailedStart(backend)
                return EngineFailure(
                    code = "ENGINE_NOT_RUNNING",
                    safeMessage = "The engine did not confirm that it is running.",
                    retryable = true
                )
            }

            activeBackend = backend
            return null
        } catch (_: Exception) {
            cleanupAfterFailedStart(backend)
            return EngineFailure(
                code = "ENGINE_INTERNAL_ERROR",
                safeMessage = "The VPN engine failed unexpectedly.",
                retryable = true
            )
        } catch (_: LinkageError) {
            cleanupAfterFailedStart(backend)
            return EngineFailure(
                code = "ENGINE_NATIVE_UNAVAILABLE",
                safeMessage = "The VPN engine's native component is unavailable or incompatible.",
                retryable = false
            )
        } finally {
            configuration.fill(0)
            synchronized(lock) {
                if (reservedBackend === backend) reservedBackend = null
                operationInProgress = false
            }
        }
    }

    /**
     * A failed cleanup must reserve the backend so another engine cannot start
     * over a possibly live tunnel. Null from stop explicitly confirms cleanup.
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
        } catch (_: LinkageError) {
            EngineFailure(
                code = "ENGINE_CLEANUP_FAILED",
                safeMessage = "The VPN engine could not be cleaned up safely.",
                retryable = true
            )
        }
        if (cleanupFailure != null) activeBackend = backend
    }

    fun disconnect(): EngineFailure? {
        val backend = synchronized(lock) {
            if (operationInProgress) {
                return EngineFailure(
                    code = "ENGINE_OPERATION_IN_PROGRESS",
                    safeMessage = "The VPN engine is already changing state.",
                    retryable = true
                )
            }
            val current = activeBackend ?: return null
            operationInProgress = true
            current
        }

        try {
            val failure = backend.stop()
            // Retain active state unless stop is explicitly confirmed.
            if (failure == null) activeBackend = null
            return failure
        } catch (_: Exception) {
            return EngineFailure(
                code = "ENGINE_STOP_FAILED",
                safeMessage = "The VPN engine could not stop cleanly.",
                retryable = true
            )
        } catch (_: LinkageError) {
            return EngineFailure(
                code = "ENGINE_STOP_FAILED",
                safeMessage = "The VPN engine could not stop cleanly.",
                retryable = true
            )
        } finally {
            synchronized(lock) { operationInProgress = false }
        }
    }

    fun isEngineAvailable(engineId: EngineId): Boolean = registry.backend(engineId) != null

    /** A lock-free read suitable for UI rendering. */
    fun activeEngine(): EngineId? = activeBackend?.id

    fun isOperationInProgress(): Boolean = operationInProgress
}
