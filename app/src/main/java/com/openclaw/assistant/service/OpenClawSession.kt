package com.openclaw.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.service.voice.VoiceInteractionSession
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.openclaw.assistant.R
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.VoiceSessionRouter
import com.openclaw.assistant.api.OpenClawVoiceResponse
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.speech.TTSState
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import com.openclaw.assistant.voice.CtbPlaybackResult
import com.openclaw.assistant.voice.CtbVoiceFileStore
import com.openclaw.assistant.voice.CtbVoiceLimits
import com.openclaw.assistant.voice.CtbVoicePlayer
import com.openclaw.assistant.voice.CtbVoiceRecorder
import com.openclaw.assistant.voice.CtbVoiceTurnStateMachine
import com.openclaw.assistant.voice.LocalSilenceDetector
import java.io.File
import kotlin.math.log10

/**
 * Voice Interaction Session
 * Handles actual voice interaction
 */
class OpenClawSession(
    context: Context,
    initialSessionArgs: Bundle? = null
) : VoiceInteractionSession(context),
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {

    companion object {
        private const val TAG = "OpenClawSession"
        private const val INITIAL_FILLER_DELAY_MS = 750L
        private const val INTERRUPT_LISTEN_DELAY_MS = 350L
    }

    private val settings = SettingsRepository.getInstance(context)
    private var sessionArgs: Bundle? = initialSessionArgs
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var ttsManager: TTSManager
    private lateinit var ctbVoiceFiles: CtbVoiceFileStore
    private lateinit var ctbVoiceRecorder: CtbVoiceRecorder
    private lateinit var ctbVoicePlayer: CtbVoicePlayer
    private val ctbVoiceState = CtbVoiceTurnStateMachine()
    private var ctbRecordingStop: CompletableDeferred<Unit>? = null
    private var ctbInputFile: File? = null
    private var ctbOutputFile: File? = null
    
    // Repository
    private val chatRepository = com.openclaw.assistant.data.repository.ChatRepository.getInstance(context)
    private var currentSessionId: String? = null
    
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var initialFillerPhraseJob: Job? = null
    private var auxiliarySpeechJob: Job? = null
    @Volatile private var ignoreNextTtsStop = false
    private val interruptReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.openclaw.assistant.ACTION_INTERRUPT_TTS") return
            // THINKING included so barge-in can also cancel the request
            // during the full 320-second CTB wait.
            if (currentState.value != AssistantState.LISTENING &&
                currentState.value != AssistantState.PROCESSING &&
                currentState.value != AssistantState.SPEAKING &&
                currentState.value != AssistantState.PREPARING_SPEECH &&
                currentState.value != AssistantState.THINKING) return
            Log.d(TAG, "Barge-in interrupt received in OpenClawSession")
            interruptAndListen()
        }
    }

    // UI State
    private var currentState = mutableStateOf(AssistantState.IDLE)
    private var displayText = mutableStateOf("")
    private var userQuery = mutableStateOf("") // User's spoken text
    private var partialText = mutableStateOf("")
    private var errorMessage = mutableStateOf<String?>(null)
    private var audioLevel = mutableStateOf(0f) // Audio level for visualization

    private fun effectiveVoiceTarget(): String {
        return sessionArgs?.getString(OpenClawAssistantService.EXTRA_VOICE_TARGET)
            ?.takeIf { it == SettingsRepository.VOICE_TARGET_OPENCLAW || it == SettingsRepository.VOICE_TARGET_HERMES }
            ?: if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                SettingsRepository.VOICE_TARGET_OPENCLAW
            } else {
                SettingsRepository.VOICE_TARGET_HERMES
            }
    }

    /**
     * Backend route resolved exactly once in [onShow]; the same route is used
     * for every request of this session. Only a GATEWAY route touches gateway
     * health or gateway session management.
     */
    private var resolvedRoute: VoiceSessionRouter.Route.Backend? = null

    private fun isGatewayRoute(): Boolean =
        resolvedRoute?.type == BackendType.OPENCLAW_GATEWAY

    // WakeLock to keep CPU alive during voice conversation when screen is off
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        Log.e(TAG, "Session onCreate start")
        super.onCreate()
        
        // Initialize lifecycle and saved state here (once per session lifetime)
        try {
            savedStateRegistryController.performAttach()
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already attached?", e)
        }
        
        try {
            savedStateRegistryController.performRestore(null)
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already restored?", e)
        }

        try {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        } catch (e: Exception) {
             Log.w(TAG, "Lifecycle ON_CREATE failed", e)
        }

        ttsManager = TTSManager(context)
        ctbVoiceFiles = CtbVoiceFileStore(context)
        ctbVoiceFiles.deleteAll()
        ctbVoiceRecorder = CtbVoiceRecorder(context, ctbVoiceFiles)
        ctbVoicePlayer = CtbVoicePlayer(context)
        val initialized = ttsManager.initializeCurrentProvider()
        Log.i(TAG, "Session TTS initialized=$initialized ready=${ttsManager.isReady()}")
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            interruptReceiver,
            android.content.IntentFilter("com.openclaw.assistant.ACTION_INTERRUPT_TTS"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
    
    // Audio Cue
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
    private val toneGeneratorReleased = AtomicBoolean(false)

    private fun playTone(tone: Int, durationMs: Int = -1) {
        if (toneGeneratorReleased.get()) return
        try {
            if (durationMs == -1) toneGenerator.startTone(tone)
            else toneGenerator.startTone(tone, durationMs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator already released", e)
        }
    }

    // AudioFocus management
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // Session foreground service keeps process alive during conversation (prevents MIUI/HyperOS freeze)

    override val lifecycle: androidx.lifecycle.Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreateContentView(): View {
        Log.e(TAG, "Session onCreateContentView")
        val composeView = ComposeView(context).apply {
            Log.e(TAG, "Initializing ComposeView with owners")
            // Set ViewTree owners using extensions
            try {
                setViewTreeLifecycleOwner(this@OpenClawSession)
                setViewTreeViewModelStoreOwner(this@OpenClawSession)
                setViewTreeSavedStateRegistryOwner(this@OpenClawSession)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ViewTree owners", e)
            }
            
            setContent {
                AssistantUI(
                    state = currentState.value,
                    displayText = displayText.value,
                    userQuery = userQuery.value,
                    partialText = partialText.value,
                    errorMessage = errorMessage.value,
                    audioLevel = audioLevel.value,
                    isRawVoice = resolvedRoute?.type == BackendType.OPENCLAW_HTTP,
                    onClose = {
                        isUserDismissed = true
                        finish()
                    },
                    onRetry = { startListening() },
                    onInterrupt = { interruptAndListen() },
                    onStopRecording = { stopCtbRecordingAndSend() },
                )
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionArgs = args

        // Recreate scope if it was cancelled by a previous onHide()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        // A CTB voice turn must never create or invoke SpeechRecognizer. Text
        // routes initialize it only after routing has selected Gateway/Hermes.
        if (this::speechManager.isInitialized) {
            try {
                speechManager.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to destroy existing SpeechRecognizerManager before recreation", e)
            }
        }

        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

        // Start foreground service to keep process alive during conversation (screen off)
        SessionForegroundService.start(context)

        Log.d(TAG, "Session shown with flags: $showFlags")

        // PAUSE Hotword Service to prevent microphone conflict
        sendPauseBroadcast()

        // Resolve the backend route exactly once for this session. The
        // gateway is consulted only when the route is actually a gateway;
        // an HTTP (CTB) or Hermes route never requires gateway health,
        // gateway session management, or gateway configuration.
        val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
        val route = VoiceSessionRouter.resolve(
            voiceTarget = effectiveVoiceTarget(),
            backends = com.openclaw.assistant.backend.BackendRepository.getInstance(context).backends.value,
            gatewayHealthy = nodeRuntime.chatHealthOk.value,
        )
        when (route) {
            is VoiceSessionRouter.Route.Backend -> resolvedRoute = route
            VoiceSessionRouter.Route.GatewayUnavailable -> {
                resolvedRoute = null
                showTerminalConfigError(context.getString(R.string.error_gateway_not_connected))
                return
            }
            VoiceSessionRouter.Route.NotConfigured -> {
                resolvedRoute = null
                showTerminalConfigError(context.getString(R.string.error_config_required))
                return
            }
        }

        if (route is VoiceSessionRouter.Route.Backend && route.type != BackendType.OPENCLAW_HTTP) {
            speechManager = SpeechRecognizerManager(context)
        }

        // SESSION MANAGEMENT
        if (isGatewayRoute()) {
            // Gateway route: manage session on the gateway side, not in local DB
            if (!settings.resumeLatestSession) {
                // Start a fresh gateway session with a human-readable label
                val newKey = java.util.UUID.randomUUID().toString()
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val label = String.format(context.getString(R.string.default_session_title_format), timeStr)
                nodeRuntime.switchChatSession(newKey)
                scope.launch { nodeRuntime.patchChatSession(newKey, label) }
            }
            // resumeLatestSession ON → keep the current active gateway session as-is
        } else {
            scope.launch {
                try {
                    val latestSession = if (settings.resumeLatestSession) chatRepository.getLatestSession() else null
                    if (latestSession != null) {
                        currentSessionId = latestSession.id
                        Log.d(TAG, "Resuming latest session")
                    } else {
                        currentSessionId = chatRepository.createSession(title = String.format(context.getString(R.string.default_session_title_format), java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())))
                        Log.d(TAG, "Created new session")
                    }

                    // Store this ID in settings so ChatActivity and API calls use it
                    currentSessionId?.let { settings.sessionId = it }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle session", e)
                }
            }
        }

        // Start speech recognition
        startListening()
    }

    /**
     * Terminal configuration error: the overlay stays up showing the error,
     * but no foreground service, wake lock, or audio focus may remain active
     * (Spec 001 §D).
     */
    private fun showTerminalConfigError(message: String) {
        currentState.value = AssistantState.ERROR
        errorMessage.value = message
        displayText.value = context.getString(R.string.config_required)
        releaseSessionResources()
    }
    
    override fun onHide() {
        super.onHide()

        // ユーザーが明示的に Close ボタンを押した場合は、音声状態に関わらず必ずクリーンアップ
        if (isUserDismissed) {
            isUserDismissed = false
            cleanupSession()
            return
        }

        // If a voice session is active (listening, thinking, or speaking),
        // keep resources alive so conversation continues with screen off.
        val state = currentState.value
        val isVoiceActive = state == AssistantState.LISTENING ||
                state == AssistantState.THINKING ||
                state == AssistantState.SPEAKING ||
                state == AssistantState.PREPARING_SPEECH ||
                state == AssistantState.PROCESSING
        
        if (isVoiceActive) {
            Log.d(TAG, "onHide: voice session active ($state), keeping resources alive")
            // Keep scope, speechManager, ttsManager alive
            // SessionForegroundService is already running
            return
        }

        cleanupSession()
    }

    /**
     * Single terminal cleanup used by every non-continuous end of a request —
     * success, error, empty reply, TTS failure, or configuration error
     * (Spec 001 §D). Cancels all auxiliary sounds, releases audio focus and
     * the session wake lock, stops the foreground service and resumes the
     * hotword service. Not used during barge-in, where the session goes
     * straight back to listening and must keep its foreground service.
     */
    private val terminalCleanupRunning = AtomicBoolean(false)

    private fun releaseSessionResources(resumeHotword: Boolean = true) {
        if (!terminalCleanupRunning.compareAndSet(false, true)) return
        try {
            listeningJob?.cancel()
            listeningJob = null
            speakingJob?.cancel()
            speakingJob = null
            cancelSendJob()
            ctbRecordingStop?.cancel()
            ctbRecordingStop = null
            ctbVoiceState.cancel()
            runCatching { if (this::ctbVoiceRecorder.isInitialized) ctbVoiceRecorder.cancel() }
            runCatching { if (this::ctbVoicePlayer.isInitialized) ctbVoicePlayer.stop() }
            cancelInitialFillerPhrase()
            cancelWaitPhraseTimer()
            stopThinkingSound()
            runCatching { stopAuxiliarySpeech() }
            runCatching { if (this::ttsManager.isInitialized) ttsManager.stop() }
            runCatching { if (this::speechManager.isInitialized) speechManager.destroy() }
            runCatching { abandonAudioFocus() }
            runCatching { releaseWakeLock() }
            runCatching { SessionForegroundService.stop(context) }
            if (this::ctbVoiceFiles.isInitialized) {
                runCatching { ctbVoiceFiles.delete(ctbInputFile) }
                runCatching { ctbVoiceFiles.delete(ctbOutputFile) }
                runCatching { ctbVoiceFiles.deleteAll() }
            }
            ctbInputFile = null
            ctbOutputFile = null
            if (resumeHotword) runCatching { sendResumeBroadcast() }
        } finally {
            terminalCleanupRunning.set(false)
        }
    }

    /**
     * Cancels the in-flight assistant request. Cancellation synchronously
     * aborts the underlying OkHttp call (the client registers
     * invokeOnCancellation → Call.cancel()); the coroutine itself finishes on
     * the main dispatcher, where the ensureActive() guard prevents any late
     * work. The slot is cleared only if it still holds this job.
     */
    private fun cancelSendJob(): Job? {
        val job = sendJob ?: return null
        job.cancel()
        if (sendJob === job) sendJob = null
        return job
    }

    private fun cleanupSession() {
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)

        releaseSessionResources()
        scope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        releaseSessionResources()
        scope.cancel()
        try {
            context.unregisterReceiver(interruptReceiver)
        } catch (_: Exception) {
        }

        if (this::ttsManager.isInitialized) ttsManager.shutdown()
        toneGeneratorReleased.set(true)
        toneGenerator.release()
    }

    private fun sendPauseBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
    
    private fun sendResumeBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    // Must implement ViewModelStoreOwner for Compose
    override val viewModelStore: androidx.lifecycle.ViewModelStore = androidx.lifecycle.ViewModelStore()

    private var listeningJob: Job? = null
    private var speakingJob: Job? = null
    private var sendJob: Job? = null
    private var isUserDismissed = false

    private fun startListening(initialDelayMs: Long = 50L) {
        if (resolvedRoute?.type == BackendType.OPENCLAW_HTTP) {
            startCtbVoiceTurn(initialDelayMs)
        } else {
            startTextListening(initialDelayMs)
        }
    }

    private fun startTextListening(initialDelayMs: Long = 50L) {
        Log.d(TAG, "startListening() called, currentState=${currentState.value}, listeningJob=${listeningJob}, speakingJob=${speakingJob}")
        if (!this::speechManager.isInitialized) speechManager = SpeechRecognizerManager(context)
        listeningJob?.cancel()
        // Re-arm the session resources: a retry after a terminal error must
        // restore the foreground service and wake lock it released.
        SessionForegroundService.start(context)
        acquireWakeLock()
        sendPauseBroadcast()

        currentState.value = AssistantState.PROCESSING
        displayText.value = ""
        userQuery.value = ""
        partialText.value = ""
        errorMessage.value = null
        audioLevel.value = 0f

        // Fallback: if SpeechResult.Ready doesn't arrive within 2s, force LISTENING
        // so users don't see "Processing" indefinitely while the recognizer warms up
        scope.launch {
            delay(2000L)
            if (currentState.value == AssistantState.PROCESSING) {
                Log.w(TAG, "SpeechResult.Ready timeout — forcing LISTENING state")
                currentState.value = AssistantState.LISTENING
            }
        }

        listeningJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false
            
            // Wait for resources to be released before reopening the mic
            delay(initialDelayMs)

            while (isActive && !hasActuallySpoken) {
                // Request audio focus
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest = android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    ).build()
                    audioManager.requestAudioFocus(audioFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null,
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }

                val listenResult = withTimeoutOrNull(30_000L) {
                    speechManager.startListening(settings.speechLanguage.ifEmpty { null }, settings.speechSilenceTimeout).collectLatest { result ->
                        when (result) {
                            is SpeechResult.Ready -> {
                                Log.d(TAG, "SpeechResult.Ready received, transitioning to LISTENING")
                                currentState.value = AssistantState.LISTENING
                                playTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                            }
                            is SpeechResult.Processing -> {
                                // No sound here - thinking ACK sound will play when AI starts processing
                            }
                            is SpeechResult.Listening -> {
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.RmsChanged -> {
                                audioLevel.value = result.rmsdB
                            }
                            is SpeechResult.PartialResult -> {
                                partialText.value = result.text
                                // Ensure state is listening if we get partial results
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.Result -> {
                                Log.d(TAG, "SpeechResult.Result received length=${result.text.length}")
                                hasActuallySpoken = true
                                userQuery.value = result.text
                                sendToOpenClaw(result.text)
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                              result.code == SpeechRecognizer.ERROR_NO_MATCH

                                if (isTimeout && settings.continuousMode && elapsed < 10000) {
                                    Log.d(TAG, "Speech timeout within 10s window ($elapsed ms), retrying...")
                                } else if (
                                    result.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                    result.code == SpeechRecognizer.ERROR_CLIENT
                                ) {
                                    speechManager.destroy()
                                    delay(500)
                                } else if (isTimeout) {
                                    // Timeout - close session without error screen
                                    // But only if AI is not currently thinking or speaking
                                    val state = currentState.value
                                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                                        Log.d(TAG, "Speech timeout but AI is $state, not closing session")
                                        hasActuallySpoken = true // break the loop but don't finish
                                    } else {
                                        playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                        currentState.value = AssistantState.IDLE
                                        releaseSessionResources()
                                        finish() // Close the session
                                    }
                                } else {
                                    playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                    failRequest(result.message)
                                    hasActuallySpoken = true
                                }
                            }
                            else -> {}
                        }
                    }
                }

                if (listenResult == null && !hasActuallySpoken) {
                    Log.w(TAG, "Speech recognition timed out (30s). Device may be locked.")
                    // Manual timeout - only close session if not thinking/speaking
                    val state = currentState.value
                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                        Log.d(TAG, "Manual timeout but AI is $state, not closing session")
                        hasActuallySpoken = true // break the loop but don't finish
                    } else {
                        currentState.value = AssistantState.IDLE
                        releaseSessionResources()
                        finish() // Close the session
                        hasActuallySpoken = true
                    }
                }
                
                if (!hasActuallySpoken) {
                    delay(300)
                }
            }
        }
    }

    /** Direct CTB microphone -> OGG/Opus -> inline audio turn; no STT. */
    private fun startCtbVoiceTurn(initialDelayMs: Long) {
        val route = resolvedRoute?.takeIf { it.type == BackendType.OPENCLAW_HTTP }
        if (route == null) {
            failRequest(context.getString(R.string.error_config_required))
            return
        }

        val previous = cancelSendJob()
        ctbVoiceState.cancel()
        ctbRecordingStop?.cancel()
        ctbRecordingStop = null
        ctbVoiceRecorder.cancel()
        ctbVoicePlayer.stop()
        ctbVoiceFiles.delete(ctbInputFile)
        ctbVoiceFiles.delete(ctbOutputFile)
        ctbInputFile = null
        ctbOutputFile = null

        SessionForegroundService.start(context)
        acquireWakeLock()
        sendPauseBroadcast()
        currentState.value = AssistantState.PROCESSING
        displayText.value = ""
        userQuery.value = ""
        partialText.value = ""
        errorMessage.value = null
        audioLevel.value = 0f

        lateinit var turnJob: Job
        turnJob = scope.launch {
            previous?.join()
            val owner = ctbVoiceState.begin()
            try {
                delay(initialDelayMs)
                requestCaptureAudioFocus()
                playTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100)
                delay(180L)

                val stopSignal = CompletableDeferred<Unit>()
                ctbRecordingStop = stopSignal
                val input = ctbVoiceRecorder.start { stopSignal.complete(Unit) }
                ctbInputFile = input
                val detector = LocalSilenceDetector()
                val startedAt = System.currentTimeMillis()
                currentState.value = AssistantState.LISTENING

                while (isActive && !stopSignal.isCompleted) {
                    delay(100L)
                    val amplitude = ctbVoiceRecorder.maxAmplitude()
                    audioLevel.value = amplitudeToUiLevel(amplitude)
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (detector.observe(amplitude, elapsed) ||
                        elapsed >= CtbVoiceLimits.MAX_CAPTURE_DURATION_MS
                    ) {
                        stopSignal.complete(Unit)
                    }
                }
                stopSignal.await()
                ctbRecordingStop = null
                coroutineContext.ensureActive()
                if (!ctbVoiceState.transition(owner, CtbVoiceTurnStateMachine.State.WAITING_FOR_REPLY)) {
                    return@launch
                }

                currentState.value = AssistantState.PROCESSING
                audioLevel.value = 0f
                abandonAudioFocus()
                val finalizedInput = ctbVoiceRecorder.stopAndValidate()
                ctbInputFile = finalizedInput
                currentState.value = AssistantState.THINKING
                playTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
                startThinkingSound()
                if (settings.fillerPhrasesEnabled) scheduleInitialFillerPhrase()
                startWaitPhraseTimer()

                val output = ctbVoiceFiles.newOutputFile()
                ctbOutputFile = output
                val response = com.openclaw.assistant.backend.PrimaryBackendDispatcher.sendCtbVoice(
                    context = context,
                    inputFile = finalizedInput,
                    outputFile = output,
                    backendId = route.id,
                    sessionId = settings.installUserId,
                )
                ctbInputFile = null // OpenClawClient deletes it immediately after serialization.
                coroutineContext.ensureActive()
                if (!ctbVoiceState.isCurrent(owner)) return@launch
                cancelInitialFillerPhrase()
                cancelWaitPhraseTimer()
                stopAuxiliarySpeech()

                when (response) {
                    is OpenClawVoiceResponse.Audio -> handleRemoteVoiceReply(owner, response, turnJob)
                    is OpenClawVoiceResponse.Text -> handleCtbTextFallback(owner, response.content)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (ctbVoiceState.isCurrent(owner)) failRequest(e.message)
            } finally {
                ctbRecordingStop = null
                ctbVoiceRecorder.cancel()
                ctbVoiceFiles.delete(ctbInputFile)
                ctbInputFile = null
                if (sendJob !== turnJob || !ctbVoiceState.isCurrent(owner)) {
                    ctbVoiceFiles.delete(ctbOutputFile)
                    ctbOutputFile = null
                }
            }
        }
        sendJob = turnJob
        turnJob.invokeOnCompletion {
            if (sendJob === turnJob) sendJob = null
        }
    }

    private suspend fun handleRemoteVoiceReply(
        owner: Long,
        response: OpenClawVoiceResponse.Audio,
        turnJob: Job,
    ) {
        ctbOutputFile = response.file
        if (!ctbVoiceState.transition(owner, CtbVoiceTurnStateMachine.State.PREPARING_PLAYBACK)) {
            ctbVoiceFiles.delete(response.file)
            return
        }
        displayText.value = response.transcript.orEmpty()
        currentState.value = AssistantState.PREPARING_SPEECH
        stopThinkingSound()
        when (val playback = ctbVoicePlayer.play(response.file) {
            if (ctbVoiceState.transition(owner, CtbVoiceTurnStateMachine.State.PLAYING_REMOTE_AUDIO)) {
                currentState.value = AssistantState.SPEAKING
            }
        }) {
            CtbPlaybackResult.Completed -> {
                ctbVoiceState.transition(owner, CtbVoiceTurnStateMachine.State.IDLE)
                ctbVoiceFiles.delete(response.file)
                ctbOutputFile = null
                currentState.value = AssistantState.IDLE
                if (settings.continuousMode) {
                    if (sendJob === turnJob) sendJob = null
                    delay(500L)
                    startListening()
                } else {
                    releaseSessionResources()
                }
            }
            CtbPlaybackResult.Interrupted -> {
                ctbVoiceState.transition(owner, CtbVoiceTurnStateMachine.State.IDLE)
                currentState.value = AssistantState.IDLE
                releaseSessionResources()
            }
            is CtbPlaybackResult.Failed -> failRequest(playback.reason)
        }
    }

    private fun handleCtbTextFallback(owner: Long, text: String) {
        if (!ctbVoiceState.transition(owner, CtbVoiceTurnStateMachine.State.PREPARING_LOCAL_TTS)) return
        ctbVoiceFiles.delete(ctbOutputFile)
        ctbOutputFile = null
        displayText.value = text
        // CTB promised voice; a degraded text completion is spoken exactly once
        // through the established local fallback, irrespective of primary audio.
        speakResponse(
            text = text,
            onStarted = {
                ctbVoiceState.transition(owner, CtbVoiceTurnStateMachine.State.PLAYING_LOCAL_TTS)
            },
            onCompleted = {
                ctbVoiceState.transition(owner, CtbVoiceTurnStateMachine.State.IDLE)
            },
        )
    }

    private fun stopCtbRecordingAndSend() {
        if (resolvedRoute?.type == BackendType.OPENCLAW_HTTP && ctbVoiceRecorder.isRecording()) {
            ctbRecordingStop?.complete(Unit)
        }
    }

    private fun amplitudeToUiLevel(amplitude: Int): Float {
        if (amplitude <= 0) return -2f
        return (20f * log10(amplitude.toFloat() / 32_767f) + 10f).coerceIn(-2f, 10f)
    }

    private fun requestCaptureAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioFocusRequest = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        ).setOnAudioFocusChangeListener { change ->
            if ((change == android.media.AudioManager.AUDIOFOCUS_LOSS ||
                    change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) &&
                ctbVoiceRecorder.isRecording()
            ) {
                currentState.value = AssistantState.IDLE
                releaseSessionResources()
            }
        }.build()
        val granted = audioManager.requestAudioFocus(audioFocusRequest!!)
        if (granted != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = null
            throw IllegalStateException("Microphone audio focus was denied")
        }
    }

    private var thinkingSoundJob: Job? = null

    private fun startThinkingSound() {
        thinkingSoundJob?.cancel()
        if (!settings.thinkingSoundEnabled) return
        thinkingSoundJob = scope.launch {
            delay(2000)
            while (isActive) {
                playTone(android.media.ToneGenerator.TONE_SUP_RINGTONE, 100)
                delay(3000)
            }
        }
    }

    private fun stopThinkingSound() {
        thinkingSoundJob?.cancel()
        thinkingSoundJob = null
    }

    /**
     * Common failure path for the assistant request. Terminal: every session
     * resource (sounds, audio focus, wake lock, foreground service) is
     * released; the overlay stays up showing the error (Spec 001 §D).
     */
    private fun failRequest(message: String?) {
        currentState.value = AssistantState.ERROR
        errorMessage.value = message ?: context.getString(R.string.error_network)
        releaseSessionResources()
    }

    private fun sendToOpenClaw(message: String) {
        Log.d(TAG, "sendToOpenClaw() called, transitioning to THINKING")
        currentState.value = AssistantState.THINKING
        playTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        startThinkingSound()
        displayText.value = ""

        // 相槌フレーズの再生（LLMへのリクエストと並行して実行）
        if (settings.fillerPhrasesEnabled) {
            scheduleInitialFillerPhrase()
        }

        // The route was resolved once in onShow(); it is never re-resolved
        // during a request.
        val route = resolvedRoute
        if (route == null) {
            failRequest(context.getString(R.string.error_config_required))
            return
        }

        // Only one assistant request may be in flight (Spec 001 §D): the new
        // job first cancels the previous one and waits for it to fully
        // finish, so two requests can never overlap.
        val previous = cancelSendJob()
        val job = scope.launch {
            previous?.join()
            val agentId = settings.defaultAgentId.takeIf { it.isNotBlank() && it != "main" }

            // Save the user message to the local DB for non-gateway routes
            if (route.type != BackendType.OPENCLAW_GATEWAY) {
                currentSessionId?.let { sessionId ->
                    chatRepository.addMessage(sessionId, message, isUser = true)
                }
            }

            if (route.type == BackendType.OPENCLAW_GATEWAY) {
                sendViaGateway(message)
                return@launch
            }

            startWaitPhraseTimer()
            val reply = try {
                com.openclaw.assistant.backend.PrimaryBackendDispatcher.send(
                    context = context,
                    userText = message,
                    backendId = route.id,
                    sessionId = settings.installUserId,
                    agentId = agentId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failRequest(e.message)
                return@launch
            }
            cancelWaitPhraseTimer()

            val text = reply?.text.orEmpty()
            if (text.isNotBlank()) {
                displayText.value = text
                handleResponseReceived(text)
            } else {
                failRequest(context.getString(R.string.error_no_response))
            }
        }
        sendJob = job
        job.invokeOnCompletion {
            if (sendJob === job) sendJob = null
        }
    }

    private suspend fun resolveOpenClawGatewayModel(): String? {
        val backends = com.openclaw.assistant.backend.BackendRepository.getInstance(context).backends.first()
            .filter { it.enabled }
        return backends.firstOrNull {
            it.isPrimary && it.type == com.openclaw.assistant.backend.BackendType.OPENCLAW_GATEWAY
        }?.modelName?.takeIf { it.isNotBlank() }
            ?: backends.firstOrNull {
                it.type == com.openclaw.assistant.backend.BackendType.OPENCLAW_GATEWAY
            }?.modelName?.takeIf { it.isNotBlank() }
    }

    private var waitPhraseJob: Job? = null

    private fun scheduleInitialFillerPhrase() {
        cancelInitialFillerPhrase()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        initialFillerPhraseJob = scope.launch {
            delay(INITIAL_FILLER_DELAY_MS)
            if (currentState.value == AssistantState.THINKING && isActive) {
                playFillerPhrase()
            }
        }
    }

    private fun cancelInitialFillerPhrase() {
        initialFillerPhraseJob?.cancel()
        initialFillerPhraseJob = null
    }

    private fun startWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        waitPhraseJob = scope.launch {
            delay(5000) // 5秒待機
            if (currentState.value == AssistantState.THINKING && isActive) {
                Log.d(TAG, "Wait phrase timer triggered (> 5s). Playing wait phrase.")
                playWaitPhrase()
            }
        }
    }

    private fun cancelWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        waitPhraseJob = null
    }

    private fun playFillerPhrase() {
        val phrase = context.getString(R.string.filler_ok)
        
        stopAuxiliarySpeech()
        var playbackJob: Job? = null
        playbackJob = scope.launch {
            try {
                // 相槌は progress 監視せずに即座に発話だけさせる
                ttsManager.speakWithProgress(phrase).collect {} 
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play filler phrase", e)
            } finally {
                if (auxiliarySpeechJob === playbackJob) {
                    auxiliarySpeechJob = null
                }
            }
        }
        auxiliarySpeechJob = playbackJob
    }

    private fun playWaitPhrase() {
        val waitPhrases = listOf(
            context.getString(R.string.wait_phrase_let_me_think),
            context.getString(R.string.wait_phrase_one_moment),
            context.getString(R.string.wait_phrase_checking)
        )
        val phrase = waitPhrases.random()
        
        stopAuxiliarySpeech()
        var playbackJob: Job? = null
        playbackJob = scope.launch {
            try {
                ttsManager.speakWithProgress(phrase).collect {}
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play wait phrase", e)
            } finally {
                if (auxiliarySpeechJob === playbackJob) {
                    auxiliarySpeechJob = null
                }
            }
        }
        auxiliarySpeechJob = playbackJob
    }

    private suspend fun sendViaGateway(message: String) {
        val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
        if (!nodeRuntime.chatHealthOk.value) {
            failRequest(context.getString(R.string.error_gateway_not_connected))
            return
        }

        try {
            val assistantCountBefore = nodeRuntime.chatMessages.value.count { it.role == "assistant" }

            startWaitPhraseTimer() // 待ちフレーズのタイマー開始

            nodeRuntime.sendChat(
                message = message,
                thinking = "low",
                attachments = emptyList(),
                modelName = resolveOpenClawGatewayModel(),
            )

            // Wait for a new complete assistant response (timeout 60s).
            // chatMessages only adds a message when the full response is committed,
            // so watching it avoids both the pendingRunCount==0 early-fire issue
            // and streaming partial-text races.
            val responseText = withTimeoutOrNull(60_000L) {
                nodeRuntime.chatMessages
                    .first { messages -> messages.count { it.role == "assistant" } > assistantCountBefore }
                    .lastOrNull { it.role == "assistant" }
                    ?.content?.firstOrNull { it.type == "text" }?.text
            }

            cancelWaitPhraseTimer()

            if (responseText != null) {
                displayText.value = responseText
                handleResponseReceived(responseText)
            } else {
                failRequest(context.getString(R.string.error_no_response))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only: exception messages can embed host details.
            Log.w(TAG, "Gateway error: ${e.javaClass.simpleName}")
            failRequest(e.message)
        }
    }

    private suspend fun handleResponseReceived(responseText: String) {
        // A cancelled request must never start TTS when the late HTTP
        // response arrives (Spec 001 §D). Everything here runs on the main
        // dispatcher, so this check cannot race with interruptAndListen().
        coroutineContext.ensureActive()

        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopAuxiliarySpeech()

        // Save the AI response to the local DB for non-gateway routes
        if (!isGatewayRoute()) {
            currentSessionId?.let { sessionId ->
                chatRepository.addMessage(sessionId, responseText, isUser = false)
            }
        }

        if (settings.ttsEnabled) {
            // Thinking sound continues until actual audio playback starts
            speakResponse(responseText)
        } else if (settings.continuousMode) {
            stopThinkingSound()
            delay(500)
            startListening()
        } else {
            // TTS disabled & continuous conversation OFF: terminal success
            releaseSessionResources()
            currentState.value = AssistantState.IDLE
        }
    }

    private fun interruptAndListen() {
        // Barge-in: the session goes straight back to listening, so the
        // foreground service and wake lock are deliberately kept alive here.
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        listeningJob?.cancel()
        ctbVoiceState.cancel()
        ctbRecordingStop?.cancel()
        ctbRecordingStop = null
        ctbVoiceRecorder.cancel()
        ctbVoicePlayer.stop()
        ctbVoiceFiles.delete(ctbInputFile)
        ctbVoiceFiles.delete(ctbOutputFile)
        ctbInputFile = null
        ctbOutputFile = null
        // Abort the in-flight HTTP request so a late reply is never spoken
        // and the OkHttp call is really cancelled (Spec 001 §B.2/§D).
        val cancelledSend = cancelSendJob()
        sendPauseBroadcast()
        ignoreNextTtsStop = true
        ttsManager.stop()
        speakingJob?.cancel()
        speakingJob = null
        if (this::speechManager.isInitialized) speechManager.destroy()
        abandonAudioFocus()
        currentState.value = AssistantState.PROCESSING
        partialText.value = ""
        errorMessage.value = null
        scope.launch {
            // The cancelled request must have fully finished before the next
            // listening turn can produce a new one.
            cancelledSend?.join()
            delay(INTERRUPT_LISTEN_DELAY_MS)
            startListening()
        }
    }

    private fun speakResponse(
        text: String,
        onStarted: (() -> Unit)? = null,
        onCompleted: (() -> Unit)? = null,
    ) {
        Log.d(TAG, "speakResponse() called, text length=${text.length}")
        // Thinking sound continues until TTSState.Speaking is received
        currentState.value = AssistantState.PREPARING_SPEECH
        val cleanText = TTSUtils.stripMarkdownForSpeech(text)

        speakingJob = scope.launch {
            ignoreNextTtsStop = false
            try {
                val maxLen = minOf(TTSUtils.getMaxInputLength(null), 1000)
                val chunks = TTSUtils.splitTextForTTS(cleanText, maxLen)
                var success = chunks.isNotEmpty()
                for (chunk in chunks) {
                    var chunkSuccess = false
                    ttsManager.speakWithProgress(chunk).collect { state ->
                        when (state) {
                            is TTSState.Preparing -> {
                                Log.d(TAG, "TTS Preparing")
                                // Keep PREPARING_SPEECH state
                            }
                            is TTSState.Speaking -> {
                                Log.d(TAG, "TTS Speaking")
                                stopThinkingSound()
                                currentState.value = AssistantState.SPEAKING
                                onStarted?.invoke()
                                // Barge-inが有効な場合、読み上げ開始時にHotwordServiceを再開する
                                if (settings.ttsBargeInEnabled) {
                                    sendResumeBroadcast()
                                }
                            }
                            is TTSState.Done -> {
                                Log.d(TAG, "TTS Done")
                                chunkSuccess = true
                            }
                            is TTSState.Error -> {
                                if (ignoreNextTtsStop) {
                                    Log.d(TAG, "Ignoring TTS stop during controlled interruption")
                                    return@collect
                                }
                                Log.e(TAG, "TTS error")
                                chunkSuccess = false
                            }
                        }
                    }
                    if (!chunkSuccess) {
                        success = false
                        break
                    }
                }

                // abandonAudioFocus() : 連続対話やBarge-in対応のため、ここでは即座にfocusを捨てない運用にするか、
                // 次のアクション(マイクの起動等)まで保持しておく選択もある。
                // 既存の挙動を尊重し一旦残す
                abandonAudioFocus()

                // 読み上げ終了時にBarge-inのために再開していたHotwordServiceを再度一時停止させる
                // (この後すぐにlisteningに入る場合はそちらでpauseされるが念のため)
                if (settings.ttsBargeInEnabled && !settings.continuousMode) {
                    sendPauseBroadcast()
                }

                if (ignoreNextTtsStop) {
                    return@launch
                }

                if (success) {
                    onCompleted?.invoke()
                    // After speech completion, if continuous conversation mode is enabled, start listening again
                    if (settings.continuousMode) {
                        Log.d(TAG, "TTS complete, continuous mode ON. Starting 2nd rally startListening() in 500ms")
                        delay(500)
                        startListening()
                    } else {
                        // Continuous conversation OFF: terminal success
                        currentState.value = AssistantState.IDLE
                        releaseSessionResources()
                    }
                } else {
                    failRequest(context.getString(R.string.error_speech_general))
                }
            } catch (e: CancellationException) {
                if (!ignoreNextTtsStop) {
                    throw e
                }
            } catch (e: Exception) {
                if (ignoreNextTtsStop) {
                    return@launch
                }
                Log.e(TAG, "TTS speak error: ${e.javaClass.simpleName}")
                failRequest(context.getString(R.string.error_speech_general))
            }
        }
    }

    private fun stopAuxiliarySpeech() {
        val hadActiveAuxSpeech = auxiliarySpeechJob?.isActive == true
        auxiliarySpeechJob?.cancel()
        auxiliarySpeechJob = null
        if (hadActiveAuxSpeech) {
            ttsManager.stop()
        }
    }



    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClawAssistant::SessionWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 min max to prevent leak
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}

/**
 * Assistant state
 */
enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    THINKING,
    PREPARING_SPEECH,
    SPEAKING,
    ERROR
}

/**
 * Assistant UI (Compose)
 */
@Composable
fun AssistantUI(
    state: AssistantState,
    displayText: String,
    userQuery: String,
    partialText: String,
    errorMessage: String?,
    audioLevel: Float,
    isRawVoice: Boolean = false,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onInterrupt: () -> Unit = {},
    onStopRecording: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Microphone icon
            val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
            val baseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (state == AssistantState.LISTENING) 1.1f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "base_scale"
            )

            // Audio level animation
            // RMS dB usually ranges from roughly -2 (silence) to 10+ (loud speech).
            // We map this to a scale factor.
            // Shift -2 to 0: (level + 2)
            // Divide by expected max roughly 12: ((level + 2) / 12)
            // Clamp to 0..1 range just in case.
            val normalizedLevel = ((audioLevel + 2f) / 10f).coerceIn(0f, 1f)
            
            // Scale increases up to 1.5x for loud sounds
            val targetLevelScale = 1f + (normalizedLevel * 0.5f) 
            
            val animatedLevelScale by animateFloatAsState(
                targetValue = if (state == AssistantState.LISTENING) targetLevelScale else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow), // Slightly faster response
                label = "audio_level_scale"
            )

            // Combine breathing (baseScale) with voice reaction. 
            // When speaking loudly, the voice reaction should dominate.
            val finalScale = if (state == AssistantState.LISTENING) maxOf(baseScale, animatedLevelScale) else 1f

            // Morphing sphere — audio-reactive blob that
            // breathes / ripples / pulses by state. The mic icon floats above
            // the sphere so the existing tap-to-interrupt affordance still
            // works while the assistant is speaking.
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .then(
                        if ((state == AssistantState.LISTENING && isRawVoice) ||
                            state == AssistantState.PROCESSING ||
                            state == AssistantState.SPEAKING ||
                            state == AssistantState.PREPARING_SPEECH ||
                            state == AssistantState.THINKING
                        ) {
                            Modifier.clickable(
                                onClickLabel = stringResource(
                                    if (state == AssistantState.LISTENING) {
                                        R.string.stop_recording_description
                                    } else {
                                        R.string.interrupt_description
                                    }
                                ),
                                role = Role.Button
                            ) {
                                if (state == AssistantState.LISTENING) onStopRecording()
                                else onInterrupt()
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                com.openclaw.assistant.ui.voice.MorphingSphere(
                    state = state,
                    audioLevel = normalizedLevel,
                )
                Icon(
                    imageVector = if (state == AssistantState.ERROR) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status text
            Text(
                text = when (state) {
                    AssistantState.LISTENING -> stringResource(R.string.state_listening)
                    AssistantState.PROCESSING -> stringResource(R.string.state_processing)
                    AssistantState.THINKING -> stringResource(R.string.state_thinking)
                    AssistantState.PREPARING_SPEECH -> stringResource(R.string.preparing_speech)
                    AssistantState.SPEAKING -> stringResource(R.string.state_speaking)
                    AssistantState.ERROR -> stringResource(R.string.state_error)
                    else -> stringResource(R.string.state_ready)
                },
                fontSize = 14.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Recognized text (partial results)
            if (partialText.isNotBlank() && state == AssistantState.LISTENING) {
                Text(
                    text = partialText,
                    fontSize = 16.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }

            if (state == AssistantState.LISTENING && isRawVoice) {
                Text(
                    text = stringResource(R.string.stop_recording_hint),
                    fontSize = 13.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                )
            }

            // User Query (Final Result)
            if (userQuery.isNotBlank()) {
                Text(
                    text = "$userQuery",
                    fontSize = 16.sp,
                    color = Color.DarkGray, // Slightly different color
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Main text
            if (displayText.isNotBlank() && state != AssistantState.LISTENING) {
                Text(
                    text = displayText,
                    fontSize = 18.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Error message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    fontSize = 14.sp,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_try_again))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
