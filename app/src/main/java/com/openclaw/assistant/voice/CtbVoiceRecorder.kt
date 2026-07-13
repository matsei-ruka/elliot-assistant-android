package com.openclaw.assistant.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Platform OGG/Opus recorder used only by CTB Assistant voice turns. */
class CtbVoiceRecorder(
    private val context: Context,
    private val files: CtbVoiceFileStore,
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    @Synchronized
    fun start(onHardLimit: () -> Unit): File {
        check(recorder == null) { "Voice recorder is already active" }
        val output = files.newInputFile()
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(48_000)
            created.setAudioEncodingBitRate(24_000)
            created.setMaxDuration(CtbVoiceLimits.MAX_CAPTURE_DURATION_MS)
            created.setMaxFileSize(CtbVoiceLimits.MAX_AUDIO_BYTES.toLong())
            created.setOutputFile(output.absolutePath)
            created.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    onHardLimit()
                }
            }
            created.prepare()
            created.start()
            recorder = created
            currentFile = output
            return output
        } catch (e: Exception) {
            runCatching { created.reset() }
            runCatching { created.release() }
            files.delete(output)
            throw InvalidVoiceAudioException("Voice recording could not start", e)
        }
    }

    @Synchronized
    fun maxAmplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: RuntimeException) {
        0
    }

    @Synchronized
    fun stopAndValidate(): File {
        val active = recorder ?: throw InvalidVoiceAudioException("Voice recorder is not active")
        val output = currentFile ?: throw InvalidVoiceAudioException("Voice recording file is missing")
        recorder = null
        currentFile = null
        try {
            active.stop()
        } catch (e: RuntimeException) {
            files.delete(output)
            throw InvalidVoiceAudioException("Voice recording was too short or could not be finalized", e)
        } finally {
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        try {
            OggOpusValidator.validate(output)
            return output
        } catch (e: Exception) {
            files.delete(output)
            throw e
        }
    }

    @Synchronized
    fun cancel() {
        val active = recorder
        val output = currentFile
        recorder = null
        currentFile = null
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        files.delete(output)
    }

    @Synchronized
    fun isRecording(): Boolean = recorder != null
}
