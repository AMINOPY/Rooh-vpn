package com.aminopy.roohvpn.core

/**
 * All engines must implement the same lifecycle boundary, but each engine
 * retains its own config schema and capability checks.
 *
 * Implementations must validate config before launching native code, redact
 * diagnostics, and clean up partial starts on every failure path.
 */
interface EngineBackend {
    val id: EngineId
    val capabilities: EngineCapabilities

    fun validate(configuration: ByteArray): EngineFailure?
    fun start(configuration: ByteArray): EngineFailure?

    /** Engines may override this overload to apply supported launch-time options. */
    fun validate(configuration: ByteArray, options: EngineLaunchOptions): EngineFailure? {
        if (options.hasXrayTransportOverrides()) {
            return EngineFailure(
                code = "ENGINE_OPTION_UNSUPPORTED",
                safeMessage = "This engine does not support the requested transport overrides.",
                retryable = false
            )
        }
        return validate(configuration)
    }

    /** Engines may override this overload to apply supported launch-time options. */
    fun start(configuration: ByteArray, options: EngineLaunchOptions): EngineFailure? {
        if (options.hasXrayTransportOverrides()) {
            configuration.fill(0)
            return EngineFailure(
                code = "ENGINE_OPTION_UNSUPPORTED",
                safeMessage = "This engine does not support the requested transport overrides.",
                retryable = false
            )
        }
        return start(configuration)
    }

    fun status(): EngineStatus
    fun stop(): EngineFailure?
}
