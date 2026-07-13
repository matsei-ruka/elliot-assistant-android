package com.openclaw.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CtbLogTest {

    @Test
    fun `log line carries only id status elapsed and body length`() {
        val line = CtbLog.requestLine(
            requestId = "ab12cd34",
            status = 200,
            elapsedMs = 1234,
            bodyLength = 456,
        )
        assertEquals("request id=ab12cd34 status=200 elapsedMs=1234 bodyLength=456", line)
    }

    @Test
    fun `missing status and body render as none`() {
        val line = CtbLog.requestLine(
            requestId = "ab12cd34",
            status = null,
            elapsedMs = 99,
            bodyLength = null,
        )
        assertEquals("request id=ab12cd34 status=none elapsedMs=99 bodyLength=none", line)
    }

    @Test
    fun `request ids are short and non-repeating`() {
        val a = CtbLog.newRequestId()
        val b = CtbLog.newRequestId()
        assertEquals(8, a.length)
        assertFalse(a == b)
    }

    @Test
    fun `format has no field that could carry url token or content`() {
        // The formatter only accepts id/status/elapsed/length; this guards the
        // shape so a future field addition is a conscious, reviewed choice.
        val line = CtbLog.requestLine("id", 500, 1, 0)
        assertTrue(line.split(" ").size == 5)
        assertFalse(line.contains("http"))
        assertFalse(line.contains("Bearer"))
    }
}
