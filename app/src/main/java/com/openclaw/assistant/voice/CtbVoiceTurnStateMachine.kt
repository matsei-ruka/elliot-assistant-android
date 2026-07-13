package com.openclaw.assistant.voice

/**
 * Generation-guarded CTB voice turn state. A late callback can mutate state
 * only when it still owns the active generation.
 */
class CtbVoiceTurnStateMachine {
    enum class State {
        IDLE,
        RECORDING,
        WAITING_FOR_REPLY,
        PREPARING_PLAYBACK,
        PLAYING_REMOTE_AUDIO,
        PREPARING_LOCAL_TTS,
        PLAYING_LOCAL_TTS,
        CANCELLED,
        ERROR,
    }

    private var generation = 0L
    var state: State = State.IDLE
        private set

    @Synchronized
    fun begin(): Long {
        generation += 1
        state = State.RECORDING
        return generation
    }

    @Synchronized
    fun transition(owner: Long, next: State): Boolean {
        if (owner != generation || !isAllowed(state, next)) return false
        state = next
        return true
    }

    @Synchronized
    fun cancel(): Long {
        generation += 1
        state = State.CANCELLED
        return generation
    }

    @Synchronized
    fun isCurrent(owner: Long): Boolean = owner == generation && state != State.CANCELLED

    private fun isAllowed(from: State, to: State): Boolean = when (from) {
        State.RECORDING -> to == State.WAITING_FOR_REPLY || to == State.ERROR
        State.WAITING_FOR_REPLY -> to == State.PREPARING_PLAYBACK ||
            to == State.PREPARING_LOCAL_TTS || to == State.ERROR
        State.PREPARING_PLAYBACK -> to == State.PLAYING_REMOTE_AUDIO || to == State.ERROR
        State.PLAYING_REMOTE_AUDIO,
        State.PLAYING_LOCAL_TTS -> to == State.IDLE || to == State.ERROR
        State.PREPARING_LOCAL_TTS -> to == State.PLAYING_LOCAL_TTS || to == State.ERROR
        State.ERROR,
        State.CANCELLED -> to == State.IDLE
        State.IDLE -> to == State.RECORDING
    }
}
