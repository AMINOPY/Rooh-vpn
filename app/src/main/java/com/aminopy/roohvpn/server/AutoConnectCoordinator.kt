package com.aminopy.roohvpn.server

import com.aminopy.roohvpn.core.ConnectionCoordinator
import com.aminopy.roohvpn.core.EngineFailure
import com.aminopy.roohvpn.core.EngineId

/**
 * Fetches a short-lived, authorized engine profile after server selection.
 * Implementations must use HTTPS and must never persist/log returned secrets.
 */
fun interface ServerProfileProvider {
    fun fetchConfiguration(candidate: ServerCandidate): ByteArray
}

data class AutoConnectResult(
    val selectedServer: ServerCandidate?,
    val failure: EngineFailure?
) {
    val connected: Boolean get() = selectedServer != null && failure == null
}

/**
 * Core no-manual-config flow. This orchestrates trusted discovery, reachability
 * ranking, short-lived profile provisioning, and an already registered engine.
 *
 * This method performs blocking I/O; call it from a worker thread. It cannot
 * create free servers or bypass provider authorization, and it reports success
 * only when ConnectionCoordinator receives a RUNNING status from a backend.
 */
class AutoConnectCoordinator(
    private val directory: ServerDirectory,
    private val selector: ServerSelector,
    private val profiles: ServerProfileProvider,
    private val connections: ConnectionCoordinator
) {
    fun connect(): AutoConnectResult {
        val candidates = try {
            directory.discover()
        } catch (_: Exception) {
            return AutoConnectResult(
                null,
                EngineFailure("SERVER_DISCOVERY_FAILED", "Could not discover VPN servers.", true)
            )
        }

        if (candidates.size > MAX_CANDIDATES) {
            return AutoConnectResult(
                null,
                EngineFailure("SERVER_DIRECTORY_LIMIT_EXCEEDED", "The server list exceeded the safety limit.", true)
            )
        }
        if (candidates.isEmpty()) {
            return AutoConnectResult(
                null,
                EngineFailure("NO_SERVERS", "No VPN servers are currently available.", true)
            )
        }

        val ranked = try {
            selector.select(candidates)
        } catch (_: Exception) {
            null
        } ?: return AutoConnectResult(
            null,
            EngineFailure("NO_REACHABLE_SERVERS", "No available VPN server responded.", true)
        )

        val engineId = parseEngineId(ranked.candidate.engineId)
            ?: return AutoConnectResult(
                ranked.candidate,
                EngineFailure("UNSUPPORTED_ENGINE", "The selected server uses an unsupported VPN engine.", false)
            )

        val configuration = try {
            profiles.fetchConfiguration(ranked.candidate)
        } catch (_: Exception) {
            return AutoConnectResult(
                ranked.candidate,
                EngineFailure("PROFILE_PROVISION_FAILED", "Could not obtain a secure connection profile.", true)
            )
        }

        if (configuration.isEmpty() || configuration.size > MAX_CONFIGURATION_BYTES) {
            configuration.fill(0)
            return AutoConnectResult(
                ranked.candidate,
                EngineFailure("INVALID_PROFILE_SIZE", "The secure connection profile was invalid.", false)
            )
        }

        val failure = try {
            connections.connect(engineId, configuration)
        } catch (_: Exception) {
            // ConnectionCoordinator also clears this buffer in its finally
            // block; repeat defensively if a future implementation throws.
            configuration.fill(0)
            EngineFailure("CONNECTION_COORDINATOR_FAILED", "The VPN connection could not be started.", true)
        }
        return AutoConnectResult(ranked.candidate, failure)
    }

    private fun parseEngineId(value: String): EngineId? = when (value.lowercase()) {
        "xray" -> EngineId.XRAY
        "aether" -> EngineId.AETHER
        "sing-box", "sing_box", "singbox" -> EngineId.SING_BOX
        "wireguard" -> EngineId.WIREGUARD
        else -> null
    }

    private companion object {
        const val MAX_CANDIDATES = 500
        const val MAX_CONFIGURATION_BYTES = 1024 * 1024
    }
}
