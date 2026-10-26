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
    fun status(): EngineStatus
    fun stop(): EngineFailure?
}
