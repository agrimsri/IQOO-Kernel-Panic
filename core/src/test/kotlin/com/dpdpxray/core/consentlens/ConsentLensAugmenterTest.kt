package com.dpdpxray.core.consentlens

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.UiNode
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.screen.DeterministicScreenAuditor
import com.dpdpxray.core.screen.Fixtures
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentLensAugmenterTest {
    /** Returns fixed roles per text; anything unknown is OTHER. Records what it was asked. */
    private class FakeClassifier(private val roles: Map<String, Pair<ConsentRole, Double>>, private val fail: Boolean = false) :
        ConsentRoleClassifier {
        val asked = mutableListOf<String>()
        override val modelId = "ConsentLens-1"
        override val backendLabel = "fake"
        override fun classify(texts: List<String>): List<RolePrediction> {
            if (fail) error("model not loaded")
            asked += texts
            return texts.map { t ->
                val (role, conf) = roles[t] ?: (ConsentRole.OTHER to 0.99)
                RolePrediction(t, role, conf, ConsentRole.entries.map { if (it == role) conf else (1 - conf) / 6 })
            }
        }
    }

    private val germanDialog = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            Fixtures.label("Wir verwenden Ihre Daten für personalisierte Werbung."),
            Fixtures.checkbox("Personalisierte Werbung", checked = true),
            Fixtures.button("Alle akzeptieren"),
            Fixtures.button("Alle ablehnen"),
        ),
    )
    private val germanRoles = mapOf(
        "Wir verwenden Ihre Daten für personalisierte Werbung." to (ConsentRole.PURPOSE_NOTICE to 0.93),
        "Personalisierte Werbung" to (ConsentRole.PURPOSE_NOTICE to 0.88),
        "Alle akzeptieren" to (ConsentRole.ACCEPT to 0.97),
        "Alle ablehnen" to (ConsentRole.REJECT to 0.96),
    )

    @Test
    fun `a German consent screen the lexicon misses becomes an inferred consent screen`() {
        val det = DeterministicScreenAuditor.audit(germanDialog)
        assertFalse(det.isConsentScreen) // EN/HI/KN lexicon cannot read it
        val a = ConsentLensAugmenter.augment(det, germanDialog, FakeClassifier(germanRoles))
        assertTrue(a.isConsentScreen)
        assertEquals(RejectOption.VISIBLE, a.rejectOption)
        assertEquals(true, a.preTicked)
        assertEquals(true, a.purposeStated)
        assertTrue(a.observedFields.isEmpty()) // nothing the model says becomes OBSERVED
        val lens = a.consentLens!!
        assertEquals("ConsentLens-1", lens.modelId)
        assertTrue(lens.inferredFields.containsAll(listOf("isConsentScreen", "rejectOption", "preTicked", "purposeStated")))
        assertTrue(lens.support.getValue("rejectOption").single().contains("Alle ablehnen"))
        assertEquals(AuditSource.MERGED, a.source)
        assertEquals(0.93, a.confidence, 1e-9) // the weakest prediction behind an inferred field
    }

    @Test
    fun `rules turn the inferred screen into INFERRED findings that name the model`() {
        val a = ConsentLensAugmenter.augment(DeterministicScreenAuditor.audit(germanDialog), germanDialog, FakeClassifier(germanRoles))
        val s = 1_000_000_000L
        val session = AuditSession(
            id = "a", targetPackage = "de.example", targetLabel = "Example", plan = AuditPlan.ACCEPT, endedAtNanos = 40 * s,
            netEvents = listOf(NetEvent(tNanos = 8 * s, hostname = "api.example.de"), NetEvent(tNanos = 9 * s, hostname = "cdn.example.de")),
            consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.ACCEPT)),
            screenAudit = a, privateDnsActive = false,
        )
        val c3 = RulesEngine(TrackerCatalog.loadDefault()).evaluate(listOf(session)).findings.single { it.checkId == CheckId.C3 }
        assertEquals(EvidenceType.INFERRED, c3.evidenceType)
        assertTrue(c3.evidence.any { it.startsWith("AI-inferred by ConsentLens-1") && it.contains("Alle akzeptieren") })
        assertTrue(c3.confidence < 1.0)
    }

    @Test
    fun `an OBSERVED tree fact is never overridden and the disagreement stays visible`() {
        val tree = Fixtures.leakyDialog.copy(children = Fixtures.leakyDialog.children + Fixtures.button("Ablehnen"))
        val det = DeterministicScreenAuditor.audit(tree)
        assertEquals(RejectOption.HIDDEN, det.rejectOption)
        val a = ConsentLensAugmenter.augment(det, tree, FakeClassifier(mapOf("Ablehnen" to (ConsentRole.REJECT to 0.95))))
        assertEquals(RejectOption.HIDDEN, a.rejectOption)
        assertTrue("rejectOption" in a.observedFields)
        assertFalse("rejectOption" in a.consentLens!!.inferredFields)
        assertTrue(a.conflicts.any { it.startsWith("rejectOption: tree=HIDDEN, ConsentLens-1=VISIBLE") })
    }

    @Test
    fun `strings the lexicon already recognises are not sent to the model`() {
        val fake = FakeClassifier(emptyMap())
        ConsentLensAugmenter.augment(DeterministicScreenAuditor.audit(Fixtures.leakyDialog), Fixtures.leakyDialog, fake)
        assertFalse("Accept & Continue" in fake.asked)
        assertFalse("Manage" in fake.asked)
        assertTrue("We value your privacy" in fake.asked)
    }

    @Test
    fun `predictions below the threshold change nothing`() {
        val weak = germanRoles.mapValues { (_, v) -> v.first to 0.5 }
        val det = DeterministicScreenAuditor.audit(germanDialog)
        val a = ConsentLensAugmenter.augment(det, germanDialog, FakeClassifier(weak))
        assertFalse(a.isConsentScreen)
        assertEquals(RejectOption.UNKNOWN, a.rejectOption)
        assertTrue(a.consentLens!!.inferredFields.isEmpty())
    }

    @Test
    fun `a classifier failure leaves the audit untouched`() {
        val det = DeterministicScreenAuditor.audit(germanDialog)
        assertSame(det, ConsentLensAugmenter.augment(det, germanDialog, FakeClassifier(germanRoles, fail = true)))
    }

    @Test
    fun `fields set by the screen-review model are kept and disagreements are listed`() {
        val vlm = ScreenAudit(isConsentScreen = true, rejectOption = RejectOption.HIDDEN, withdrawalInfo = false, source = AuditSource.MERGED, confidence = 0.7)
        val tree = germanDialog.copy(children = germanDialog.children + Fixtures.label("Sie können Ihre Einwilligung jederzeit widerrufen."))
        val roles = germanRoles + ("Sie können Ihre Einwilligung jederzeit widerrufen." to (ConsentRole.WITHDRAW_INFO to 0.9))
        val a = ConsentLensAugmenter.augment(vlm, tree, FakeClassifier(roles))
        assertEquals(RejectOption.HIDDEN, a.rejectOption)
        assertEquals(false, a.withdrawalInfo)
        val lens = a.consentLens!!
        assertTrue(lens.disagreements.any { it.startsWith("rejectOption: screen review=HIDDEN") })
        assertTrue(lens.disagreements.any { it.startsWith("withdrawalInfo") })
        assertFalse("rejectOption" in lens.inferredFields)
    }

    @Test
    fun `absence of a string is never turned into a missing notice element`() {
        val a = ConsentLensAugmenter.augment(DeterministicScreenAuditor.audit(germanDialog), germanDialog, FakeClassifier(germanRoles))
        assertNull(a.withdrawalInfo)
        assertNull(a.itemisedData)
        assertNull(a.consentLens!!.privacyLinkFound)
    }

    @Test
    fun `withdrawal text and a privacy link are found in another language`() {
        val tree = germanDialog.copy(
            children = germanDialog.children + Fixtures.label("Sie können Ihre Einwilligung jederzeit widerrufen.") +
                UiNode(className = "android.widget.TextView", text = "Datenschutzerklärung", isClickable = true),
        )
        val roles = germanRoles + mapOf(
            "Sie können Ihre Einwilligung jederzeit widerrufen." to (ConsentRole.WITHDRAW_INFO to 0.9),
            "Datenschutzerklärung" to (ConsentRole.PRIVACY_LINK to 0.94),
        )
        val a = ConsentLensAugmenter.augment(DeterministicScreenAuditor.audit(tree), tree, FakeClassifier(roles))
        assertEquals(true, a.withdrawalInfo)
        assertTrue("withdrawalInfo" in a.consentLens!!.inferredFields)
        assertEquals(true, a.consentLens!!.privacyLinkFound)
    }

    @Test
    fun `an ordinary screen stays a non consent screen`() {
        val a = ConsentLensAugmenter.augment(
            DeterministicScreenAuditor.audit(Fixtures.productGrid), Fixtures.productGrid,
            FakeClassifier(mapOf("Add to cart" to (ConsentRole.ACCEPT to 0.9))),
        )
        assertFalse(a.isConsentScreen)
        assertTrue(a.consentLens!!.inferredFields.isEmpty())
    }

    @Test
    fun `two short purpose labels give an inferred purpose count`() {
        val tree = germanDialog.copy(children = germanDialog.children + Fixtures.checkbox("Statistik", checked = false))
        val roles = germanRoles + ("Statistik" to (ConsentRole.PURPOSE_NOTICE to 0.9))
        val a = ConsentLensAugmenter.augment(DeterministicScreenAuditor.audit(tree), tree, FakeClassifier(roles))
        assertEquals(2, a.purposesCount)
        assertNull(a.separateChoices) // never inferred: C5 still needs the screen review
    }
}
