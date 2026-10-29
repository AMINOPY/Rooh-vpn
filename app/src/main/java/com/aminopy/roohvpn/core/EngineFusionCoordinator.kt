package com.aminopy.roohvpn.core

/**
 * Resolves engine roles before any native engine is started.
 *
 * This is the RØH orchestration policy, separate from PG CORE's flow selector.
 * It prevents conflicting TUN ownership and refuses to call an unverified
 * backend operational. It does not forward packets or create a bonded tunnel.
 */
enum class FusionEngineRole {
    PRIMARY,
    HELPER,
    TRANSPORT
}

data class FusionEngineDescriptor(
    val id: EngineId,
    val role: FusionEngineRole,
    val available: Boolean,
    val dataPlaneVerified: Boolean,
    val ownsTun: Boolean,
    val supportsIpv4: Boolean,
    val supportsIpv6: Boolean,
    val supportsDns: Boolean,
    val priority: Int = 100
)

data class FusionRequest(
    val primaryEngine: EngineId = EngineId.XRAY,
    val helperEngines: Set<EngineId> = setOf(EngineId.PG_CORE),
    val transportEngines: Set<EngineId> = emptySet(),
    val requireIpv4: Boolean = true,
    val requireIpv6: Boolean = true,
    val requireDns: Boolean = true
)

sealed class FusionResolution {
    data class Plan(
        val primary: FusionEngineDescriptor,
        val helpers: List<FusionEngineDescriptor>,
        val transports: List<FusionEngineDescriptor>,
        val warnings: List<String>
    ) : FusionResolution()

    data class Blocked(val code: String, val safeMessage: String) : FusionResolution()
}

/**
 * Deterministic, fail-closed role resolver for the RØH multi-engine design.
 *
 * Xray is the default parent engine. Exactly one selected participant may own
 * Android's TUN. Helpers must not own a second TUN, and no engine is accepted
 * as a traffic participant until its real data plane has been verified.
 */
object EngineFusionCoordinator {
    fun resolve(
        request: FusionRequest,
        participants: Collection<FusionEngineDescriptor>
    ): FusionResolution {
        if (request.primaryEngine != EngineId.XRAY) {
            return blocked("FUSION_PRIMARY_UNSUPPORTED", "Xray is the configured parent engine.")
        }
        val byId = participants.groupBy { it.id }
        if (byId.values.any { it.size != 1 }) {
            return blocked("FUSION_DUPLICATE_ENGINE", "An engine has conflicting registrations.")
        }
        val descriptors = byId.mapValues { it.value.single() }
        val primary = descriptors[request.primaryEngine]
            ?: return blocked("FUSION_PRIMARY_MISSING", "The Xray parent engine is not registered.")
        if (!primary.available || !primary.dataPlaneVerified) {
            return blocked("FUSION_PRIMARY_UNVERIFIED", "The Xray data path is not verified for traffic.")
        }
        if (primary.role != FusionEngineRole.PRIMARY || !primary.ownsTun) {
            return blocked("FUSION_TUN_OWNER_INVALID", "Xray must be the sole owner of the Android TUN.")
        }
        if (!supportsRequirements(primary, request)) {
            return blocked("FUSION_PRIMARY_CAPABILITY_GAP", "Xray does not satisfy the required tunnel capabilities.")
        }

        val warnings = mutableListOf<String>()
        val helpers = mutableListOf<FusionEngineDescriptor>()
        for (id in request.helperEngines.sortedBy { it.name }) {
            if (id == request.primaryEngine) {
                return blocked("FUSION_ROLE_CONFLICT", "The primary engine cannot also be registered as a helper.")
            }
            val helper = descriptors[id]
            when {
                helper == null -> warnings += "$id helper is not registered and was not activated."
                !helper.available -> warnings += "$id helper is unavailable and was not activated."
                !helper.dataPlaneVerified -> warnings += "$id helper has no verified data path and was not activated."
                helper.role != FusionEngineRole.HELPER -> warnings += "$id is not declared as a helper and was not activated."
                helper.ownsTun -> warnings += "$id cannot own a second Android TUN and was not activated."
                else -> helpers += helper
            }
        }

        val transports = mutableListOf<FusionEngineDescriptor>()
        for (id in request.transportEngines.sortedBy { it.name }) {
            if (id == request.primaryEngine || id in request.helperEngines) {
                return blocked("FUSION_ROLE_CONFLICT", "An engine cannot occupy multiple fusion roles.")
            }
            val transport = descriptors[id]
            when {
                transport == null -> warnings += "$id transport is not registered and was not activated."
                !transport.available -> warnings += "$id transport is unavailable and was not activated."
                !transport.dataPlaneVerified -> warnings += "$id transport has no verified data path and was not activated."
                transport.role != FusionEngineRole.TRANSPORT -> warnings += "$id is not declared as a transport and was not activated."
                transport.ownsTun -> warnings += "$id cannot own a second Android TUN and was not activated."
                else -> transports += transport
            }
        }

        val selected = listOf(primary) + helpers + transports
        if (selected.count { it.ownsTun } != 1) {
            return blocked("FUSION_TUN_OWNER_CONFLICT", "Fusion requires exactly one Android TUN owner.")
        }
        return FusionResolution.Plan(primary, helpers, transports, warnings)
    }

    private fun supportsRequirements(engine: FusionEngineDescriptor, request: FusionRequest): Boolean =
        (!request.requireIpv4 || engine.supportsIpv4) &&
            (!request.requireIpv6 || engine.supportsIpv6) &&
            (!request.requireDns || engine.supportsDns)

    private fun blocked(code: String, message: String) = FusionResolution.Blocked(code, message)
}
