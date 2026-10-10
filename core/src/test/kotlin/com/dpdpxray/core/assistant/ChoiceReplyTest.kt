package com.dpdpxray.core.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ChoiceReplyTest {
    private val options = listOf(AppCandidate("Instagram", "com.instagram.android"), AppCandidate("Instagram Lite", "com.instagram.lite"))

    @Test fun `ordinal answers in English and Hindi`() {
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("the first one", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("second", options))
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("pehla wala", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("दूसरा", options))
    }

    @Test fun `naming one of the options picks it`() {
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("Instagram Lite", options))
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("instagram", options))
    }

    @Test fun `cancelling`() {
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("never mind", options))
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("rehne do", options))
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("कोई नहीं", options))
    }

    @Test fun `anything else is unclear`() {
        assertEquals(ChoiceReply.Unclear, ChoiceReply.parse("open settings", options))
        assertEquals(ChoiceReply.Unclear, ChoiceReply.parse("", options))
    }
}
