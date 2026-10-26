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
