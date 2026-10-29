package com.aminopy.roohvpn.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * HTTPS server-directory client. Supply the official RØH service URL from
 * trusted app configuration; no default endpoint is hard-coded into source.
 *
 * Run on a background thread. The default TLS certificate/hostname validation
 * is intentionally retained. A directory response contains public endpoint
 * metadata only, never credentials or private keys.
 */
class HttpsServerDirectory(
    baseUrl: String,
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 4_000
) : CancellableServerDirectory {
    private val endpoint: URL

    init {
        require(connectTimeoutMs in 250..15_000)
        require(readTimeoutMs in 250..15_000)
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "A trusted server-directory URL is required" }

        val base = URI(normalized)
        require(base.scheme.equals("https", ignoreCase = true)) {
            "Server directory must use HTTPS"
        }
        require(!base.host.isNullOrBlank() && base.rawUserInfo == null &&
            base.rawQuery == null && base.rawFragment == null
        ) {
            "Server directory URL must contain only an HTTPS host and optional path"
        }
        require(base.path.split('/').none { it == "." || it == ".." }) {
            "Server directory URL path must not contain dot segments"
        }
        endpoint = URI(
            base.scheme,
            null,
            base.host,
            base.port,
            base.path.trimEnd('/') + "/v1/servers",
            null,
            null
        ).toURL()
    }

    override fun discover(cancellation: AutoConnectCancellation): List<ServerCandidate> {
        if (cancellation.isCancelled()) throw InterruptedException("Discovery cancelled")
        val connection = endpoint.openConnection() as? HttpsURLConnection
            ?: throw IllegalStateException("HTTPS connection could not be created")
        val cancellationRegistration = cancellation.onCancel { connection.disconnect() }
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "ROH-VPN-Android")

        try {
            if (cancellation.isCancelled()) throw InterruptedException("Discovery cancelled")
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Server directory returned HTTP $code")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_RESPONSE_BYTES) { "Server directory response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (cancellation.isCancelled()) throw InterruptedException("Discovery cancelled")
            val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
            require(root.optInt("version", -1) == API_VERSION) {
                "Unsupported server directory response version"
            }
            val servers = root.optJSONArray("servers")
                ?: throw IllegalArgumentException("Server directory response has no servers array")

            return parseCandidates(servers)
        } finally {
            cancellationRegistration.close()
            connection.disconnect()
        }
    }

    private fun parseCandidates(array: JSONArray): List<ServerCandidate> {
        require(array.length() <= MAX_CANDIDATES) { "Too many server candidates" }
        val result = ArrayList<ServerCandidate>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val candidate = runCatching {
                ServerCandidate(
                    id = item.getString("id"),
                    host = item.getString("host"),
                    port = item.getInt("port"),
                    region = item.getString("region"),
                    engineId = item.getString("engine"),
                    priority = item.optInt("priority", 0)
                )
            }.getOrNull() ?: continue
            // Avoid accidental malformed values and excessively long labels.
            if (candidate.id.length > 128 ||
                candidate.host.length > 253 ||
                candidate.region.length > 80 ||
                candidate.engineId.length > 40
            ) continue
            result += candidate
        }
        return result
    }

    private companion object {
        const val API_VERSION = 1
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MAX_CANDIDATES = 500
    }
}
