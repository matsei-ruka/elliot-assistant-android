package com.openclaw.assistant.voice

import java.io.File
import java.io.FileInputStream
import java.io.IOException

class InvalidVoiceAudioException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Minimal container/codec validation performed before upload and playback. */
object OggOpusValidator {
    private val OGG_MAGIC = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)

    fun validate(file: File, maxBytes: Int = CtbVoiceLimits.MAX_AUDIO_BYTES) {
        val length = file.length()
        if (!file.isFile || length <= 0L) throw InvalidVoiceAudioException("Voice audio is empty")
        if (length > maxBytes) throw InvalidVoiceAudioException("Voice audio exceeds the size limit")

        val scanLength = minOf(length.toInt(), CtbVoiceLimits.OGG_VALIDATION_SCAN_BYTES)
        val header = ByteArray(scanLength)
        val bytesRead = try {
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read < 0) break
                    offset += read
                }
                offset
            }
        } catch (e: IOException) {
            throw InvalidVoiceAudioException("Voice audio could not be read", e)
        }

        if (bytesRead < OGG_MAGIC.size || !header.startsWith(OGG_MAGIC)) {
            throw InvalidVoiceAudioException("Voice audio is not an OGG container")
        }
        if (header.indexOf(OPUS_HEAD, bytesRead) < 0) {
            throw InvalidVoiceAudioException("OGG audio does not contain OpusHead")
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.indexOf(needle: ByteArray, validLength: Int): Int {
        if (needle.isEmpty() || validLength < needle.size) return -1
        for (start in 0..(validLength - needle.size)) {
            var matches = true
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }
}
