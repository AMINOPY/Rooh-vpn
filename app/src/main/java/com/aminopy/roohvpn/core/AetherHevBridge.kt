package com.aminopy.roohvpn.core

import studio.cluvex.aether.core.TProxyService

/**
 * RØH lifecycle facade over the pinned upstream HEV JNI bridge.
 * The actual native event loop runs on HEV's own pthread, never an ART thread.
 */
internal object AetherHevBridge {
    fun start(configPath: String, tunFd: Int): Boolean =
        TProxyService.TProxyStartService(configPath, tunFd)

    fun stop() = TProxyService.TProxyStopService()

    fun isRunning(): Boolean = TProxyService.isRunning()
}
