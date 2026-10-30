package com.aminopy.roohvpn.tor

import java.io.File

object TorConfigBuilder {
    fun build(settings: TorSettings, nativeDir: File, dataDir: File, torDir: File): String {
        require(settings.bridgeMode in TorSettings.BRIDGE_MODES)
        require(settings.bridgeTransport in TorSettings.TRANSPORTS)
        require(settings.socksPort in 1024..65535)

        return buildString {
            val isolation = buildString {
                if (settings.isolateDestAddr) append(" IsolateDestAddr")
                if (settings.isolateDestPort) append(" IsolateDestPort")
            }
            appendLine("SocksPort 127.0.0.1:${settings.socksPort}$isolation")
            appendLine("DNSPort 0")
            appendLine("RunAsDaemon 0")
            appendLine("ClientOnly 1")
            appendLine("DataDirectory ${dataDir.absolutePath}")
            // GeoIP databases are optional; never point Tor at missing files.
            File(torDir, "geoip").takeIf { it.isFile }?.let { appendLine("GeoIPFile ${it.absolutePath}") }
            File(torDir, "geoip6").takeIf { it.isFile }?.let { appendLine("GeoIPv6File ${it.absolutePath}") }
            appendLine("Log notice stdout")
            appendLine("AvoidDiskWrites ${if (settings.avoidDiskWrites) 1 else 0}")
            appendLine("ConnectionPadding ${if (settings.connectionPadding) 1 else 0}")
            appendLine("ReducedConnectionPadding ${if (settings.reducedConnectionPadding) 1 else 0}")
            appendLine("EnforceDistinctSubnets ${if (settings.enforceDistinctSubnets) 1 else 0}")
            appendLine("ClientUseIPv4 ${if (settings.ipv4) 1 else 0}")
            appendLine("ClientUseIPv6 ${if (settings.ipv6) 1 else 0}")
            appendLine("NewCircuitPeriod ${settings.newCircuitPeriodSeconds.coerceIn(30, 86400)}")
            appendLine("MaxCircuitDirtiness ${settings.maxCircuitDirtinessSeconds.coerceIn(60, 86400)}")
            appendLine("DormantClientTimeout 15 minutes")
            appendLine("AutomapHostsOnResolve 1")
            appendLine("VirtualAddrNetworkIPv4 10.192.0.0/10")
            appendLine("VirtualAddrNetworkIPv6 [FC00::]/7")
            appendNode("EntryNodes", settings.entryNodes)
            appendNode("ExitNodes", settings.exitNodes)
            appendNode("ExcludeNodes", settings.excludeNodes)
            appendNode("ExcludeExitNodes", settings.excludeExitNodes)
            appendLine("StrictNodes ${if (settings.strictNodes) 1 else 0}")
            appendBridges(settings, nativeDir, torDir)
        }
    }

    private fun StringBuilder.appendNode(name: String, raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        require(value.matches(Regex("[A-Za-z0-9{},~+\\-]+"))) { "Invalid Tor node selector" }
        appendLine("$name $value")
    }

    private fun StringBuilder.appendBridges(settings: TorSettings, nativeDir: File, torDir: File) {
        if (settings.bridgeMode == "none") {
            appendLine("UseBridges 0")
            return
        }
        appendLine("UseBridges 1")
        val plugin = when (settings.bridgeTransport) {
            "obfs4", "obfs3", "scramblesuit", "meek_lite", "webtunnel" -> "libobfs4proxy.so"
            "snowflake" -> "libsnowflake.so"
            "conjure" -> "libconjure.so"
            else -> null
        }
        if (plugin != null) {
            val executable = File(nativeDir, plugin)
            require(executable.isFile && executable.canExecute()) { "Tor transport is not packaged: $plugin" }
            val transports = if (plugin == "libobfs4proxy.so") {
                "obfs4,obfs3,scramblesuit,meek_lite,webtunnel"
            } else settings.bridgeTransport
            appendLine("ClientTransportPlugin $transports exec ${executable.absolutePath}")
        }

        val lines = when (settings.bridgeMode) {
            "own" -> settings.ownBridges.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            else -> if (settings.bridgeTransport == "snowflake") {
                listOf(
                    "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72",
                    "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA"
                )
            } else {
                val all = File(torDir, "bridges_default.lst").takeIf { it.isFile }
                    ?.readLines().orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                val defaults = if (settings.bridgeTransport == "vanilla") {
                    all.filter { line ->
                        val address = line.substringBefore(' ')
                        address.contains(':') && !address.any { it.isLetter() }
                    }
                } else all.filter { it.startsWith("${settings.bridgeTransport} ") }
                defaults.take(8)
            }
        }
        require(lines.isNotEmpty()) { "No bridge lines are available for the selected transport" }
        lines.forEach { line ->
            require(line.length <= 2048 && !line.contains('\r') && !line.contains('\n')) { "Invalid bridge line" }
            if (settings.bridgeTransport == "vanilla") {
                val address = line.substringBefore(' ')
                require(address.contains(':') && !address.any { it.isLetter() }) { "Invalid vanilla bridge address" }
            } else {
                require(line.substringBefore(' ') == settings.bridgeTransport) { "Bridge transport mismatch" }
            }
            appendLine("Bridge $line")
        }
    }
}
