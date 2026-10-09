package com.dpdpxray.core.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CopilotQuestionsTest {
    @Test
    fun `matches spoken English questions`() {
        assertEquals(CopilotIntent.CHILDREN, CopilotQuestions.match("is it safe for my kids"))
        assertEquals(CopilotIntent.AFTER_REJECT, CopilotQuestions.match("did it keep tracking after I refused consent"))
        assertEquals(CopilotIntent.BEFORE_CONSENT, CopilotQuestions.match("which trackers ran before I agreed"))
        assertEquals(CopilotIntent.SCORE, CopilotQuestions.match("What's the score?"))
    }

    @Test
    fun `matches Hindi questions`() {
        assertEquals(CopilotIntent.CHILDREN, CopilotQuestions.match("क्या यह बच्चों के लिए सुरक्षित है"))
        assertEquals(CopilotIntent.AFTER_REJECT, CopilotQuestions.match("मना करने के बाद क्या हुआ"))
    }

    @Test
    fun `whole words only`() {
        assertNull(CopilotQuestions.match("scoreboard of the cricket match"))
        assertNull(CopilotQuestions.match("what is the weather"))
    }

    @Test
    fun `every chip matches its own intent in both languages`() {
        CopilotQuestions.chips.forEach {
            assertEquals(it.en, it.intent, CopilotQuestions.match(it.en))
            assertEquals(it.hi, it.intent, CopilotQuestions.match(it.hi))
        }
    }
}
