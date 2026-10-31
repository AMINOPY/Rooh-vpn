package com.aminopy.roohvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionHealthAdvisorTest {
    @Test
    fun runningEngineDoesNotClaimTrafficVerification() {
        val report = ConnectionHealthAdvisor.evaluate(
            EngineStatus(EngineId.XRAY, EngineState.RUNNING)
        )
        assertEquals(ConnectionHealthAdvisor.Severity.INFO, report.severity)
        assertEquals(ConnectionHealthAdvisor.Action.NONE, report.action)
        assertFalse(report.trafficVerified)
    }

    @Test
    fun operationInProgressTakesPriority() {
        val report = ConnectionHealthAdvisor.evaluate(
            EngineStatus(EngineId.XRAY, EngineState.RUNNING),
            operationInProgress = true
        )
        assertEquals(ConnectionHealthAdvisor.Action.WAIT, report.action)
    }

    @Test
    fun retryableFailureSuggestsManualRetryOnly() {
        val report = ConnectionHealthAdvisor.evaluate(
            null,
            EngineFailure("TEMPORARY_FAILURE", "The engine could not start.", true)
        )
        assertEquals(ConnectionHealthAdvisor.Severity.ERROR, report.severity)
        assertEquals(ConnectionHealthAdvisor.Action.RETRY_MANUALLY, report.action)
    }

    @Test
    fun profileFailureSuggestsReviewingProfile() {
        val report = ConnectionHealthAdvisor.evaluate(
            null,
            EngineFailure("PROFILE_REJECTED", "The profile was rejected.", false)
        )
        assertEquals(ConnectionHealthAdvisor.Action.CHECK_PROFILE, report.action)
    }

    @Test
    fun missingStatusNeverClaimsConnected() {
        val report = ConnectionHealthAdvisor.evaluate(null)
        assertEquals(ConnectionHealthAdvisor.Severity.WARNING, report.severity)
        assertFalse(report.trafficVerified)
    }
}
