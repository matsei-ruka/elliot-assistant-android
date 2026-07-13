package com.openclaw.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSilenceDetectorTest {
    @Test
    fun `initial silence never pretends speech ended`() {
        val detector = LocalSilenceDetector(speechThreshold = 100, silenceAfterSpeechMs = 500)
        assertFalse(detector.observe(0, 0))
        assertFalse(detector.observe(0, 5_000))
    }

    @Test
    fun `requires speech then sustained silence`() {
        val detector = LocalSilenceDetector(speechThreshold = 100, silenceAfterSpeechMs = 500)
        assertFalse(detector.observe(200, 100))
        assertFalse(detector.observe(200, 200))
        assertFalse(detector.observe(0, 600))
        assertTrue(detector.observe(0, 700))
    }
}
