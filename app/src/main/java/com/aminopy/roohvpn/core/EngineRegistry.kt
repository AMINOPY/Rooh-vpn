package com.aminopy.roohvpn.core

/**
 * Only fully integrated and tested backends should be registered here.
 * Keeping the registry empty is safer than advertising a backend that cannot
 * establish a real, verified traffic path.
 */
class EngineRegistry(backends: Collection<EngineBackend> = emptyList()) {
    private val byId = backends.associateBy(EngineBackend::id)

    fun availableEngines(): Set<EngineId> = byId.keys

    fun backend(id: EngineId): EngineBackend? = byId[id]
}
