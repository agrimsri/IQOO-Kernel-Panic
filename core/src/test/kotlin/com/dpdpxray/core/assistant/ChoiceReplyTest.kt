package com.dpdpxray.core.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ChoiceReplyTest {
    private val options = listOf(AppCandidate("Instagram", "com.instagram.android"), AppCandidate("Instagram Lite", "com.instagram.lite"))
    private val whatsapp = listOf(AppCandidate("WhatsApp", "com.whatsapp"), AppCandidate("WhatsApp Business", "com.whatsapp.w4b"))

    @Test fun `ordinal answers in English and Hindi`() {
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("the first one", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("second", options))
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("pehla wala", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("दूसरा", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("the second one please", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("दूसरा वाला कर दो", options))
    }

    @Test fun `naming one of the options picks it`() {
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("Instagram Lite", options))
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("instagram", options))
    }

    @Test fun `a tapped option is picked even when its label holds a filler word`() {
        // The copilot's chips send the label itself; "My" must not be stripped from "My Jio".
        val jio = listOf(AppCandidate("My Jio", "com.jio.myjio"), AppCandidate("JioTV", "com.jio.jioplay.tv"))
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("My Jio", jio))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("JioTV", jio))
    }

    @Test fun `check verbs and filler around the name are ignored`() {
        // "दो" here is "do it", not "two": it must not pick WhatsApp Business.
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("व्हाट्सएप की जाँच कर दो", whatsapp))
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("WhatsApp please", whatsapp))
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("check WhatsApp", whatsapp))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("WhatsApp Business ko check karo", whatsapp))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("Business wala", whatsapp))
    }

    @Test fun `a word found in only one option's label picks that option`() {
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("the Lite one", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("Instagram, the lite version", options))
    }

    @Test fun `a bare number counts only when it is the only word left`() {
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("one", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("two", options))
        assertEquals(ChoiceReply.Picked(0), ChoiceReply.parse("एक", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("दो", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("दो वाला", options))
        assertEquals(ChoiceReply.Unclear, ChoiceReply.parse("कर दो", options))
    }

    @Test fun `a name is matched before a leading no`() {
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("No, Instagram Lite", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("नहीं, इंस्टाग्राम लाइट", options))
        assertEquals(ChoiceReply.Picked(1), ChoiceReply.parse("no, the second one", options))
    }

    @Test fun `a negated name is never picked`() {
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("not Instagram", options))
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("इंस्टाग्राम नहीं", options))
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("don't check anything", options))
    }

    @Test fun `cancelling`() {
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("never mind", options))
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("rehne do", options))
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("कोई नहीं", options))
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("no", options))
        assertEquals(ChoiceReply.Cancel, ChoiceReply.parse("neither", options))
    }

    @Test fun `anything else is unclear`() {
        assertEquals(ChoiceReply.Unclear, ChoiceReply.parse("open settings", options))
        assertEquals(ChoiceReply.Unclear, ChoiceReply.parse("", options))
        assertEquals(ChoiceReply.Unclear, ChoiceReply.parse("check it please", options))
    }
}
