package com.openclaw.assistant.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WriteOnlyCredentialTest {
    @Test
    fun `empty entry preserves stored token`() {
        assertEquals("stored", WriteOnlyCredential.resolve("", "stored"))
        assertEquals("stored", WriteOnlyCredential.resolve("   ", "stored"))
    }

    @Test
    fun `nonempty entry replaces token without copying another store`() {
        assertEquals("new", WriteOnlyCredential.resolve("  new  ", "stored"))
    }

    @Test
    fun `explicit clear removes stored token`() {
        assertNull(WriteOnlyCredential.resolve("ignored", "stored", clear = true))
    }
}
