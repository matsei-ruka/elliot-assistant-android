package com.openclaw.assistant.voice

/** Hard client-side limits for the CTB inline voice path (Spec 002). */
object CtbVoiceLimits {
    const val MAX_CAPTURE_DURATION_MS = 60_000
    const val MAX_AUDIO_BYTES = 8 * 1024 * 1024
    const val MAX_JSON_BYTES = 12 * 1024 * 1024
    const val OGG_VALIDATION_SCAN_BYTES = 64 * 1024

    const val SILENCE_AFTER_SPEECH_MS = 1_200L
    const val SPEECH_AMPLITUDE_THRESHOLD = 1_500

    /** Maximum padded Base64 length for [MAX_AUDIO_BYTES]. */
    const val MAX_BASE64_CHARS = ((MAX_AUDIO_BYTES + 2) / 3) * 4
}
