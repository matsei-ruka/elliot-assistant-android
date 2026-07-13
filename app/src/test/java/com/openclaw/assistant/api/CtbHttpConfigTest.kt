package com.openclaw.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CtbHttpConfigTest {

    @Test
    fun `accepts the fixed CTB endpoint`() {
        val url = CtbHttpConfig.validateEndpoint("https://bridge.italia.ae/v1/chat/completions")
        assertNotNull(url)
        assertEquals("bridge.italia.ae", url!!.host)
        assertEquals("/v1/chat/completions", url.encodedPath)
    }

    @Test
    fun `accepts surrounding whitespace without rewriting`() {
        val url = CtbHttpConfig.validateEndpoint("  https://bridge.italia.ae/v1/chat/completions  ")
        assertNotNull(url)
    }

    @Test
    fun `accepts a path prefix as long as it ends in the completions path`() {
        assertNotNull(CtbHttpConfig.validateEndpoint("https://host.example/proxy/v1/chat/completions"))
    }

    @Test
    fun `rejects plain http for non-loopback hosts`() {
        assertNull(CtbHttpConfig.validateEndpoint("http://bridge.italia.ae/v1/chat/completions"))
        assertNull(CtbHttpConfig.validateEndpoint("http://192.168.1.10/v1/chat/completions"))
    }

    @Test
    fun `allows plain http only for loopback test servers`() {
        assertNotNull(CtbHttpConfig.validateEndpoint("http://localhost:8080/v1/chat/completions"))
        assertNotNull(CtbHttpConfig.validateEndpoint("http://127.0.0.1:8080/v1/chat/completions"))
    }

    @Test
    fun `rejects a base url instead of guessing the completions path`() {
        assertNull(CtbHttpConfig.validateEndpoint("https://bridge.italia.ae"))
        assertNull(CtbHttpConfig.validateEndpoint("https://bridge.italia.ae/"))
        assertNull(CtbHttpConfig.validateEndpoint("https://bridge.italia.ae/v1/"))
    }

    @Test
    fun `rejects a trailing slash after the completions path`() {
        assertNull(CtbHttpConfig.validateEndpoint("https://bridge.italia.ae/v1/chat/completions/"))
    }

    @Test
    fun `rejects other api paths`() {
        assertNull(CtbHttpConfig.validateEndpoint("https://bridge.italia.ae/v1/completions"))
        assertNull(CtbHttpConfig.validateEndpoint("https://bridge.italia.ae/v1/chat/completions/extra"))
    }

    @Test
    fun `rejects garbage and blank input`() {
        assertNull(CtbHttpConfig.validateEndpoint(""))
        assertNull(CtbHttpConfig.validateEndpoint("not a url"))
        assertNull(CtbHttpConfig.validateEndpoint("ftp://bridge.italia.ae/v1/chat/completions"))
    }

    @Test
    fun `origin keeps only scheme host and port`() {
        val endpoint = CtbHttpConfig.validateEndpoint(
            "https://bridge.italia.ae/v1/chat/completions?key=value"
        )!!
        val origin = CtbHttpConfig.origin(endpoint)
        assertEquals("https://bridge.italia.ae/", origin.toString())
    }

    @Test
    fun `origin preserves an explicit port`() {
        val endpoint = CtbHttpConfig.validateEndpoint(
            "https://bridge.italia.ae:8443/v1/chat/completions"
        )!!
        assertEquals("https://bridge.italia.ae:8443/", CtbHttpConfig.origin(endpoint).toString())
    }

    @Test
    fun `health url is healthz on the endpoint origin`() {
        val endpoint = CtbHttpConfig.validateEndpoint(
            "https://bridge.italia.ae/v1/chat/completions"
        )!!
        assertEquals(
            "https://bridge.italia.ae/healthz",
            CtbHttpConfig.healthUrl(endpoint).toString()
        )
    }

    @Test
    fun `client budget covers the 300 second CTB wait`() {
        assertEquals(320L, CtbHttpConfig.READ_TIMEOUT_SECONDS)
        assertEquals(320L, CtbHttpConfig.CALL_TIMEOUT_SECONDS)
        assertEquals(30L, CtbHttpConfig.CONNECT_TIMEOUT_SECONDS)
        assertEquals(30L, CtbHttpConfig.WRITE_TIMEOUT_SECONDS)
    }
}
