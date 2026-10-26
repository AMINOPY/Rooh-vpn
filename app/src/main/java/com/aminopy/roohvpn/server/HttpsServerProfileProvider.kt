package com.aminopy.roohvpn.server

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/** Supplies a short-lived bearer token for an authorized profile request. */
fun interface ServerProfileAuthorization {
    fun tokenFor(candidate: ServerCandidate): String?
}

/**
 * Fetches one short-lived engine-specific profile from the trusted RØH service.
 * This is a client contract only; it does not create or host the service.
 */
class HttpsServerProfileProvider(
    baseUrl: String,
    private val authorization: ServerProfileAuthorization,
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 4_000,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) : ServerProfileProvider {
    private val base: URI

    init {
        require(connectTimeoutMs in 250..15_000)
        require(readTimeoutMs in 250..15_000)
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "A trusted profile service URL is required" }
        val parsed = URI(normalized)
        require(parsed.scheme.equals("https", ignoreCase = true)) {
            "Profile service must use HTTPS"
        }
        require(!parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
            parsed.rawQuery == null && parsed.rawFragment == null
        ) {
            "Profile service URL must contain only an HTTPS host and optional path"
        }
        require(parsed.path.split('/').none { it == "." || it == ".." }) {
            "Profile service URL path must not contain dot segments"
        }
        base = parsed
    }

    override fun fetchConfiguration(candidate: ServerCandidate): ByteArray {
        // ServerCandidate constrains IDs to URL-safe path-segment characters.
        val path = base.path.trimEnd('/') + "/v1/servers/${candidate.id}/profile"
        val endpoint = URI(
            base.scheme, null, base.host, base.port, path, null, null
        ).toURL()
        val token = authorization.tokenFor(candidate)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN_CHARS &&
                it.none { ch -> ch <= ' ' || ch.code == 127 } }
            ?: throw IllegalStateException("Profile authorization is unavailable")

        val connection = endpoint.openConnection() as? HttpsURLConnection
            ?: throw IllegalStateException("HTTPS connection could not be created")
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("User-Agent", "ROH-VPN-Android")

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Profile service returned HTTP $status")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_RESPONSE_BYTES) { "Profile response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val body = String(bytes, StandardCharsets.UTF_8)
            return parseProfileResponse(body, candidate, nowEpochSeconds())
                ?: throw IllegalArgumentException("Profile response failed validation")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_TOKEN_CHARS = 4096
        private const val MAX_RESPONSE_BYTES = 96 * 1024
        private const val MAX_PROFILE_BYTES = 64 * 1024
        private const val MAX_PROFILE_TTL_SECONDS = 24 * 60 * 60L

        internal fun parseProfileResponse(
            body: String,
            candidate: ServerCandidate,
            nowEpochSeconds: Long
        ): ByteArray? = runCatching {
            require(body.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_BYTES)
            val root = JSONObject(body)
            require(root.optInt("version", -1) == API_VERSION)
            require(root.optString("serverId") == candidate.id)
            require(normalizeEngine(root.optString("engine")) == normalizeEngine(candidate.engineId))
            val expiresAt = root.optLong("expiresAtEpochSeconds", -1L)
            require(expiresAt > nowEpochSeconds)
            require(expiresAt <= nowEpochSeconds + MAX_PROFILE_TTL_SECONDS)
            require(root.opt("configuration") is String)
            val configuration = root.getString("configuration").toByteArray(StandardCharsets.UTF_8)
            require(configuration.isNotEmpty() && configuration.size <= MAX_PROFILE_BYTES)
            configuration
        }.getOrNull()

        private fun normalizeEngine(value: String): String =
            value.trim().lowercase().replace('_', '-').replace(" ", "")

        private const val API_VERSION = 1
    }
}
