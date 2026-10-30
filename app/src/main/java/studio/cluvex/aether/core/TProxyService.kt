package studio.cluvex.aether.core

/**
 * Compatibility surface for Aether's pinned libaethertun.so JNI symbols.
 *
 * Keep this exact package, object name, and external method names in sync with
 * scripts/aethertun-jni.c. The native tunnel loop must remain on its own
 * pthread; calling hev directly on an ART-managed thread can corrupt ART's
 * stack and crash the process under traffic.
 */
internal object TProxyService {
    init {
        System.loadLibrary("aethertun")
    }

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService()
    @JvmStatic external fun isRunning(): Boolean
}
