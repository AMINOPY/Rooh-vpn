package com.aminopy.roohvpn.core

/**
 * Only fully integrated and tested backends should be registered here.
 * Keeping the registry empty is safer than advertising a backend that cannot
 * establish a real, verified traffic path.
 */
class EngineRegistry(backends: Collection<EngineBackend> = emptyList()) {
    init {
        require(backends.map(EngineBackend::id).distinct().size == backends.size) {
            "Each engine ID may be registered only once"
        }
    }

    private val byId = backends.associateBy(EngineBackend::id)

    fun availableEngines(): Set<EngineId> = byId.keys

    fun backend(id: EngineId): EngineBackend? = byId[id]
}

/**
 * Builds the set of engines actually packaged in this APK. Aether and PG CORE
 * are intentionally omitted until they have verified end-to-end packet
 * forwarding implementations; UI visibility must not imply a working tunnel.
 */
object DefaultEngineRegistry {
    fun create(context: android.content.Context): EngineRegistry {
        val appContext = context.applicationContext
        val backends = when (com.aminopy.roohvpn.BuildConfig.FLAVOR) {
            "singbox" -> listOf(
                SingBoxEngineBackend(appContext),
                WireGuardEngineBackend(appContext)
            )
            "multi" -> listOf(
                XrayEngineBackend(appContext),
                SingBoxEngineBackend(appContext),
                WireGuardEngineBackend(appContext)
            )
            else -> listOf(
                XrayEngineBackend(appContext),
                WireGuardEngineBackend(appContext)
            )
        }
        return EngineRegistry(backends)
    }
}
