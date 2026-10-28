package com.aminopy.roohvpn.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress
import java.net.NetworkInterface as JNetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * Android adapter for sing-box's libbox PlatformInterface.
 * Unsupported root/bridge/shell features fail explicitly instead of pretending to work.
 */
internal class SingBoxPlatformInterface(
    private val service: VpnService,
    private val onTunOpened: (android.os.ParcelFileDescriptor) -> Unit
) : PlatformInterface {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val monitor = AtomicReference<ConnectivityManager.NetworkCallback?>()

    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    /** Protect each sing-box outbound socket before it is used. */
    override fun autoDetectInterfaceControl(fd: Int) {
        check(fd >= 0 && service.protect(fd)) { "Android refused to protect sing-box outbound socket" }
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(service) == null) { "Android VPN consent is missing" }
        val builder = service.Builder()
            .setSession("RØH VPN — sing-box")
            .setMtu(options.mtu.coerceIn(1280, 9000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val address = v4.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val v6 = options.inet6Address
        while (v6.hasNext()) {
            val address = v6.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val v4Routes = options.inet4RouteAddress
                while (v4Routes.hasNext()) builder.addRoute(android.net.IpPrefix(v4Routes.next().toIpPrefixString()))
                val v6Routes = options.inet6RouteAddress
                while (v6Routes.hasNext()) builder.addRoute(android.net.IpPrefix(v6Routes.next().toIpPrefixString()))
                val v4Excludes = options.inet4RouteExcludeAddress
                while (v4Excludes.hasNext()) builder.excludeRoute(android.net.IpPrefix(v4Excludes.next().toIpPrefixString()))
                val v6Excludes = options.inet6RouteExcludeAddress
                while (v6Excludes.hasNext()) builder.excludeRoute(android.net.IpPrefix(v6Excludes.next().toIpPrefixString()))
            } else {
                val v4Routes = options.inet4RouteRange
                while (v4Routes.hasNext()) {
                    val route = v4Routes.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                val v6Routes = options.inet6RouteRange
                while (v6Routes.hasNext()) {
                    val route = v6Routes.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            }
            val include = options.includePackage
            while (include.hasNext()) {
                try { builder.addAllowedApplication(include.next()) }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { Log.w(TAG, "Ignoring unknown included package") }
            }
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                try { builder.addDisallowedApplication(exclude.next()) }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { Log.w(TAG, "Ignoring unknown excluded package") }
            }
        }

        val descriptor = builder.establish() ?: error("Android did not establish the sing-box TUN")
        try {
            onTunOpened(descriptor)
            return descriptor.fd
        } catch (failure: Exception) {
            runCatching { descriptor.close() }
            throw failure
        }
    }

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int
    ): ConnectionOwner {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Connection-owner lookup requires Android 10+" }
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol, InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )
        check(uid != Process.INVALID_UID) { "Android could not identify the connection owner" }
        return ConnectionOwner().also {
            it.userId = uid
            it.userName = service.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (monitor.get() != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publishNetwork(listener, network)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publishNetwork(listener, network)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = publishNetwork(listener, network)
        }
        if (monitor.compareAndSet(null, callback)) connectivity.registerDefaultNetworkCallback(callback)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = monitor.getAndSet(null) ?: return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    private fun publishNetwork(listener: InterfaceUpdateListener, network: Network) {
        val properties = connectivity.getLinkProperties(network) ?: return
        val name = properties.interfaceName ?: return
        val iface = runCatching { JNetworkInterface.getByName(name) }.getOrNull() ?: return
        val caps = connectivity.getNetworkCapabilities(network)
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        runCatching { listener.updateDefaultInterface(name, iface.index, metered, false) }
        runCatching { listener.updateNetworkPath(name) }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = mutableListOf<NetworkInterface>()
        val all = runCatching { Collections.list(JNetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        for (iface in all) {
            val item = NetworkInterface()
            item.name = iface.name
            item.index = iface.index
            item.mtu = runCatching { iface.mtu }.getOrDefault(1500)
            item.addresses = strings(iface.interfaceAddresses.mapNotNull { a ->
                val host = a.address.hostAddress ?: return@mapNotNull null
                "$host/${a.networkPrefixLength}"
            })
            var flags = 0
            if (iface.isUp) flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (iface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (iface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (iface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            item.flags = flags
            item.type = when {
                iface.name.startsWith("wlan") -> Libbox.InterfaceTypeWIFI
                iface.name.startsWith("rmnet") || iface.name.startsWith("ccmni") -> Libbox.InterfaceTypeCellular
                iface.name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            item.dnsServer = strings(emptyList())
            item.dnsSearchDomain = strings(emptyList())
            item.gateway = strings(emptyList())
            item.metered = false
            result.add(item)
        }
        return object : NetworkInterfaceIterator {
            private var index = 0
            override fun hasNext(): Boolean = index < result.size
            override fun next(): NetworkInterface? = if (hasNext()) result[index++] else null
        }
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: Notification) = Unit
    override fun cancelNotification(identifier: String, typeID: Int) = Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun registerMyInterface(name: String) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() { error("Android shell integration is not supported") }
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int): ShellSession =
        error("Android shell integration is not supported")
    override fun lookupUser(username: String): PlatformUser? = null
    override fun lookupSFTPServer(): String = ""
    override fun readSystemSSHHostKey(): String = error("System SSH host keys are not exposed")
    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions): BridgeSession = error("Platform bridge is not supported")
    override fun usePlatformAutoRedirect(): Boolean = false
    override fun createAutoRedirect(options: ByteArray, handler: AutoRedirectHandler): AutoRedirectSession =
        error("Platform auto-redirect is not supported")

    private fun strings(values: List<String>): StringIterator = object : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < values.size
        override fun next(): String = values[index++]
    }

    private fun io.nekohasekai.libbox.Inet4Address.toIpPrefixString(): String = "${address()}/${prefix()}"
    private fun io.nekohasekai.libbox.Inet6Address.toIpPrefixString(): String = "${address()}/${prefix()}"

    private companion object { const val TAG = "RoohVpnSingBox" }
}
