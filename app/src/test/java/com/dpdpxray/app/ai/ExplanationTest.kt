package com.dpdpxray.app.ai

import com.dpdpxray.core.dpdp.Passage
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplanationTest {
    private val c1 = Finding(
        checkId = CheckId.C1, severity = Severity.HIGH, evidenceType = EvidenceType.OBSERVED, title = CheckId.C1.title,
        explanation = "The app looked up hosts the tracker catalogue lists for AppsFlyer before the user made any consent choice. " +
            "Each lookup is a catalogue match. Under the DPDP Act, consent comes first.",
        evidence = listOf("launches.appsflyersdk.com (AppsFlyer) — catalogue match: attribution — looked up 0.1 s after launch"),
        plans = listOf(AuditPlan.ACCEPT), dpdpRefs = listOf("s4(1)", "s6(1)"), penaltyBand = PenaltyBand.RESIDUAL_50,
    )
    private val law = listOf(passage("s4(1)"), passage("s6(1)"))

    private fun passage(id: String) = Passage(id = id, title = id, text = "text of $id", source = "DPDP Act 2023", verbatim = true)

    @Test
    fun `a grounded model answer is labelled AI-inferred and names the model`() {
        val e = Explanations.fromModel("AppsFlyer was reached before consent [s6(1)]. Section 4 needs a lawful basis.", c1, law, false, "Gemma 4 E2B (GPU)")
        assertEquals(ResultSource.AI_INFERRED, e.source)
        assertTrue(e.detail.contains("Gemma 4 E2B (GPU)"))
    }

    @Test
    fun `empty or ungrounded model answers fall back to rules text, never a blank`() {
        val empty = Explanations.fromModel("  ", c1, law, false, "m")
        assertEquals(ResultSource.FALLBACK, empty.source)
        assertTrue(empty.text.startsWith("C1: Tracker hosts looked up before consent."))
        assertTrue(empty.text.contains("launches.appsflyersdk.com"))

        val invented = Explanations.fromModel("This breaks Section 33 of the Act.", c1, law, false, "m")
        assertEquals(ResultSource.FALLBACK, invented.source)
        assertTrue(invented.detail.contains("cited law it was not given"))
        assertFalse(Explanations.groundedIn("See [s9(3)].", law))
        assertTrue(Explanations.groundedIn("Under s. 6 consent must come first [s4(1)].", law))
    }

    @Test
    fun `hindi fallback is in Devanagari and keeps the evidence`() {
        val e = Explanations.fallback(c1, hindi = true, reason = "the model is not loaded")
        assertEquals(ResultSource.FALLBACK, e.source)
        assertTrue(e.text.contains("नियमों पर आधारित"))
        assertTrue(e.text.contains("launches.appsflyersdk.com"))
    }

    @Test
    fun `finding sources are observed, AI-inferred screen reading, or documentation-inferred`() {
        val session = AuditSession(id = "a", targetPackage = "p", targetLabel = "p", plan = AuditPlan.ACCEPT)
        assertEquals(ResultSource.OBSERVED, ResultSources.of(c1, listOf(session)))
        val c10 = c1.copy(checkId = CheckId.C10, evidenceType = EvidenceType.INFERRED)
        assertEquals(ResultSource.INFERRED, ResultSources.of(c10, listOf(session)))
        val c5 = c1.copy(checkId = CheckId.C5, evidenceType = EvidenceType.INFERRED)
        assertEquals(ResultSource.INFERRED, ResultSources.of(c5, listOf(session)))
    }

    @Test
    fun `markdown markers from the model are not shown literally`() {
        val e = Explanations.fromModel("**क्या गलत है:** सहमति मना की।\n* **What to change:** delay the SDK.", c1, law, true, "m")
        assertEquals("क्या गलत है: सहमति मना की।\nWhat to change: delay the SDK.", e.text)
    }
}
