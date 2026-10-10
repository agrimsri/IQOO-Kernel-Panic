package com.dpdpxray.core.assistant

import com.dpdpxray.core.copilot.AuditEvidence
import com.dpdpxray.core.copilot.CopilotLanguage
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditSpokenSummaryTest {
    private fun finding(id: CheckId, severity: Severity = Severity.HIGH, entities: List<String> = emptyList()) = Finding(
        checkId = id, severity = severity, evidenceType = EvidenceType.OBSERVED, title = id.title, explanation = "${id.title}.",
        evidence = emptyList(), plans = listOf(AuditPlan.ACCEPT), dpdpRefs = emptyList(), penaltyBand = PenaltyBand.NONE, entities = entities,
    )

    private val leaky = AuditEvidence(
        label = "LeakyShop", sample = false,
        result = AuditResult(
            findings = listOf(
                finding(CheckId.C3, Severity.MEDIUM),
                finding(CheckId.C1, Severity.CRITICAL, listOf("Google Analytics for Firebase", "Meta", "AppsFlyer", "Adjust")),
                finding(CheckId.C10, Severity.HIGH),
            ),
            score = 21, band = ScoreBand.AT_RISK,
        ),
        plans = setOf(AuditPlan.ACCEPT), completedPlans = setOf(AuditPlan.ACCEPT),
    )

    private fun en(e: AuditEvidence) = AuditSpokenSummary.summarize(e, CopilotLanguage.EN)
    private fun hi(e: AuditEvidence) = AuditSpokenSummary.summarize(e, CopilotLanguage.HI)

    @Test fun `score, band, headline and the most serious finding come from the saved result`() {
        val text = en(leaky)
        assertTrue(text, text.startsWith("In this check, LeakyShop scored 21 out of 100: At risk."))
        assertTrue(text, text.contains("4 tracker hosts were looked up before any consent choice: Google Analytics for Firebase, Meta, AppsFlyer and 1 more."))
        assertTrue(text, text.contains("3 findings; the most serious is C1, ${CheckId.C1.title}."))
    }

    @Test fun `a refusal finding leads the headline`() {
        val e = leaky.copy(result = leaky.result.copy(findings = leaky.result.findings + finding(CheckId.C2, Severity.CRITICAL, listOf("Meta"))))
        assertTrue(en(e).contains("Tracker hosts were looked up after consent was refused: Meta."))
    }

    @Test fun `the most serious finding is the highest severity, earliest first`() {
        assertEquals(CheckId.C1, AuditSpokenSummary.mostSerious(leaky.result.findings)?.checkId)
        assertEquals(null, AuditSpokenSummary.mostSerious(emptyList()))
    }

    @Test fun `an inconclusive audit has no score and says so`() {
        val e = leaky.copy(result = AuditResult(listOf(finding(CheckId.V0, Severity.INFO)), null, ScoreBand.INCONCLUSIVE))
        val text = en(e)
        assertTrue(text, text.contains("too few lookups were captured to give a score"))
        assertFalse(text, text.contains("out of 100"))
        assertFalse(text, text.contains("history"))
    }

    @Test fun `a clean full score is never called safe`() {
        val clean = leaky.copy(label = "LeakyShop Fixed", result = AuditResult(emptyList(), 100, ScoreBand.READY))
        val text = en(clean)
        assertTrue(text, text.contains("scored 100 out of 100"))
        assertTrue(text, text.contains("No tracker lookups before consent and no consent-screen problems were recorded."))
        assertTrue(text, text.contains("not a safety certificate"))
        listOf(" is safe", "malware", "malicious", "fraud", "compliant", "illegal").forEach { assertFalse(it, text.lowercase().contains(it)) }
    }

    @Test fun `a missing Accept or Reject choice is spoken`() {
        val missed = leaky.copy(plans = setOf(AuditPlan.ACCEPT, AuditPlan.REJECT), completedPlans = setOf(AuditPlan.REJECT))
        assertTrue(en(missed).contains("Your Accept choice was not recorded, so this check is incomplete."))
        val noReject = leaky.copy(plans = setOf(AuditPlan.ACCEPT, AuditPlan.REJECT), completedPlans = setOf(AuditPlan.ACCEPT))
        assertTrue(en(noReject).contains("refusal was not tested"))
    }

    @Test fun `samples are labelled`() {
        assertTrue(en(leaky.copy(sample = true)).startsWith("This is the sample audit, not a live result."))
    }

    @Test fun `Hindi summary carries the same facts`() {
        val text = hi(leaky)
        assertTrue(text, text.contains("इस जाँच में LeakyShop का स्कोर 100 में से 21 है: जोखिम में।"))
        assertTrue(text, text.contains("C1"))
        assertTrue(text, text.contains("4 ट्रैकर होस्ट"))
    }

    @Test fun `every live summary is limited to this check`() {
        val variants = listOf(leaky, leaky.copy(result = AuditResult(emptyList(), 100, ScoreBand.READY)), keptDataOld, keptDataNew, keptDataNoTrackers)
        variants.forEach { assertTrue(en(it).contains("In this check")); assertTrue(hi(it).contains("इस जाँच में")) }
    }

    // --- consent history unknown (U1): never a readiness verdict -------------------------------------------------------

    private val u1Trackers = finding(CheckId.U1, Severity.INFO, listOf("Google Analytics for Firebase", "Meta", "AppsFlyer", "Adjust"))

    /** Saved before U1 with tracker lookups made a result inconclusive: band READY and a full score. */
    private val keptDataOld = leaky.copy(label = "Instagram", result = AuditResult(listOf(u1Trackers), 100, ScoreBand.READY))

    /** The same check scored by the current rules: Inconclusive, no score. */
    private val keptDataNew = keptDataOld.copy(result = AuditResult(listOf(u1Trackers), null, ScoreBand.INCONCLUSIVE))

    private val keptDataNoTrackers = keptDataOld.copy(result = AuditResult(listOf(finding(CheckId.U1, Severity.INFO)), 100, ScoreBand.READY))

    private val readyWord = Regex("\\bready\\b", RegexOption.IGNORE_CASE)

    @Test fun `an old saved READY result with U1 tracker lookups gets no score and no verdict`() {
        val text = en(keptDataOld)
        assertFalse(text, text.contains("out of 100"))
        assertFalse(text, readyWord.containsMatchIn(text))
        assertTrue(text, text.startsWith("In this check of Instagram, there is no readiness verdict"))
        assertTrue(text, text.contains("consent history is unknown"))
        assertTrue(text, text.contains("Google Analytics for Firebase, Meta, AppsFlyer and 1 more"))
        assertTrue(text, text.contains("run a Full check of a test install"))
        assertFalse(text, text.contains("safety certificate"))
        assertFalse(text, text.contains("consent screen needs work"))
    }

    @Test fun `a new INCONCLUSIVE result with U1 tracker lookups says Inconclusive because history is unknown`() {
        val text = en(keptDataNew)
        assertTrue(text, text.startsWith("In this check of Instagram, the result is Inconclusive"))
        assertTrue(text, text.contains("consent history is unknown"))
        assertFalse(text, text.contains("too few lookups"))
        assertFalse(text, text.contains("out of 100"))
        assertFalse(text, readyWord.containsMatchIn(text))
        assertTrue(text, text.contains("Google Analytics for Firebase, Meta, AppsFlyer and 1 more"))
        assertTrue(text, text.contains("run a Full check of a test install"))
    }

    @Test fun `U1 without tracker lookups may keep its score but adds the consent-history caveat and no band`() {
        val text = en(keptDataNoTrackers)
        assertTrue(text, text.startsWith("In this check, Instagram scored 100 out of 100."))
        assertTrue(text, text.contains("Consent history is unknown, because the app kept its data from before"))
        assertFalse(text, readyWord.containsMatchIn(text))
        assertTrue(text, text.contains("not a safety certificate"))
    }

    @Test fun `low visibility is called Inconclusive with too few lookups`() {
        val e = leaky.copy(result = AuditResult(listOf(finding(CheckId.V0, Severity.INFO)), null, ScoreBand.INCONCLUSIVE))
        assertTrue(en(e), en(e).contains("the result is Inconclusive: too few lookups were captured to give a score"))
        assertTrue(hi(e), hi(e).contains("अनिर्णायक"))
    }

    @Test fun `the consent screen is said to need work only when a consent-screen finding exists`() {
        val c10Only = leaky.copy(result = AuditResult(listOf(finding(CheckId.C10, Severity.MEDIUM)), 90, ScoreBand.READY))
        val text = en(c10Only)
        assertFalse(text, text.contains("consent screen needs work"))
        assertTrue(text, text.contains("No tracker lookups before consent were recorded."))
        assertTrue(text, text.contains("1 finding; the most serious is C10, ${CheckId.C10.title}."))
        listOf(CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8).forEach { id ->
            val screen = leaky.copy(result = AuditResult(listOf(finding(id, Severity.MEDIUM)), 85, ScoreBand.READY))
            assertTrue(id.code, en(screen).contains("the consent screen needs work"))
        }
    }

    @Test fun `no summary says DPDP-ready`() {
        val variants = listOf(leaky, leaky.copy(result = AuditResult(emptyList(), 100, ScoreBand.READY)), keptDataOld, keptDataNew, keptDataNoTrackers)
        variants.flatMap { listOf(en(it), hi(it)) }.forEach { assertFalse(it, it.contains("DPDP", ignoreCase = true)) }
        assertTrue(en(leaky.copy(result = AuditResult(emptyList(), 100, ScoreBand.READY))).contains("scored 100 out of 100: Ready."))
    }

    @Test fun `Hindi never calls an unknown-history result ready and names the trackers`() {
        listOf(keptDataOld, keptDataNew, keptDataNoTrackers).forEach { e ->
            val text = hi(e)
            assertFalse(text, text.contains("तैयार"))
            assertTrue(text, text.contains("सहमति का इतिहास अज्ञात है"))
        }
        val text = hi(keptDataNew)
        assertTrue(text, text.contains("अनिर्णायक"))
        assertFalse(text, text.contains("100 में से"))
        assertTrue(text, text.contains("Google Analytics for Firebase, Meta, AppsFlyer और 1 अन्य"))
        assertTrue(text, text.contains("पूरी जाँच"))
        assertFalse(hi(keptDataOld), hi(keptDataOld).contains("100 में से"))
    }
}
