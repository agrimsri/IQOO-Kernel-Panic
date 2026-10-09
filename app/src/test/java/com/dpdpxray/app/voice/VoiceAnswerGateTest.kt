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
}
