package com.aminopy.roohvpn.server

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpsServerProfileProviderTest {
    private val candidate = ServerCandidate(
        id = "server-eu-1",
        host = "vpn.example.org",
        port = 443,
        region = "EU",
        engineId = "wireguard"
    )

    @Test
    fun acceptsMatchingShortLivedAuthorizedProfilePayload() {
        val body = response(
            serverId = candidate.id,
            engine = "WIREGUARD",
            expiresAt = 1_500L,
            configuration = "[Interface]\nPrivateKey = redacted"
        )

        val parsed = HttpsServerProfileProvider.parseProfileResponse(body, candidate, 1_000L)

        assertArrayEquals("[Interface]\nPrivateKey = redacted".toByteArray(), parsed)
    }

    @Test
    fun rejectsExpiredProfile() {
        val body = response(candidate.id, "wireguard", 999L, "config")
        assertNull(HttpsServerProfileProvider.parseProfileResponse(body, candidate, 1_000L))
    }

    @Test
    fun rejectsProfileForAnotherServer() {
        val body = response("other-server", "wireguard", 1_500L, "config")
        assertNull(HttpsServerProfileProvider.parseProfileResponse(body, candidate, 1_000L))
    }

    @Test
    fun rejectsProfileForAnotherEngine() {
        val body = response(candidate.id, "xray", 1_500L, "config")
        assertNull(HttpsServerProfileProvider.parseProfileResponse(body, candidate, 1_000L))
    }

    @Test
    fun rejectsProfileWithExcessiveLifetime() {
        val body = response(candidate.id, "wireguard", 100_000L, "config")
        assertNull(HttpsServerProfileProvider.parseProfileResponse(body, candidate, 1_000L))
    }

    @Test
    fun rejectsOversizedConfiguration() {
        val body = response(candidate.id, "wireguard", 1_500L, "x".repeat(64 * 1024 + 1))
        assertNull(HttpsServerProfileProvider.parseProfileResponse(body, candidate, 1_000L))
    }

    @Test
    fun rejectsUnsupportedResponseVersion() {
        val body = response(candidate.id, "wireguard", 1_500L, "config", version = 2)
        assertNull(HttpsServerProfileProvider.parseProfileResponse(body, candidate, 1_000L))
    }

    private fun response(
        serverId: String,
        engine: String,
        expiresAt: Long,
        configuration: String,
        version: Int = 1
    ): String = JSONObject()
        .put("version", version)
        .put("serverId", serverId)
        .put("engine", engine)
        .put("expiresAtEpochSeconds", expiresAt)
        .put("configuration", configuration)
        .toString()
}
