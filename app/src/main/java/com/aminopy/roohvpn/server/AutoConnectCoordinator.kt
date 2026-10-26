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

        // Never let a faster but unregistered engine block a usable backend.
        val supportedCandidates = candidates.filter { candidate ->
            parseEngineId(candidate.engineId)?.let(connections::isEngineAvailable) == true
        }
        if (supportedCandidates.isEmpty()) {
            return AutoConnectResult(
                candidates.firstOrNull(),
                EngineFailure("UNSUPPORTED_ENGINE", "No discovered server uses an engine available in this build.", false)
            )
        }

        val rankedCandidates = try {
            selector.rank(supportedCandidates).take(MAX_CONNECTION_ATTEMPTS)
        } catch (_: Exception) {
            emptyList()
        }
        if (rankedCandidates.isEmpty()) {
            return AutoConnectResult(
                null,
                EngineFailure("NO_REACHABLE_SERVERS", "No available VPN server responded.", true)
            )
        }

        var lastCandidate: ServerCandidate? = null
        var lastFailure = EngineFailure(
            "CONNECTION_ATTEMPTS_EXHAUSTED",
            "No selected VPN server could establish a connection.",
            true
        )
        for (ranked in rankedCandidates) {
            lastCandidate = ranked.candidate
            val engineId = parseEngineId(ranked.candidate.engineId) ?: continue

            val configuration = try {
                profiles.fetchConfiguration(ranked.candidate)
            } catch (_: Exception) {
                lastFailure = EngineFailure(
                    "PROFILE_PROVISION_FAILED",
                    "Could not obtain a secure connection profile.",
                    true
                )
                continue
            }

            if (configuration.isEmpty() || configuration.size > MAX_CONFIGURATION_BYTES) {
                configuration.fill(0)
                lastFailure = EngineFailure(
                    "INVALID_PROFILE_SIZE",
                    "The secure connection profile was invalid.",
                    false
                )
                continue
            }

            val failure = try {
                connections.connect(engineId, configuration)
            } catch (_: Exception) {
                configuration.fill(0)
                EngineFailure(
                    "CONNECTION_COORDINATOR_FAILED",
                    "The VPN connection could not be started.",
                    true
                )
            }
            if (failure == null) return AutoConnectResult(ranked.candidate, null)

            lastFailure = failure
            // If cleanup could not be confirmed, do not attempt another engine
            // over a possibly live tunnel. ConnectionCoordinator reserves it.
            if (connections.activeEngine() != null) {
                return AutoConnectResult(ranked.candidate, failure)
            }
        }
        return AutoConnectResult(lastCandidate, lastFailure)
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
        const val MAX_CONNECTION_ATTEMPTS = 3
    }
}
