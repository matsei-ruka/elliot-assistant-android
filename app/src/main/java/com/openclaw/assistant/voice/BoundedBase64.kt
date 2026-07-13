package com.openclaw.assistant.voice

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64

/** Bounded Base64/file operations used by the inline voice request and reply. */
object BoundedBase64 {
    fun encodeFile(file: File, maxBytes: Int = CtbVoiceLimits.MAX_AUDIO_BYTES): String {
        val length = file.length()
        if (!file.isFile || length <= 0L) throw InvalidVoiceAudioException("Voice audio is empty")
        if (length > maxBytes) throw InvalidVoiceAudioException("Voice audio exceeds the size limit")
        val bytes = file.readBytes()
        if (bytes.size > maxBytes) throw InvalidVoiceAudioException("Voice audio exceeds the size limit")
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Streams decoded bytes through a counted temporary file, then atomically
     * exposes [target]. Malformed or oversized data never leaves a partial
     * playback file behind.
     */
    fun decodeToFile(
        encoded: String,
        target: File,
        maxBytes: Int = CtbVoiceLimits.MAX_AUDIO_BYTES,
    ) {
        val maxEncodedChars = ((maxBytes + 2) / 3) * 4
        if (encoded.isEmpty()) throw InvalidVoiceAudioException("Voice reply audio is empty")
        if (encoded.length > maxEncodedChars) {
            throw InvalidVoiceAudioException("Voice reply audio exceeds the size limit")
        }

        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        target.delete()
        partial.delete()
        try {
            val decoded = try {
                Base64.getDecoder().wrap(encoded.byteInputStream(Charsets.US_ASCII))
            } catch (e: IllegalArgumentException) {
                throw InvalidVoiceAudioException("Voice reply audio is not valid Base64", e)
            }
            decoded.use { input ->
                BufferedOutputStream(partial.outputStream()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = try {
                            input.read(buffer)
                        } catch (e: IllegalArgumentException) {
                            throw InvalidVoiceAudioException("Voice reply audio is not valid Base64", e)
                        }
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw InvalidVoiceAudioException("Voice reply audio exceeds the size limit")
                        }
                        output.write(buffer, 0, read)
                    }
                    if (total == 0L) throw InvalidVoiceAudioException("Voice reply audio is empty")
                }
            }
            if (!partial.renameTo(target)) {
                throw IOException("Voice reply cache file could not be finalized")
            }
        } catch (e: InvalidVoiceAudioException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw InvalidVoiceAudioException("Voice reply audio is not valid Base64", e)
        } catch (e: IOException) {
            throw InvalidVoiceAudioException("Voice reply audio could not be decoded", e)
        } finally {
            partial.delete()
        }
    }
}
