package com.aminopy.roohvpn.tor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TorProxyService : Service() {
    @Volatile private var controller: TorController? = null
    @Volatile private var running = false
    @Volatile private var starting = false
    @Volatile private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "RØH Tor", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            startForeground(NOTIFICATION_ID, notification("Stopping Tor…"))
            Thread({
                val stopped = controller?.stop() ?: true
                running = false
                if (stopped) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    completeStop(true)
                } else completeStop(false)
            }, "rooh-tor-stop").start()
            return START_NOT_STICKY
        }

        if (running || starting) {
            completeStart(running)
            return START_NOT_STICKY
        }
        stopRequested = false
        startForeground(NOTIFICATION_ID, notification("Starting Tor…"))
        starting = true
        Thread({
            val settings = TorSettingsRepository(this).load()
            val instance = TorController(this, settings, onProgress = { pct ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Tor bootstrap: $pct%"))
            }, onStopped = { reason ->
                running = false
                if (reason != null) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(reason))
                }
            })
            controller = instance
            if (stopRequested) {
                starting = false
                completeStart(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return@Thread
            }
            val started = instance.start() && instance.awaitSocksReady()
            if (stopRequested) {
                instance.stop()
                running = false
                starting = false
                completeStart(false)
                completeStop(true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return@Thread
            }
            running = started
            starting = false
            completeStart(started)
            if (started) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Tor SOCKS ready on 127.0.0.1:${settings.socksPort}"))
            } else {
                instance.stop()
                starting = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }, "rooh-tor-start").start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller?.stop()
        controller = null
        running = false
        super.onDestroy()
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("RØH VPN — Tor")
            .setContentText(message)
            .setOngoing(running || message == "Starting Tor…")
            .build()
    }

    companion object {
        private const val CHANNEL = "roohvpn-tor"
        private const val NOTIFICATION_ID = 7315
        private const val ACTION_START = "com.aminopy.roohvpn.TOR_START"
        private const val ACTION_STOP = "com.aminopy.roohvpn.TOR_STOP"
        @Volatile private var startLatch: CountDownLatch? = null
        @Volatile private var startResult = false
        @Volatile private var stopLatch: CountDownLatch? = null
        @Volatile private var stopResult = false

        fun start(context: Context, timeoutMs: Long = 20_000): Boolean {
            val latch = CountDownLatch(1)
            synchronized(this) { startLatch = latch; startResult = false }
            return try {
                context.startForegroundService(Intent(context, TorProxyService::class.java).setAction(ACTION_START))
                latch.await(timeoutMs, TimeUnit.MILLISECONDS) && startResult
            } catch (_: Exception) { false }
            finally { synchronized(this) { if (startLatch === latch) startLatch = null } }
        }

        fun stop(context: Context, timeoutMs: Long = 8_000): Boolean {
            val latch = CountDownLatch(1)
            synchronized(this) { stopLatch = latch; stopResult = false }
            return try {
                context.startForegroundService(Intent(context, TorProxyService::class.java).setAction(ACTION_STOP))
                latch.await(timeoutMs, TimeUnit.MILLISECONDS) && stopResult
            } catch (_: Exception) { false }
            finally { synchronized(this) { if (stopLatch === latch) stopLatch = null } }
        }

        private fun completeStart(value: Boolean) {
            synchronized(this) { startResult = value; startLatch?.countDown() }
        }

        private fun completeStop(value: Boolean) {
            synchronized(this) { stopResult = value; stopLatch?.countDown() }
        }
    }
}
