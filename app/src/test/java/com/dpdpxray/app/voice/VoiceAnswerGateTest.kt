package com.dpdpxray.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAnswerGateTest {
    @Test fun `new question rejects a slower answer to the old question`() {
        val gate = VoiceAnswerGate()
        val old = gate.begin()
        val current = gate.begin()
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(current))
    }

    @Test fun `leaving screen rejects the answer and cannot resume speaking`() {
        val gate = VoiceAnswerGate()
        val request = gate.begin()
        gate.invalidate()
        assertFalse(gate.isCurrent(request))
    }

    @Test fun `starting microphone rejects a chip answer that completes during listening`() {
        val gate = VoiceAnswerGate()
        val delayedChip = gate.begin()
        gate.beginListening()
        assertFalse("retrieval completion must not start speech into the microphone", gate.isCurrent(delayedChip))
        val recognizedQuestion = gate.begin()
        assertTrue(gate.isCurrent(recognizedQuestion))
        assertFalse(gate.isCurrent(delayedChip))
    }
}
