package com.openclaw.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CtbVoiceTurnStateMachineTest {
    @Test
    fun `remote audio follows the valid state path`() {
        val machine = CtbVoiceTurnStateMachine()
        val owner = machine.begin()
        assertTrue(machine.transition(owner, CtbVoiceTurnStateMachine.State.WAITING_FOR_REPLY))
        assertTrue(machine.transition(owner, CtbVoiceTurnStateMachine.State.PREPARING_PLAYBACK))
        assertTrue(machine.transition(owner, CtbVoiceTurnStateMachine.State.PLAYING_REMOTE_AUDIO))
        assertTrue(machine.transition(owner, CtbVoiceTurnStateMachine.State.IDLE))
        assertEquals(CtbVoiceTurnStateMachine.State.IDLE, machine.state)
    }

    @Test
    fun `text fallback is a distinct single path`() {
        val machine = CtbVoiceTurnStateMachine()
        val owner = machine.begin()
        machine.transition(owner, CtbVoiceTurnStateMachine.State.WAITING_FOR_REPLY)
        assertTrue(machine.transition(owner, CtbVoiceTurnStateMachine.State.PREPARING_LOCAL_TTS))
        assertTrue(machine.transition(owner, CtbVoiceTurnStateMachine.State.PLAYING_LOCAL_TTS))
        assertFalse(machine.transition(owner, CtbVoiceTurnStateMachine.State.PLAYING_REMOTE_AUDIO))
        assertTrue(machine.transition(owner, CtbVoiceTurnStateMachine.State.IDLE))
    }

    @Test
    fun `cancellation invalidates late response and replacement owns new generation`() {
        val machine = CtbVoiceTurnStateMachine()
        val stale = machine.begin()
        machine.transition(stale, CtbVoiceTurnStateMachine.State.WAITING_FOR_REPLY)
        machine.cancel()
        assertFalse(machine.isCurrent(stale))
        assertFalse(machine.transition(stale, CtbVoiceTurnStateMachine.State.PREPARING_PLAYBACK))

        val replacement = machine.begin()
        assertTrue(machine.isCurrent(replacement))
        assertFalse(machine.isCurrent(stale))
    }

    @Test
    fun `repeated cleanup cancellation is idempotent for stale owners`() {
        val machine = CtbVoiceTurnStateMachine()
        val owner = machine.begin()
        machine.cancel()
        machine.cancel()
        assertEquals(CtbVoiceTurnStateMachine.State.CANCELLED, machine.state)
        assertFalse(machine.isCurrent(owner))
    }
}
