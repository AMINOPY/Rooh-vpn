package com.aminopy.roohvpn.tor

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TorController(
    context: Context,
    private val settings: TorSettings,
    private val onProgress: (Int) -> Unit = {},
    private val onStopped: (String?) -> Unit = {},
) {
    private val app = context.applicationContext
    private val active = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var outputThread: Thread? = null

    val isRunning: Boolean get() = active.get() && process?.isAlive == true

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        stop()
        return try {
            val nativeDir = File(app.applicationInfo.nativeLibraryDir)
            val tor = File(nativeDir, "libtor.so")
            require(tor.isFile && tor.canExecute()) { "Tor native runtime is missing for this ABI" }
            val torDir = File(app.filesDir, "tor").apply { mkdirs() }
            val dataDir = File(app.filesDir, "tor-data").apply { mkdirs() }
            val bridgeFile = File(torDir, "bridges_default.lst")
            if (!bridgeFile.isFile || bridgeFile.length() == 0L) {
                app.assets.open("tor/bridges_default.lst").use { input ->
                    bridgeFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            listOf("geoip", "geoip6").forEach { name ->
                val database = File(torDir, name)
                if (!database.isFile || database.length() == 0L) {
                    // GeoIP assets are shipped as verified raw databases.
                    app.assets.open("tor/$name").use { input ->
                        database.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            val torrc = File(torDir, "torrc")
            torrc.writeText(TorConfigBuilder.build(settings, nativeDir, dataDir, torDir))
            torrc.setReadable(false, false)
            torrc.setReadable(true, true)
            torrc.setWritable(false, false)
            torrc.setWritable(true, true)

            val command = listOf(tor.absolutePath, "-f", torrc.absolutePath, "--pidfile", File(dataDir, "tor.pid").absolutePath)
            val child = ProcessBuilder(command)
                .directory(dataDir)
                .redirectErrorStream(true)
                .apply { environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath }
                .start()
            process = child
            active.set(true)
            outputThread = Thread({
                var reached100 = false
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            BOOTSTRAP.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { pct ->
                                runCatching { onProgress(pct.coerceIn(0, 100)) }
                                if (pct >= 100) reached100 = true
                            }
                        }
                    }
                } catch (_: Exception) { }
                val code = runCatching { child.waitFor() }.getOrDefault(-1)
                if (active.compareAndSet(true, false)) {
                    process = null
                    onStopped(if (reached100) "Tor stopped (exit $code)" else "Tor exited before bootstrap completed (exit $code)")
                }
            }, "rooh-tor-output").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            active.set(false)
            process = null
            onStopped(e.message ?: "Tor failed to start")
            false
        }
    }

    fun awaitSocksReady(timeoutMs: Long = 15_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline && isRunning) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", settings.socksPort), 400)
                    socket.soTimeout = 500
                    socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                    socket.getOutputStream().flush()
                    return socket.getInputStream().read() == 0x05 && socket.getInputStream().read() == 0x00
                }
            } catch (_: Exception) {
                Thread.sleep(200)
            }
        }
        return false
    }

    @Synchronized
    fun stop(): Boolean {
        active.set(false)
        val child = process
        process = null
        if (child == null) return true
        return try {
            child.destroy()
            if (!child.waitFor(2, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                child.waitFor(2, TimeUnit.SECONDS)
            } else true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        val BOOTSTRAP = Regex("""Bootstrapped (\d{1,3})%""")
    }
}
