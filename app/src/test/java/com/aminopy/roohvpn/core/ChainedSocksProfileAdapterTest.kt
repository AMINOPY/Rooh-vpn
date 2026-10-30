package com.aminopy.roohvpn.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChainedSocksProfileAdapterTest {
    @Test fun xrayAddsLoopbackSocksAndChainsOnlyProxyOutbound() {
        val source = """{"outbounds":[{"protocol":"vless","tag":"proxy","settings":{}},{"protocol":"freedom","tag":"direct"}]}"""
        val result = ChainedSocksProfileAdapter.applyXray(source.toByteArray(), port = 19080)
        assertTrue(result.errorCode ?: "expected success", result.isSuccess)
        val root = JSONObject(result.configuration!!.toString(Charsets.UTF_8))
        val outbounds = root.getJSONArray("outbounds")
        assertEquals("roohvpn-chained-upstream", outbounds.getJSONObject(0).getJSONObject("proxySettings").getString("tag"))
        assertEquals("127.0.0.1", outbounds.getJSONObject(2).getJSONObject("settings")
            .getJSONArray("servers").getJSONObject(0).getString("address"))
        assertEquals(19080, outbounds.getJSONObject(2).getJSONObject("settings")
            .getJSONArray("servers").getJSONObject(0).getInt("port"))
    }

    @Test fun singBoxAddsLoopbackSocksDetour() {
        val source = """{"outbounds":[{"type":"vless","tag":"proxy"},{"type":"direct","tag":"direct"}]}"""
        val result = ChainedSocksProfileAdapter.applySingBox(source.toByteArray(), port = 19081)
        assertTrue(result.errorCode ?: "expected success", result.isSuccess)
        val outbounds = JSONObject(result.configuration!!.toString(Charsets.UTF_8)).getJSONArray("outbounds")
        assertEquals("roohvpn-chained-upstream", outbounds.getJSONObject(1).getString("detour"))
        assertEquals("socks", outbounds.getJSONObject(0).getString("type"))
        assertEquals(19081, outbounds.getJSONObject(0).getInt("server_port"))
    }

    @Test fun rejectsNonLoopbackSocksHost() {
        val source = """{"outbounds":[{"protocol":"vless","settings":{}}]}"""
        assertEquals("INVALID_LOCAL_SOCKS",
            ChainedSocksProfileAdapter.applyXray(source.toByteArray(), "0.0.0.0", 1080).errorCode)
    }

    @Test fun rejectsAmbiguousProfilesInsteadOfGuessingOutbound() {
        val source = """{"outbounds":[{"protocol":"vless"},{"protocol":"vmess"}]}"""
        assertEquals("AMBIGUOUS_PROXY_OUTBOUND",
            ChainedSocksProfileAdapter.applyXray(source.toByteArray(), port = 1080).errorCode)
    }

    @Test fun refusesToOverwriteAnExistingXrayChain() {
        val source = """{"outbounds":[{"protocol":"vless","proxySettings":{"tag":"old"}}]}"""
        assertEquals("EXISTING_PROXY_CHAIN_UNSUPPORTED",
            ChainedSocksProfileAdapter.applyXray(source.toByteArray(), port = 1080).errorCode)
    }

    @Test fun refusesToOverwriteAnExistingSingBoxDetour() {
        val source = """{"outbounds":[{"type":"vless","detour":"old"}]}"""
        assertEquals("EXISTING_PROXY_CHAIN_UNSUPPORTED",
            ChainedSocksProfileAdapter.applySingBox(source.toByteArray(), port = 1080).errorCode)
    }
}
