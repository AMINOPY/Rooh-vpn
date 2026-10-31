package com.aminopy.roohvpn.core

/**
 * Produces transparent, user-actionable connection guidance.
 *
 * This advisor never changes engine state, retries, rewrites profiles, or
 * claims that a RUNNING engine proves end-to-end traffic is working.
 */
object ConnectionHealthAdvisor {
    enum class Severity { INFO, WARNING, ERROR }
    enum class Action { WAIT, CHECK_PROFILE, RETRY_MANUALLY, RECONNECT_MANUALLY, CONTACT_PROVIDER, NONE }

    data class Report(
        val severity: Severity,
        val title: String,
        val detail: String,
        val action: Action,
        val trafficVerified: Boolean = false
    )

    fun evaluate(
        status: EngineStatus?,
        failure: EngineFailure? = null,
        operationInProgress: Boolean = false
    ): Report {
        if (operationInProgress) {
            return Report(
                Severity.INFO,
                "Connection change in progress",
                "Wait for the current engine operation to finish before taking another action.",
                Action.WAIT
            )
        }

        if (failure != null) {
            val action = when {
                failure.retryable -> Action.RETRY_MANUALLY
                failure.code.contains("PROFILE", ignoreCase = true) ||
                    failure.code.contains("CONFIG", ignoreCase = true) -> Action.CHECK_PROFILE
                failure.code.contains("UPSTREAM", ignoreCase = true) ||
                    failure.code.contains("UNAVAILABLE", ignoreCase = true) -> Action.CONTACT_PROVIDER
                else -> Action.CHECK_PROFILE
            }
            return Report(
                Severity.ERROR,
                "Connection could not be confirmed",
                failure.safeMessage,
                action
            )
        }

        return when (status?.state) {
            EngineState.RUNNING -> Report(
                Severity.INFO,
                "Engine reports running",
                "The engine reports an active state. End-to-end internet access, DNS, IPv4/IPv6, and leak protection have not been verified by this status alone.",
                Action.NONE,
                trafficVerified = false
            )
            EngineState.STARTING, EngineState.STOPPING -> Report(
                Severity.INFO,
                "Engine is changing state",
                "Wait for the engine to finish starting or stopping.",
                Action.WAIT
            )
            EngineState.STOPPED -> Report(
                Severity.WARNING,
                "VPN is disconnected",
                "No active engine is reported. Import or select a trusted profile, then connect manually.",
                Action.RECONNECT_MANUALLY
            )
            EngineState.FAILED -> Report(
                Severity.ERROR,
                "Engine reported a failure",
                "Review the selected profile and engine availability before trying again.",
                Action.CHECK_PROFILE
            )
            null -> Report(
                Severity.WARNING,
                "Connection status unavailable",
                "The app has no reliable engine status to display. Do not assume that traffic is protected.",
                Action.CHECK_PROFILE
            )
        }
    }
}
