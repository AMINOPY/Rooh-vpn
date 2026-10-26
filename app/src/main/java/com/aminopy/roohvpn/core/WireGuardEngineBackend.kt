package com.aminopy.roohvpn.core

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

/**
 * WireGuard adapter backed by the official Android tunnel library.
 *
 * Configuration must be a short-lived, authorized WireGuard wg-quick profile.
 * Do not log it or persist it through this adapter. The library necessarily
 * parses key material into JVM objects; callers should minimize its lifetime.
 */
class WireGuardEngineBackend(context: Context) : EngineBackend {
    private val backend = GoBackend(context.applicationContext)
    private val tunnel = object : Tunnel {
        @Volatile
        private var lastState = Tunnel.State.DOWN

        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            lastState = newState
        }

        fun lastKnownState(): Tunnel.State = lastState
    }

    override val id: EngineId = EngineId.WIREGUARD

    override val capabilities = EngineCapabilities(
        tunnelMode = TunnelMode.NATIVE_TUN,
        supportsIpv4 = true,
        supportsIpv6 = true,
        supportsDnsRouting = true,
        supportsPerAppRouting = true,
        supportedConfigFormats = setOf("wireguard", "wg-quick")
    )

    @Synchronized
    override fun validate(configuration: ByteArray): EngineFailure? {
        if (configuration.isEmpty() || configuration.size > MAX_CONFIG_BYTES) {
            return invalidConfiguration()
        }
        return if (WireGuardProfileParser.parse(configuration) != null) null else invalidConfiguration()
    }

    @Synchronized
    override fun start(configuration: ByteArray): EngineFailure? {
        if (configuration.isEmpty() || configuration.size > MAX_CONFIG_BYTES) {
            return invalidConfiguration()
        }
        val parsed = WireGuardProfileParser.parse(configuration) ?: return invalidConfiguration()

        return try {
            val state = backend.setState(tunnel, Tunnel.State.UP, parsed)
            if (state == Tunnel.State.UP) null else EngineFailure(
                code = "WIREGUARD_NOT_RUNNING",
                safeMessage = "WireGuard did not confirm that the tunnel is running.",
                retryable = true
            )
        } catch (_: Exception) {
            EngineFailure(
                code = "WIREGUARD_START_FAILED",
                safeMessage = "WireGuard could not start. Check VPN permission and the connection profile.",
                retryable = true
            )
        }
    }

    @Synchronized
    override fun status(): EngineStatus {
        val state = try {
            backend.getState(tunnel)
        } catch (_: Exception) {
            return EngineStatus(id, EngineState.FAILED, "WireGuard status is unavailable.")
        }
        return EngineStatus(
            engineId = id,
            state = if (state == Tunnel.State.UP) EngineState.RUNNING else EngineState.STOPPED
        )
    }

    @Synchronized
    override fun stop(): EngineFailure? {
        return try {
            val state = backend.setState(tunnel, Tunnel.State.DOWN, null)
            if (state == Tunnel.State.DOWN) null else EngineFailure(
                code = "WIREGUARD_STOP_UNCONFIRMED",
                safeMessage = "WireGuard did not confirm that the tunnel stopped.",
                retryable = true
            )
        } catch (_: Exception) {
            EngineFailure(
                code = "WIREGUARD_STOP_FAILED",
                safeMessage = "WireGuard could not stop cleanly.",
                retryable = true
            )
        }
    }

    private fun invalidConfiguration() = EngineFailure(
        code = "WIREGUARD_INVALID_CONFIG",
        safeMessage = "The WireGuard profile is invalid or unsupported.",
        retryable = false
    )

    private companion object {
        const val TUNNEL_NAME = "roohvpn"
        const val MAX_CONFIG_BYTES = 64 * 1024
    }
}

/** Creates a registry containing only backends actually included in this build. */
object DefaultEngineRegistry {
    fun create(context: Context): EngineRegistry =
        EngineRegistry(listOf(WireGuardEngineBackend(context.applicationContext)))
}

/** Pure profile parsing boundary so malformed/oversized input can be unit-tested. */
internal object WireGuardProfileParser {
    fun parse(configuration: ByteArray): Config? {
        if (configuration.isEmpty() || configuration.size > MAX_CONFIG_BYTES) return null
        return try {
            ByteArrayInputStream(configuration).use { Config.parse(it) }
                .takeIf { it.getPeers().isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_CONFIG_BYTES = 64 * 1024
}
