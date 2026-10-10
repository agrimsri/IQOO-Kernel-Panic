package com.dpdpxray.core.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerdictWordsTest {
    @Test
    fun `flags verdicts TraceVeil never gives`() {
        assertEquals("compliant", VerdictWords.find("This app is compliant with the DPDP Act."))
        assertEquals("non-compliant", VerdictWords.find("The SDK makes the app non-compliant."))
        assertEquals("illegal", VerdictWords.find("Loading AppsFlyer first is ILLEGAL."))
        assertEquals("is safe", VerdictWords.find("Otherwise the app is safe."))
        assertEquals("safe to use", VerdictWords.find("After this fix it is safe to use."))
        assertEquals("violation", VerdictWords.find("This is a clear violation of consent rules."))
        assertEquals("malicious", VerdictWords.find("The host looks malicious."))
        assertEquals("data was sent", VerdictWords.find("Your data was sent to Meta."))
    }

    @Test
    fun `flags Hindi verdicts`() {
        assertEquals("अवैध", VerdictWords.find("यह तरीका अवैध है।"))
        assertEquals("उल्लंघन", VerdictWords.find("यह सहमति नियमों का उल्लंघन है।"))
    }

    @Test
    fun `leaves neutral explanations alone`() {
        assertNull(VerdictWords.find("The app looked up graph.facebook.com 0.4 s after launch, before any consent choice [s6(1)]."))
        assertNull(VerdictWords.find("Ask for consent first, then initialise the SDK. This helps DPDP compliance readiness."))
        assertNull(VerdictWords.find("Google Safe Browsing and safety settings are unrelated to this finding."))
        assertNull(VerdictWords.find("A lookup is an attempt to contact a host, not proof that data was shared."))
    }
}
