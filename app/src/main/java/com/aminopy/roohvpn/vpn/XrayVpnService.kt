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
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import libv2ray.SocketProtector
import com.aminopy.roohvpn.MainActivity
import com.aminopy.roohvpn.core.EngineFailure
import com.aminopy.roohvpn.core.XrayTunProfileAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the Android TUN descriptor for Xray. Xray receives a duplicate of the
 * descriptor; this service retains and closes the original after StopLoop.
 */
class XrayVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "roohvpn-xray").apply { isDaemon = true }
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var controller: CoreController? = null
    @Volatile private var running = false
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        synchronized(lock) { instance = this }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            worker.execute {
                stopTunnel()
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            signalStartFailure(failure("XRAY_FOREGROUND_FAILED", false))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val configuration = synchronized(lock) {
            pendingConfiguration.also { pendingConfiguration = null }
        }
        if (configuration == null) {
            signalStartFailure(failure("XRAY_PROFILE_MISSING", false))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        worker.execute { startTunnel(configuration, startId) }
        return START_NOT_STICKY
    }

    private fun startTunnel(configuration: ByteArray, startId: Int) {
        try {
            val adapted = XrayTunProfileAdapter.prepare(configuration)
                ?: throw IllegalArgumentException("Xray profile rejected")
            configuration.fill(0)

            val descriptor = Builder()
                .setSession("RØH VPN — Xray")
                .setMtu(XrayTunProfileAdapter.MTU)
                .addAddress(XrayTunProfileAdapter.IPV4_ADDRESS, XrayTunProfileAdapter.IPV4_PREFIX)
                .addAddress(XrayTunProfileAdapter.IPV6_ADDRESS, XrayTunProfileAdapter.IPV6_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(XrayTunProfileAdapter.IPV4_DNS)
                .addDnsServer(XrayTunProfileAdapter.IPV6_DNS)
                .establish() ?: throw IllegalStateException("Android did not establish the VPN TUN")
            tun = descriptor

            // AndroidLibXrayLite resolves bundled geoip.dat/geosite.dat through
            // Android assets when the same-named file is not present on disk.
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            val protector = object : SocketProtector {
                override fun protectSocket(fd: Int): Boolean = protect(fd)
            }
            val protectFailure = Libv2ray.registerSocketProtector(protector)
            if (protectFailure != null) {
                throw IllegalStateException("Xray socket-protection callback registration failed")
            }

            val callbacks = object : CoreCallbackHandler {
                override fun startup(): Int = 0
                override fun shutdown(): Int = 0
                override fun onEmitStatus(code: Int, message: String): Int = 0
            }
            val core = Libv2ray.newCoreController(callbacks)
            controller = core
            // StartLoop's tunFd contract duplicates this descriptor internally;
            // the original remains owned by this service until StopLoop succeeds.
            core.startLoop(adapted, descriptor.fd)

            running = true
            signalStartFailure(null)
        } catch (_: Exception) {
            configuration.fill(0)
            cleanupFailedStart()
            signalStartFailure(failure("XRAY_START_FAILED", true))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        } catch (_: LinkageError) {
            configuration.fill(0)
            cleanupFailedStart()
            signalStartFailure(failure("XRAY_NATIVE_UNAVAILABLE", false))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    @Synchronized
    private fun cleanupFailedStart() {
        try {
            controller?.stopLoop()
        } catch (_: Exception) {
            // Do not surface native exception text; it may contain config details.
        } catch (_: LinkageError) {
            // Keep the user-facing error redacted.
        }
        controller = null
        try {
            Libv2ray.registerSocketProtector(null)
        } catch (_: Exception) {
            // The callback itself fails closed if no protector is registered.
        } catch (_: LinkageError) {
            // Native binding may not have initialized.
        }
        try {
            tun?.close()
        } catch (_: Exception) {
            // Best-effort cleanup after a failed start.
        }
        tun = null
        running = false
    }

    @Synchronized
    fun stopTunnel(): EngineFailure? {
        if (stopping) return failure("XRAY_STOP_IN_PROGRESS", true)
        if (!running && controller == null && tun == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return null
        }
        stopping = true
        try {
            try {
                controller?.stopLoop()
            } catch (_: Exception) {
                // Keep descriptor and protector alive if shutdown cannot be confirmed.
                return failure("XRAY_STOP_UNCONFIRMED", true)
            } catch (_: LinkageError) {
                return failure("XRAY_STOP_UNCONFIRMED", true)
            }

            controller = null
            try {
                Libv2ray.registerSocketProtector(null)
            } catch (_: Exception) {
                return failure("XRAY_PROTECTOR_SHUTDOWN_FAILED", true)
            } catch (_: LinkageError) {
                return failure("XRAY_PROTECTOR_SHUTDOWN_FAILED", true)
            }
            try {
                tun?.close()
            } catch (_: Exception) {
                return failure("XRAY_TUN_CLOSE_FAILED", true)
            }
            tun = null
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return null
        } finally {
            stopping = false
        }
    }

    override fun onRevoke() {
        // Android is revoking VPN consent; make a best-effort orderly shutdown.
        worker.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        if (running || controller != null || tun != null) {
            // Do not block the main thread waiting on native shutdown.
            worker.execute { stopTunnel() }
        }
        worker.shutdown()
        synchronized(lock) {
            if (instance === this) instance = null
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RØH VPN connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows the active Xray VPN tunnel" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("RØH VPN — Xray")
            .setContentText("VPN tunnel is active")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.aminopy.roohvpn.XRAY_START"
        private const val ACTION_STOP = "com.aminopy.roohvpn.XRAY_STOP"
        private const val CHANNEL_ID = "roohvpn-xray"
        private const val NOTIFICATION_ID = 7312
        private const val START_TIMEOUT_SECONDS = 25L
        private val lock = Any()

        @Volatile private var instance: XrayVpnService? = null
        @Volatile private var pendingConfiguration: ByteArray? = null
        @Volatile private var startLatch: CountDownLatch? = null
        @Volatile private var startFailure: EngineFailure? = null

        fun start(context: Context, configuration: ByteArray): EngineFailure? {
            val latch: CountDownLatch
            synchronized(lock) {
                if (instance?.running == true) {
                    configuration.fill(0)
                    return failure("XRAY_ALREADY_RUNNING", false)
                }
                pendingConfiguration?.fill(0)
                pendingConfiguration = configuration.copyOf()
                startFailure = null
                latch = CountDownLatch(1)
                startLatch = latch
            }

            return try {
                val intent = Intent(context, XrayVpnService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
                if (!latch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    context.stopService(Intent(context, XrayVpnService::class.java))
                    failure("XRAY_START_TIMEOUT", true)
                } else {
                    startFailure
                }
            } catch (_: Exception) {
                failure("XRAY_SERVICE_START_FAILED", true)
            } finally {
                configuration.fill(0)
            }
        }

        fun stop(context: Context): EngineFailure? {
            val service = instance
            if (service == null) {
                context.stopService(Intent(context, XrayVpnService::class.java))
                return null
            }
            return service.stopTunnel()
        }

        fun isRunning(): Boolean = instance?.running == true

        private fun signalStartFailure(failure: EngineFailure?) {
            synchronized(lock) {
                startFailure = failure
                startLatch?.countDown()
                startLatch = null
            }
        }

        private fun failure(code: String, retryable: Boolean) = EngineFailure(
            code = code,
            safeMessage = when (code) {
                "XRAY_NATIVE_UNAVAILABLE" -> "Xray's native runtime is unavailable or incompatible."
                "XRAY_START_TIMEOUT" -> "Xray did not confirm startup before the timeout."
                "XRAY_STOP_UNCONFIRMED" -> "Xray shutdown could not be confirmed; resources were retained."
                else -> "Xray could not start or stop safely."
            },
            retryable = retryable
        )
    }
}
