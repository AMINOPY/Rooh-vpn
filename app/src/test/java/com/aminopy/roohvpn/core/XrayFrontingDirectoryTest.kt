package com.aminopy.roohvpn.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class XrayFrontingDirectoryTest {
    private fun profile(): String = """
        {
          "outbounds": [{
            "tag": "proxy",
            "protocol": "vless",
            "settings": {
              "vnext": [{
                "address": "origin.example.net",
                "port": 443,
                "users": [{"id": "00000000-0000-0000-0000-000000000001", "encryption": "none"}]
              }]
            },
            "streamSettings": {
              "network": "ws",
              "security": "tls",
              "wsSettings": {"path": "/tunnel", "host": "origin.example.net"},
              "tlsSettings": {"serverName": "origin.example.net", "allowInsecure": false}
            }
          }]
        }
    """.trimIndent()

    private fun signedEnvelope(now: Long): Pair<ByteArray, String> {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = JSONObject()
            .put("version", 1)
            .put("expiresAt", now + 3600)
            .put("candidates", JSONArray().put(
                JSONObject()
                    .put("id", "front-1")
                    .put("profile", profile())
                    .put("sni", "front.example.com")
                    .put("edgeIp", "192.0.2.10")
                    .put("host", "origin.example.net")
                    .put("expiresAt", now + 1800)
                    .put("priority", 10)
            ))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(payload)
        val envelope = JSONObject()
            .put("payload", Base64.getEncoder().encodeToString(payload))
            .put("signature", Base64.getEncoder().encodeToString(signer.sign()))
            .toString()
            .toByteArray(Charsets.UTF_8)
        return envelope to Base64.getEncoder().encodeToString(pair.public.encoded)
    }

    @Test fun acceptsPinnedSignedDirectoryAndValidatesFrontingProfile() {
        val now = 1_800_000_000L
        val (envelope, publicKey) = signedEnvelope(now)
        val result = XrayFrontingDirectory.parseSigned(envelope, publicKey, now)
        assertTrue(result.errorCode ?: "unexpected directory error", result.isSuccess)
        assertEquals(1, result.candidates.size)
        assertEquals("front-1", result.candidates.single().id)
        assertTrue(result.candidates.single().profile.isNotEmpty())
    }

    @Test fun rejectsTamperedDirectorySignature() {
        val now = 1_800_000_000L
        val (envelope, publicKey) = signedEnvelope(now)
        // The signature covers the decoded payload bytes, not the Base64
        // envelope text. Mutate the signed payload while retaining its original
        // signature so this test exercises signature verification itself.
        val envelopeJson = JSONObject(envelope.toString(Charsets.UTF_8))
        val payload = Base64.getDecoder().decode(envelopeJson.getString("payload"))
        val changedPayload = payload.toString(Charsets.UTF_8)
            .replace("front-1", "front-2")
            .toByteArray(Charsets.UTF_8)
        val tampered = JSONObject()
            .put("payload", Base64.getEncoder().encodeToString(changedPayload))
            .put("signature", envelopeJson.getString("signature"))
            .toString()
            .toByteArray(Charsets.UTF_8)
        payload.fill(0)
        changedPayload.fill(0)
        val result = XrayFrontingDirectory.parseSigned(tampered, publicKey, now)
        assertFalseResult(result.errorCode)
    }

    @Test fun rejectsExpiredDirectory() {
        val now = 1_800_000_000L
        val (envelope, publicKey) = signedEnvelope(now - 7200)
        val result = XrayFrontingDirectory.parseSigned(envelope, publicKey, now)
        assertEquals("DIRECTORY_EXPIRED", result.errorCode)
    }

    @Test fun choosesOnlyFreshMeasuredConnectableCandidates() {
        val now = 1_800_000_000_000L
        val valid = candidate("fast", now / 1000 + 300, 5)
        val stale = candidate("stale", now / 1000 + 300, 1)
        val disconnected = candidate("down", now / 1000 + 300, 2)
        val result = XrayFrontingDirectory.choose(
            listOf(
                XrayFrontingDirectory.MeasuredCandidate(stale, 1, now - 120_000, true),
                XrayFrontingDirectory.MeasuredCandidate(disconnected, 2, now - 100, false),
                XrayFrontingDirectory.MeasuredCandidate(valid, 42, now - 100, true)
            ),
            now,
            now / 1000
        )
        assertNotNull(result)
        assertEquals("fast", result?.id)
    }

    @Test fun rejectsMissingOrStaleLatencyInsteadOfTreatingItAsZero() {
        val now = 1_800_000_000_000L
        val candidate = candidate("unmeasured", now / 1000 + 300, 1)
        assertNull(XrayFrontingDirectory.choose(
            listOf(XrayFrontingDirectory.MeasuredCandidate(candidate, 0, now, true)),
            now,
            now / 1000
        ))
    }

    private fun candidate(id: String, expiry: Long, priority: Int) =
        XrayFrontingDirectory.Candidate(
            id = id,
            profile = profile().toByteArray(Charsets.UTF_8),
            sni = "front.example.com",
            cdnEdgeIp = "192.0.2.10",
            hostHeader = "origin.example.net",
            expiresAtEpochSeconds = expiry,
            priority = priority
        )

    private fun assertFalseResult(errorCode: String?) {
        assertNotNull("invalid signature must be rejected", errorCode)
        assertTrue(errorCode == "SIGNATURE_INVALID" || errorCode == "DIRECTORY_INVALID")
    }
}
