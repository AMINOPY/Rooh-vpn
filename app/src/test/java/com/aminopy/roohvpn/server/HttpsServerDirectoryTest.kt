package com.aminopy.roohvpn.server

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpsServerDirectoryTest {
    @Test
    fun acceptsHttpsBaseUrlWithOptionalPath() {
        val directory = HttpsServerDirectory("https://vpn.example/api/")

        val endpoint = directory.javaClass.getDeclaredField("endpoint").let { field ->
            field.isAccessible = true
            field.get(directory) as java.net.URL
        }

        assertEquals("https://vpn.example/api/v1/servers", endpoint.toExternalForm())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPlainHttp() {
        HttpsServerDirectory("http://vpn.example")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialsInBaseUrl() {
        HttpsServerDirectory("https://user:secret@vpn.example")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsQueryStringInBaseUrl() {
        HttpsServerDirectory("https://vpn.example?redirect=https://evil.example")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDotSegmentsInBasePath() {
        HttpsServerDirectory("https://vpn.example/api/../other")
    }
}
