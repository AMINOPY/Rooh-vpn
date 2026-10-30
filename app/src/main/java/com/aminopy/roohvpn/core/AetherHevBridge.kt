package com.aminopy.roohvpn.core

/**
 * JNI facade for the RØH-specific native pthread bridge to hev-socks5-tunnel.
 * Do not move or rename this class: its package is part of the exported JNI ABI.
 */
internal object AetherHevBridge {
    init {
        System.loadLibrary("roohvpnhev")
    }

    @JvmStatic external fun start(configPath: String, tunFd: Int): Boolean
    @JvmStatic external fun stop()
    @JvmStatic external fun isRunning(): Boolean
}
