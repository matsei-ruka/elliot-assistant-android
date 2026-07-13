package com.openclaw.assistant.voice

/** Amplitude-only end-of-speech detector; it never records text or features. */
class LocalSilenceDetector(
    private val speechThreshold: Int = CtbVoiceLimits.SPEECH_AMPLITUDE_THRESHOLD,
    private val silenceAfterSpeechMs: Long = CtbVoiceLimits.SILENCE_AFTER_SPEECH_MS,
) {
    private var aboveThresholdSamples = 0
    private var lastSpeechAtMs: Long? = null

    fun observe(maxAmplitude: Int, elapsedMs: Long): Boolean {
        if (maxAmplitude >= speechThreshold) {
            aboveThresholdSamples += 1
            if (aboveThresholdSamples >= MIN_SPEECH_SAMPLES) lastSpeechAtMs = elapsedMs
            return false
        }
        val lastSpeech = lastSpeechAtMs ?: return false
        return elapsedMs - lastSpeech >= silenceAfterSpeechMs
    }

    companion object {
        private const val MIN_SPEECH_SAMPLES = 2
    }
}
