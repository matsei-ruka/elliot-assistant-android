package com.openclaw.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

sealed class CtbPlaybackResult {
    object Completed : CtbPlaybackResult()
    object Interrupted : CtbPlaybackResult()
    data class Failed(val reason: String) : CtbPlaybackResult()
}

/** Lifecycle-owned remote voice player with audio focus and a MediaSession. */
class CtbVoicePlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var continuation: CancellableContinuation<CtbPlaybackResult>? = null

    suspend fun play(file: File, onPlaybackStarted: () -> Unit): CtbPlaybackResult =
        suspendCancellableCoroutine { next ->
            check(Looper.myLooper() == Looper.getMainLooper()) { "Voice playback must start on the main thread" }
            stopInternal(CtbPlaybackResult.Interrupted)
            continuation = next
            next.invokeOnCancellation {
                mainHandler.post {
                    if (continuation === next) stopInternal(null)
                }
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stop(CtbPlaybackResult.Interrupted)
                    }
                }
                .build()
            focusRequest = request
            if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                stopInternal(CtbPlaybackResult.Failed("Audio focus was denied"))
                return@suspendCancellableCoroutine
            }

            val session = MediaSession(appContext, "CtbVoiceReply").apply {
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        player?.start()
                        updatePlaybackState(PlaybackState.STATE_PLAYING)
                    }

                    override fun onPause() {
                        player?.pause()
                        updatePlaybackState(PlaybackState.STATE_PAUSED)
                    }

                    override fun onStop() {
                        stop(CtbPlaybackResult.Interrupted)
                    }
                })
                isActive = true
            }
            mediaSession = session
            updatePlaybackState(PlaybackState.STATE_BUFFERING)

            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    if (continuation !== next || !next.isActive) return@setOnPreparedListener
                    it.start()
                    updatePlaybackState(PlaybackState.STATE_PLAYING)
                    onPlaybackStarted()
                }
                setOnCompletionListener {
                    updatePlaybackState(PlaybackState.STATE_STOPPED)
                    stopInternal(CtbPlaybackResult.Completed)
                }
                setOnErrorListener { _, _, _ ->
                    stopInternal(CtbPlaybackResult.Failed("Voice reply playback failed"))
                    true
                }
            }
            player = mediaPlayer
            try {
                mediaPlayer.prepareAsync()
            } catch (_: Exception) {
                stopInternal(CtbPlaybackResult.Failed("Voice reply could not be prepared"))
            }
        }

    fun stop(result: CtbPlaybackResult = CtbPlaybackResult.Interrupted) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopInternal(result)
        } else {
            mainHandler.post { stopInternal(result) }
        }
    }

    private fun stopInternal(result: CtbPlaybackResult?) {
        val activeContinuation = continuation
        continuation = null
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            it.reset()
            it.release()
        }
        player = null
        mediaSession?.let {
            runCatching { it.isActive = false }
            it.release()
        }
        mediaSession = null
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        if (result != null && activeContinuation?.isActive == true) {
            runCatching { activeContinuation.resume(result) }
        }
    }

    private fun updatePlaybackState(state: Int) {
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }
}
