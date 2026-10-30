package com.aminopy.roohvpn.tor

import android.content.Context
import android.content.SharedPreferences

data class TorSettings(
    val bridgeMode: String = "none",
    val bridgeTransport: String = "obfs4",
    val ownBridges: String = "",
    val entryNodes: String = "",
    val exitNodes: String = "",
    val excludeNodes: String = "",
    val excludeExitNodes: String = "",
    val strictNodes: Boolean = false,
    val ipv4: Boolean = true,
    val ipv6: Boolean = true,
    val connectionPadding: Boolean = true,
    val reducedConnectionPadding: Boolean = true,
    val avoidDiskWrites: Boolean = true,
    val enforceDistinctSubnets: Boolean = true,
    val isolateDestAddr: Boolean = false,
    val isolateDestPort: Boolean = false,
    val newCircuitPeriodSeconds: Int = 30,
    val maxCircuitDirtinessSeconds: Int = 600,
    val socksPort: Int = DEFAULT_SOCKS_PORT,
) {
    companion object {
        const val DEFAULT_SOCKS_PORT = 9250
        val TRANSPORTS = setOf("obfs4", "obfs3", "scramblesuit", "meek_lite", "snowflake", "conjure", "webtunnel", "vanilla")
        val BRIDGE_MODES = setOf("none", "default", "own")
    }
}

class TorSettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("rooh_tor_settings", Context.MODE_PRIVATE)

    fun load(): TorSettings = TorSettings(
        bridgeMode = prefs.getString("bridge_mode", "none") ?: "none",
        bridgeTransport = prefs.getString("bridge_transport", "obfs4") ?: "obfs4",
        ownBridges = prefs.getString("own_bridges", "") ?: "",
        entryNodes = prefs.getString("entry_nodes", "") ?: "",
        exitNodes = prefs.getString("exit_nodes", "") ?: "",
        excludeNodes = prefs.getString("exclude_nodes", "") ?: "",
        excludeExitNodes = prefs.getString("exclude_exit_nodes", "") ?: "",
        strictNodes = prefs.getBoolean("strict_nodes", false),
        ipv4 = prefs.getBoolean("ipv4", true),
        ipv6 = prefs.getBoolean("ipv6", true),
        connectionPadding = prefs.getBoolean("connection_padding", true),
        reducedConnectionPadding = prefs.getBoolean("reduced_padding", true),
        avoidDiskWrites = prefs.getBoolean("avoid_disk_writes", true),
        enforceDistinctSubnets = prefs.getBoolean("distinct_subnets", true),
        isolateDestAddr = prefs.getBoolean("isolate_dest_addr", false),
        isolateDestPort = prefs.getBoolean("isolate_dest_port", false),
        newCircuitPeriodSeconds = prefs.getInt("new_circuit_period", 30),
        maxCircuitDirtinessSeconds = prefs.getInt("max_circuit_dirtiness", 600),
        socksPort = prefs.getInt("socks_port", TorSettings.DEFAULT_SOCKS_PORT),
    )

    fun save(s: TorSettings) {
        require(s.bridgeMode in TorSettings.BRIDGE_MODES)
        require(s.bridgeTransport in TorSettings.TRANSPORTS)
        require(s.socksPort in 1024..65535)
        prefs.edit()
            .putString("bridge_mode", s.bridgeMode)
            .putString("bridge_transport", s.bridgeTransport)
            .putString("own_bridges", s.ownBridges)
            .putString("entry_nodes", s.entryNodes)
            .putString("exit_nodes", s.exitNodes)
            .putString("exclude_nodes", s.excludeNodes)
            .putString("exclude_exit_nodes", s.excludeExitNodes)
            .putBoolean("strict_nodes", s.strictNodes)
            .putBoolean("ipv4", s.ipv4)
            .putBoolean("ipv6", s.ipv6)
            .putBoolean("connection_padding", s.connectionPadding)
            .putBoolean("reduced_padding", s.reducedConnectionPadding)
            .putBoolean("avoid_disk_writes", s.avoidDiskWrites)
            .putBoolean("distinct_subnets", s.enforceDistinctSubnets)
            .putBoolean("isolate_dest_addr", s.isolateDestAddr)
            .putBoolean("isolate_dest_port", s.isolateDestPort)
            .putInt("new_circuit_period", s.newCircuitPeriodSeconds.coerceIn(30, 86400))
            .putInt("max_circuit_dirtiness", s.maxCircuitDirtinessSeconds.coerceIn(60, 86400))
            .putInt("socks_port", s.socksPort)
            .apply()
    }
}
