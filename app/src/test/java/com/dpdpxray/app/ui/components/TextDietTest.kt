package com.dpdpxray.app.ui.components

import com.dpdpxray.app.ui.theme.reduceMotionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDietTest {
    @Test fun `the lead is the first sentence and the rest folds`() {
        assertEquals(
            "Reads each guide step aloud as you move through TraceVeil, with an installed offline voice.",
            leadSentence("Reads each guide step aloud as you move through TraceVeil, with an installed offline voice. Off by default: tap the speaker."),
        )
    }

    @Test fun `one sentence stays whole`() {
        assertEquals("Larger text for projectors and screen sharing.", leadSentence("Larger text for projectors and screen sharing."))
        assertEquals("No full stop at all", leadSentence("  No full stop at all  "))
    }

    @Test fun `abbreviations and short openers do not end the lead`() {
        val npu = "NPU needs a bundle compiled for this phone's chip (its file name must name it, e.g. sm8850); the GPU model is never used in its place."
        assertEquals(npu, leadSentence(npu))
        assertEquals("No. This is a long enough first sentence here.", leadSentence("No. This is a long enough first sentence here. And more."))
    }

    @Test fun `hindi sentences end at the danda`() {
        assertEquals("यह जाँच इसी फ़ोन पर चलती है और कुछ भी नहीं भेजती।", leadSentence("यह जाँच इसी फ़ोन पर चलती है और कुछ भी नहीं भेजती। बाकी विवरण यहाँ है।"))
    }

    @Test fun `animations are off only when the system scale is zero`() {
        assertTrue(reduceMotionFor(0f))
        assertFalse(reduceMotionFor(1f))
        assertFalse(reduceMotionFor(0.5f))
    }
}
