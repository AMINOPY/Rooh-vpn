package com.aminopy.roohvpn.core

import android.content.Context
import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import org.json.JSONObject

/**
 * Process-wide initialization for the pinned libbox binding.
 *
 * This initializes the native runtime only. It does not start a proxy, create a
 * TUN interface, or mean that a VPN connection is active.
 */
object SingBoxLibboxRuntime {
    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context): EngineFailure? {
        if (initialized) return null

        val appContext = context.applicationContext
        return try {
            val baseDir = appContext.filesDir
            val workingDir = appContext.getExternalFilesDir(null) ?: baseDir
            val tempDir = appContext.cacheDir

            check(baseDir.mkdirs() || baseDir.isDirectory) { "base directory unavailable" }
            check(workingDir.mkdirs() || workingDir.isDirectory) { "working directory unavailable" }
            check(tempDir.mkdirs() || tempDir.isDirectory) { "temporary directory unavailable" }

            val options = SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workingDir.absolutePath
                tempPath = tempDir.absolutePath
                fixAndroidStack = false
                logMaxLines = 1000
                debug = false
                appVersion = "0.1.0"
                appMarketingVersion = "0.1.0"
                platformMetadata = JSONObject().apply {
                    put("os", "Android")
                    put("sdk", Build.VERSION.SDK_INT)
                }.toString()
            }

            Libbox.setup(options)
            initialized = true
            null
        } catch (_: LinkageError) {
            EngineFailure(
                code = "SING_BOX_NATIVE_UNAVAILABLE",
                safeMessage = "The sing-box native component is unavailable or incompatible.",
                retryable = false
            )
        } catch (_: Exception) {
            EngineFailure(
                code = "SING_BOX_INITIALIZATION_FAILED",
                safeMessage = "The sing-box native runtime could not be initialized.",
                retryable = true
            )
        }
    }

    fun isInitialized(): Boolean = initialized
}
