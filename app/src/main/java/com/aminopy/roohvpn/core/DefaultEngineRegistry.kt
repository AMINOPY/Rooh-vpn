package com.aminopy.roohvpn.core

import android.content.Context
import com.aminopy.roohvpn.BuildConfig

/**
 * Registers only engines whose native runtime is packaged in this product flavor.
 *
 * Xray and sing-box ship overlapping gomobile classes, so they must never be
 * registered together in one APK. WireGuard uses its own official Android
 * tunnel library and is available in both flavors. PG CORE and Aether remain
 * unavailable until their complete Android data planes pass device validation.
 */
object DefaultEngineRegistry {
    fun create(context: Context): EngineRegistry {
        val appContext = context.applicationContext
        val backends = when (BuildConfig.FLAVOR) {
            "xray" -> listOf(
                XrayEngineBackend(appContext),
                WireGuardEngineBackend(appContext)
            )
            "singbox" -> listOf(
                SingBoxEngineBackend(appContext),
                WireGuardEngineBackend(appContext)
            )
            else -> emptyList()
        }
        return EngineRegistry(backends)
    }
}
