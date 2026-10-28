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
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import com.aminopy.roohvpn.MainActivity
import com.aminopy.roohvpn.core.EngineFailure
import com.aminopy.roohvpn.core.SingBoxLibboxRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * sing-box VPN lifecycle owner. CommandServer is the only service entry point;
 * its PlatformInterface creates the Android TUN and protects upstream sockets.
 */
class SingBoxVpnService : VpnService(), CommandServerHandler {
    @Volatile private var commandServer: CommandServer? = null
    @Volatile private var platform: SingBoxPlatformInterface? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        synchronized(lock) { instance = this }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val failure = stopTunnel()
            if (failure == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val configuration = synchronized(lock) {
            val value = pendingConfiguration
            pendingConfiguration = null
            value
        } ?: run {
            signalStartFailure(failure("SING_BOX_PROFILE_MISSING", false))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            configuration.fill(0)
            signalStartFailure(failure("SING_BOX_FOREGROUND_FAILED", false))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Thread({
            val result = startTunnel(configuration, startId)
            signalStartFailure(result)
        }, "roohvpn-singbox-start").apply { isDaemon = true }.start()
        return START_NOT_STICKY
    }

    private fun startTunnel(configuration: ByteArray, startId: Int): EngineFailure? {
        try {
            val content = configuration.toString(Charsets.UTF_8)
            SingBoxLibboxRuntime.initialize(this)?.let { return it }
            check(!destroyed) { "VPN service was destroyed during startup" }

            val platformAdapter = SingBoxPlatformInterface(this) { descriptor ->
                synchronized(resourceLock) {
                    check(tun == null) { "sing-box attempted to open more than one TUN" }
                    tun = descriptor
                }
            }
            platform = platformAdapter
            val server = CommandServer(this, platformAdapter)
            commandServer = server
            server.start()
            server.startOrReloadService(content, OverrideOptions())
            check(!destroyed) { "VPN service was destroyed during startup" }
            check(tun != null) { "sing-box did not establish an Android TUN" }
            running = true
            return null
        } catch (_: LinkageError) {
            cleanupFailedStart()
            return failure("SING_BOX_NATIVE_UNAVAILABLE", false)
        } catch (_: Exception) {
            cleanupFailedStart()
            return failure("SING_BOX_START_FAILED", true)
        } finally {
            configuration.fill(0)
        }
    }

    @Synchronized
    private fun cleanupFailedStart() {
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        synchronized(resourceLock) {
            runCatching { tun?.close() }
            tun = null
        }
        platform = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    @Synchronized
    fun stopTunnel(): EngineFailure? {
        if (!running && commandServer == null && tun == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return null
        }
        try {
            val server = commandServer
            if (server != null) {
                server.closeService()
                server.close()
            }
            commandServer = null
            synchronized(resourceLock) {
                tun?.close()
                tun = null
            }
            platform = null
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return null
        } catch (_: LinkageError) {
            return failure("SING_BOX_STOP_UNCONFIRMED", true)
        } catch (_: Exception) {
            return failure("SING_BOX_STOP_UNCONFIRMED", true)
        }
    }

    override fun serviceStop() {
        stopTunnel()
    }

    override fun serviceReload() {
        // Configuration reload is intentionally not implicit; a new validated
        // profile must go through the coordinator and consent lifecycle.
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(enabled: Boolean) {
        // Android VPN mode does not expose a system HTTP proxy toggle here.
    }

    override fun triggerNativeCrash() {
        // Native crash trigger is deliberately not exposed to the application.
    }

    override fun writeDebugMessage(message: String) {
        // Do not log raw native messages; they may contain endpoints or profile data.
    }

    override fun connectSSHAgent(): Int = -1

    override fun onRevoke() {
        Thread({ stopTunnel() }, "roohvpn-singbox-revoke").apply { isDaemon = true }.start()
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed = true
        if (running || commandServer != null || tun != null) {
            Thread({ stopTunnel() }, "roohvpn-singbox-destroy").apply { isDaemon = true }.start()
        }
        synchronized(lock) {
            if (instance === this) instance = null
            pendingConfiguration?.fill(0)
            pendingConfiguration = null
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RØH VPN connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("RØH VPN — sing-box")
            .setContentText(if (running) "VPN tunnel is active" else "Starting VPN tunnel")
            .setContentIntent(openApp).setOngoing(true).build()
    }

    companion object {
        private const val ACTION_START = "com.aminopy.roohvpn.SING_BOX_START"
        private const val ACTION_STOP = "com.aminopy.roohvpn.SING_BOX_STOP"
        private const val CHANNEL_ID = "roohvpn-singbox"
        private const val NOTIFICATION_ID = 7313
        private const val START_TIMEOUT_SECONDS = 30L
        private val lock = Any()
        private val resourceLock = Any()
        @Volatile private var instance: SingBoxVpnService? = null
        @Volatile private var pendingConfiguration: ByteArray? = null
        @Volatile private var startLatch: CountDownLatch? = null
        @Volatile private var startFailure: EngineFailure? = null

        fun start(context: Context, configuration: ByteArray): EngineFailure? {
            val latch: CountDownLatch
            synchronized(lock) {
                if (instance?.running == true) {
                    configuration.fill(0)
                    return failure("SING_BOX_ALREADY_RUNNING", false)
                }
                pendingConfiguration?.fill(0)
                pendingConfiguration = configuration.copyOf()
                startFailure = null
                latch = CountDownLatch(1)
                startLatch = latch
            }
            return try {
                val intent = Intent(context, SingBoxVpnService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
                if (!latch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    context.stopService(Intent(context, SingBoxVpnService::class.java))
                    failure("SING_BOX_START_TIMEOUT", true)
                } else startFailure
            } catch (_: Exception) {
                synchronized(lock) {
                    pendingConfiguration?.fill(0)
                    pendingConfiguration = null
                }
                failure("SING_BOX_SERVICE_START_FAILED", true)
            } finally {
                configuration.fill(0)
            }
        }

        fun stop(context: Context): EngineFailure? {
            val service = instance
            if (service == null) {
                context.stopService(Intent(context, SingBoxVpnService::class.java))
                return null
            }
            return service.stopTunnel()
        }

        fun isRunning(): Boolean = instance?.running == true

        private fun signalStartFailure(result: EngineFailure?) {
            synchronized(lock) {
                startFailure = result
                startLatch?.countDown()
                startLatch = null
            }
        }

        private fun failure(code: String, retryable: Boolean) = EngineFailure(
            code = code,
            safeMessage = when (code) {
                "SING_BOX_NATIVE_UNAVAILABLE" -> "The sing-box native runtime is unavailable in this build."
                "SING_BOX_START_TIMEOUT" -> "sing-box did not confirm startup before the timeout."
                "SING_BOX_STOP_UNCONFIRMED" -> "sing-box shutdown could not be confirmed; resources were retained."
                else -> "sing-box could not start or stop safely."
            },
            retryable = retryable
        )
    }
}
