package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFusionCoordinatorTest {
    private fun xray(
        available: Boolean = true,
        verified: Boolean = true,
        ownsTun: Boolean = true,
        role: FusionEngineRole = FusionEngineRole.PRIMARY
    ) = FusionEngineDescriptor(
        id = EngineId.XRAY,
        role = role,
        available = available,
        dataPlaneVerified = verified,
        ownsTun = ownsTun,
        supportsIpv4 = true,
        supportsIpv6 = true,
        supportsDns = true,
        priority = 0
    )

    private fun pg(
        available: Boolean = true,
        verified: Boolean = true,
        ownsTun: Boolean = false,
        role: FusionEngineRole = FusionEngineRole.HELPER
    ) = FusionEngineDescriptor(
        id = EngineId.PG_CORE,
        role = role,
        available = available,
        dataPlaneVerified = verified,
        ownsTun = ownsTun,
        supportsIpv4 = true,
        supportsIpv6 = true,
        supportsDns = true
    )

    @Test
    fun xrayIsPrimaryAndUnverifiedPgHelperIsNotActivated() {
        val result = EngineFusionCoordinator.resolve(
            FusionRequest(),
            listOf(xray(), pg(verified = false))
        ) as FusionResolution.Plan

        assertEquals(EngineId.XRAY, result.primary.id)
        assertTrue(result.helpers.isEmpty())
        assertTrue(result.warnings.any { it.contains("no verified data path") })
    }

    @Test
    fun verifiedPgHelperMayCooperateWithoutOwningAnotherTun() {
        val result = EngineFusionCoordinator.resolve(
            FusionRequest(),
            listOf(xray(), pg())
        ) as FusionResolution.Plan

        assertEquals(listOf(EngineId.PG_CORE), result.helpers.map { it.id })
        assertEquals(1, (listOf(result.primary) + result.helpers).count { it.ownsTun })
    }

    @Test
    fun refusesUnverifiedXrayParent() {
        val result = EngineFusionCoordinator.resolve(
            FusionRequest(),
            listOf(xray(verified = false), pg())
        ) as FusionResolution.Blocked

        assertEquals("FUSION_PRIMARY_UNVERIFIED", result.code)
    }

    @Test
    fun refusesSecondTunOwnerInsteadOfStartingConflictingVpnServices() {
        val result = EngineFusionCoordinator.resolve(
            FusionRequest(),
            listOf(xray(), pg(ownsTun = true))
        ) as FusionResolution.Plan

        assertTrue(result.helpers.isEmpty())
        assertTrue(result.warnings.any { it.contains("second Android TUN") })
    }

    @Test
    fun duplicateEngineRegistrationsAreRejected() {
        val result = EngineFusionCoordinator.resolve(
            FusionRequest(),
            listOf(xray(), xray())
        ) as FusionResolution.Blocked

        assertEquals("FUSION_DUPLICATE_ENGINE", result.code)
    }

    @Test
    fun xrayMustSatisfyRequestedDnsAndIpCapabilities() {
        val limited = xray().copy(supportsIpv6 = false)
        val result = EngineFusionCoordinator.resolve(
            FusionRequest(requireIpv6 = true),
            listOf(limited)
        ) as FusionResolution.Blocked

        assertEquals("FUSION_PRIMARY_CAPABILITY_GAP", result.code)
    }

    @Test
    fun nonXrayParentIsRejected() {
        val result = EngineFusionCoordinator.resolve(
            FusionRequest(primaryEngine = EngineId.SING_BOX),
            listOf(xray())
        ) as FusionResolution.Blocked

        assertEquals("FUSION_PRIMARY_UNSUPPORTED", result.code)
    }
}
