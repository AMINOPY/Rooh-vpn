package com.aminopy.roohvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.aminopy.roohvpn.MainActivity
import com.aminopy.roohvpn.core.EngineFailure
import com.aminopy.roohvpn.core.AetherHevBridge
import com.aminopy.roohvpn.security.AetherIdentityVault
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single TUN owner for the Aether native engine.
 *
 * Startup is deliberately ordered: engine SOCKS listener -> Android TUN ->
 * in-process hev bridge. The app package is excluded from its own VPN so the
 * engine's upstream sockets cannot recurse into this TUN.
 */
class AetherVpnService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var engine: Process? = null
    @Volatile private var running = false
    @Volatile private var stopping = false
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        synchronized(lock) { instance = this }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, notification("Starting Aether…"))
        } catch (_: Exception) {
            completeStart(failure("AETHER_FOREGROUND_FAILED", false))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            worker.execute {
                val result = stopTunnel()
                completeStop(result)
                if (result == null) stopSelf(startId)
            }
        } else {
            worker.execute {
                val result = startTunnel()
                completeStart(result)
                if (result != null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                } else {
                    watchEngineExit()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(): EngineFailure? {
        if (running) return null
        return try {
            val nativeDir = File(applicationInfo.nativeLibraryDir)
            val binary = File(nativeDir, ENGINE_BINARY)
            val bridge = File(nativeDir, BRIDGE_LIBRARY)
            val hev = File(nativeDir, HEV_LIBRARY)
            check(binary.isFile && bridge.isFile && hev.isFile) {
                "Aether native runtime is incomplete for this ABI"
            }

            val workDir = File(filesDir, "aether-runtime").apply { mkdirs() }
            // Recover an interrupted previous session, then unseal only for the
            // lifetime of the native engine process.
            AetherIdentityVault.sealPlaintext(workDir)
            AetherIdentityVault.unseal(workDir)

            val process = ProcessBuilder(
                binary.absolutePath,
                "--masque",
                "--balanced",
                "--dual",
                "--bind",
                "$SOCKS_HOST:$SOCKS_PORT",
                "--stats"
            ).directory(workDir).redirectErrorStream(true).apply {
                environment()["HOME"] = workDir.absolutePath
                environment()["TMPDIR"] = workDir.absolutePath
                environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
            }.start()
            engine = process
            Thread({
                runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { /* redact by omission */ } } }
            }, "roohvpn-aether-output").apply { isDaemon = true; start() }

            check(awaitSocks(process, ENGINE_READY_TIMEOUT_MS)) {
                "Aether did not open its local SOCKS5 listener"
            }

            val descriptor = Builder()
                .setSession("RØH VPN — Aether")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4, 30)
                .addAddress(TUN_IPV6, 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName)
                .setBlocking(true)
                .establish() ?: error("Android did not establish the Aether TUN")
            tun = descriptor

            val config = writeHevConfig(workDir)
            check(AetherHevBridge.start(config.absolutePath, descriptor.fd)) {
                "The native TUN-to-SOCKS bridge refused to start"
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!AetherHevBridge.isRunning() && System.nanoTime() < deadline) {
                Thread.sleep(40)
            }
            check(AetherHevBridge.isRunning()) { "The native TUN-to-SOCKS bridge did not become active" }

            running = true
            null
        } catch (_: LinkageError) {
            cleanupAfterStartFailure()
            failure("AETHER_NATIVE_UNAVAILABLE", false)
        } catch (_: Exception) {
            cleanupAfterStartFailure()
            failure("AETHER_START_FAILED", true)
        }
    }

    private fun awaitSocks(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) return false
            try {
                Socket().use { it.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 400) }
                return true
            } catch (_: Exception) {
                Thread.sleep(350)
            }
        }
        return false
    }

    private fun writeHevConfig(workDir: File): File {
        val config = File(workDir, "hev.yaml")
        config.writeText(
            """
            tunnel:
              mtu: $TUN_MTU
              ipv4: $TUN_IPV4
              ipv6: '$TUN_IPV6'
            socks5:
              address: $SOCKS_HOST
              port: $SOCKS_PORT
              udp: 'udp'
            misc:
              task-stack-size: 86016
              connect-timeout: 12000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 120000
              limit-nofile: 65535
              log-level: warn
            """.trimIndent()
        )
        config.setReadable(false, false)
        config.setReadable(true, true)
        config.setWritable(false, false)
        config.setWritable(true, true)
        return config
    }

    private fun watchEngineExit() {
        val watched = engine ?: return
        Thread({
            val exit = runCatching { watched.waitFor() }.getOrNull()
            if (exit != null && !stopping && running && engine === watched) {
                // The TUN remains blocking until the forwarding bridge is stopped;
                // there is no fallback to the device's physical network.
                worker.execute {
                    runCatching { AetherHevBridge.stop() }
                    waitForBridgeStop()
                    runCatching { tun?.close() }
                    tun = null
                    running = false
                    engine = null
                    AetherIdentityVault.sealPlaintext(File(filesDir, "aether-runtime"))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }, "roohvpn-aether-watch").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun cleanupAfterStartFailure() {
        runCatching { AetherHevBridge.stop() }
        waitForBridgeStop()
        runCatching { tun?.close() }
        tun = null
        engine?.let { proc ->
            runCatching { proc.destroy() }
            runCatching { if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly() }
        }
        engine = null
        running = false
        AetherIdentityVault.sealPlaintext(File(filesDir, "aether-runtime"))
    }

    @Synchronized
    private fun stopTunnel(): EngineFailure? {
        if (!running && tun == null && engine == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return null
        }
        stopping = true
        try {
            AetherHevBridge.stop()
            if (!waitForBridgeStop()) {
                return failure("AETHER_TUN_STOP_UNCONFIRMED", true)
            }
            try {
                tun?.close()
            } catch (_: Exception) {
                return failure("AETHER_TUN_CLOSE_FAILED", true)
            }
            tun = null

            val process = engine
            if (process != null) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        return failure("AETHER_PROCESS_STOP_UNCONFIRMED", true)
                    }
                }
            }
            engine = null
            running = false
            AetherIdentityVault.sealPlaintext(File(filesDir, "aether-runtime"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            return null
        } catch (_: LinkageError) {
            return failure("AETHER_NATIVE_STOP_FAILED", true)
        } catch (_: Exception) {
            return failure("AETHER_STOP_FAILED", true)
        } finally {
            stopping = false
        }
    }

    private fun waitForBridgeStop(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (AetherHevBridge.isRunning() && System.nanoTime() < deadline) Thread.sleep(40)
        return !AetherHevBridge.isRunning()
    }

    override fun onRevoke() {
        worker.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        if (running || tun != null || engine != null) {
            worker.execute { stopTunnel() }
        }
        synchronized(lock) { if (instance === this) instance = null }
        worker.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RØH VPN connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("RØH VPN — Aether")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.aminopy.roohvpn.AETHER_START"
        private const val ACTION_STOP = "com.aminopy.roohvpn.AETHER_STOP"
        private const val CHANNEL_ID = "roohvpn-aether"
        private const val NOTIFICATION_ID = 7314
        private const val ENGINE_BINARY = "libaether.so"
        private const val BRIDGE_LIBRARY = "libroohvpnhev.so"
        private const val HEV_LIBRARY = "libhev-socks5-tunnel.so"
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 1819
        private const val ENGINE_READY_TIMEOUT_MS = 240_000L
        private const val TUN_MTU = 1280
        private const val TUN_IPV4 = "10.10.14.1"
        private const val TUN_IPV6 = "fc00::10:10:14:1"
        private val lock = Any()
        @Volatile private var instance: AetherVpnService? = null
        @Volatile private var startLatch: CountDownLatch? = null
        @Volatile private var startFailure: EngineFailure? = null
        @Volatile private var stopLatch: CountDownLatch? = null
        @Volatile private var stopFailure: EngineFailure? = null

        fun isNativeRuntimePackaged(context: Context): Boolean {
            val dir = File(context.applicationInfo.nativeLibraryDir)
            return File(dir, ENGINE_BINARY).isFile &&
                File(dir, BRIDGE_LIBRARY).isFile &&
                File(dir, HEV_LIBRARY).isFile
        }

        fun isRunning(): Boolean = instance?.running == true

        fun start(context: Context): EngineFailure? {
            if (!isNativeRuntimePackaged(context)) {
                return failure("AETHER_RUNTIME_NOT_PACKAGED", "Aether native runtime is not included in this APK.", false)
            }
            val latch = CountDownLatch(1)
            synchronized(lock) {
                if (instance?.running == true) return null
                startFailure = null
                startLatch = latch
            }
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AetherVpnService::class.java).setAction(ACTION_START)
                )
                if (!latch.await(ENGINE_READY_TIMEOUT_MS + 20_000L, TimeUnit.MILLISECONDS)) {
                    failure("AETHER_START_TIMEOUT", "Aether did not finish starting in time.", true)
                } else startFailure
            } catch (_: Exception) {
                failure("AETHER_SERVICE_START_FAILED", "Android could not start the Aether VPN service.", true)
            } finally {
                synchronized(lock) { if (startLatch === latch) startLatch = null }
            }
        }

        fun stop(context: Context): EngineFailure? {
            val latch = CountDownLatch(1)
            synchronized(lock) {
                if (instance == null) return null
                stopFailure = null
                stopLatch = latch
            }
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AetherVpnService::class.java).setAction(ACTION_STOP)
                )
                if (!latch.await(12, TimeUnit.SECONDS)) {
                    failure("AETHER_STOP_TIMEOUT", "Aether shutdown could not be confirmed.", true)
                } else stopFailure
            } catch (_: Exception) {
                failure("AETHER_STOP_REQUEST_FAILED", "Android could not request Aether shutdown.", true)
            } finally {
                synchronized(lock) { if (stopLatch === latch) stopLatch = null }
            }
        }

        private fun completeStart(failure: EngineFailure?) {
            synchronized(lock) {
                startFailure = failure
                startLatch?.countDown()
            }
        }

        private fun completeStop(failure: EngineFailure?) {
            synchronized(lock) {
                stopFailure = failure
                stopLatch?.countDown()
            }
        }

        private fun failure(code: String, retryable: Boolean) =
            EngineFailure(code, "Aether could not establish or safely stop its VPN tunnel.", retryable)
    }
}
