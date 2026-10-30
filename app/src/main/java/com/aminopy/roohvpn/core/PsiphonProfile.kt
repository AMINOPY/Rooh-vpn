package com.aminopy.roohvpn.core

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

/** User-selected Psiphon egress preferences. Empty country means automatic region selection. */
data class PsiphonProfile(
    val country: String = "",
    val mode: Mode = Mode.AUTO,
    val cdnIps: String = "",
    val cdnSni: String = ""
) {
    enum class Mode { AUTO, CDN, DIRECT }

    fun toConfig(dataDirectory: String): String {
        val region = country.trim().uppercase()
        require(region.isEmpty() || region in REGIONS) { "Unsupported Psiphon region" }
        val ips = cdnIps.split(Regex("[\\s,;]+")).mapNotNull(::ipv4).distinct()
        val snis = cdnSni.split(Regex("[\\s,;]+")).mapNotNull(::hostname).distinct()
        if (mode == Mode.CDN && ips.isEmpty() && snis.isEmpty()) {
            // The official built-in CDN scan remains available when no custom values are supplied.
        }

        val root = JSONObject()
            .put("PropagationChannelId", PROPAGATION_CHANNEL_ID)
            .put("SponsorId", SPONSOR_ID)
            .put("ClientVersion", "1")
            .put("DataRootDirectory", dataDirectory)
            .put("LocalSocksProxyPort", 0)
            .put("LocalHttpProxyPort", 0)
            .put("EmitDiagnosticNotices", true)
            .put("EmitDiagnosticNetworkParameters", false)
            .put("InproxyTunnelProtocolPreferProbability", 0.0)
            .put("InproxyTunnelProtocolSelectionProbability", 0.0)
        if (region.isNotEmpty()) root.put("EgressRegion", region)

        val serverList = JSONArray().put(
            JSONObject().put("URL", Base64.encodeToString(SERVER_LIST_URL.toByteArray(), Base64.NO_WRAP))
        )
        root.put("RemoteServerListURLs", serverList)
        root.put("RemoteServerListSignaturePublicKey", SERVER_LIST_SIGNATURE_KEY)

        val cdnProtocols = JSONArray(listOf(
            "FRONTED-MEEK-CDN-OSSH",
            "FRONTED-MEEK-CDN-HTTP-OSSH",
            "FRONTED-MEEK-CDN-QUIC-OSSH"
        ))
        val directProtocols = JSONArray(listOf(
            "SSH", "OSSH", "TLS-OSSH",
            "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH",
            "UNFRONTED-MEEK-SESSION-TICKET-OSSH", "QUIC-OSSH", "SHADOWSOCKS-OSSH",
            "FRONTED-MEEK-OSSH", "FRONTED-MEEK-CDN-OSSH", "FRONTED-MEEK-HTTP-OSSH",
            "FRONTED-MEEK-CDN-HTTP-OSSH", "FRONTED-MEEK-QUIC-OSSH", "FRONTED-MEEK-CDN-QUIC-OSSH"
        ))
        when (mode) {
            Mode.CDN -> {
                root.put("LimitTunnelProtocols", cdnProtocols)
                root.put("DisableTactics", true)
            }
            Mode.DIRECT -> {
                root.put("LimitTunnelProtocols", directProtocols)
                root.put("DisableTactics", true)
            }
            Mode.AUTO -> root.put("LimitTunnelProtocols", directProtocols)
        }

        if (ips.isEmpty()) {
            root.put("FrontedMeekCDNScanUseBuiltInSpec", true)
        } else {
            val spec = JSONObject().put("IPCandidates", JSONArray(ips))
            if (snis.isNotEmpty()) spec.put("SNIServerNames", JSONArray(snis))
            root.put("FrontedMeekCDNScanSpec", spec)
        }
        if (ips.isNotEmpty() && snis.isNotEmpty()) {
            root.put("FrontedMeekDialOverrides", JSONArray().put(
                JSONObject()
                    .put("OverrideID", "rooh-user")
                    .put("MatchDialAddressRegexes", JSONArray().put(".*"))
                    .put("DialAddresses", JSONArray(ips))
                    .put("SNIServerName", snis.first())
                    .put("VerifyServerNames", JSONArray(snis))
            ))
        }
        return root.toString()
    }

    private fun ipv4(raw: String): String? {
        val parts = raw.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 } || parts.any { it.isEmpty() || it.length > 3 }) return null
        return octets.joinToString(".")
    }

    private fun hostname(raw: String): String? = runCatching {
        val input = raw.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').trimEnd('.')
        if (input.isBlank() || input.any(Char::isWhitespace)) return null
        val ascii = IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).lowercase()
        if (!ascii.contains('.') || ascii.length > 253) return null
        ascii
    }.getOrNull()

    companion object {
        private val REGIONS = setOf(
            "AT", "AU", "BE", "BG", "CA", "CH", "CZ", "DE", "DK", "EE", "ES", "FI", "FR",
            "GB", "HR", "HU", "IE", "IN", "IT", "JP", "KR", "LV", "MX", "NL", "NO", "PL",
            "PT", "RO", "RS", "SE", "SG", "SK", "UA", "US"
        )
        private const val PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF"
        private const val SPONSOR_ID = "FFFFFFFFFFFFFFFF"
        private const val SERVER_LIST_URL =
            "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed"
        private const val SERVER_LIST_SIGNATURE_KEY =
            "MIICIDANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH" +
            "5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5" +
            "OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42Kcot" +
            "LFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6" +
            "/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7G" +
            "stZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1O" +
            "geF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8" +
            "u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz" +
            "31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xal" +
            "KxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
    }
}
