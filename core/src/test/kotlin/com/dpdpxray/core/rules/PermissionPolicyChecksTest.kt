package com.dpdpxray.core.rules

import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.model.UiNode
import com.dpdpxray.core.screen.Fixtures
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C14 (Android permission dialog before any consent screen or notice) and C15 (consent screen without a privacy-policy
 * link). Dialog texts are Android's own wording for the permission controller; trees are shaped like the app's
 * accessibility copies. Test data, not device replays.
 */
class PermissionPolicyChecksTest {
    private val s = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()
    private val engine = RulesEngine(catalog)
    private val firstParty = listOf(NetEvent(1 * s, "api.shop.app"), NetEvent(2 * s, "api.shop.app"))

    private fun session(
        consent: List<ConsentEvent>,
        watched: Boolean? = true,
        fresh: Boolean? = true,
        plan: AuditPlan = AuditPlan.SILENT,
    ) = AuditSession(
        id = plan.name, targetPackage = "com.shop.app", targetLabel = "ShopNow", plan = plan, endedAtNanos = 30 * s,
        netEvents = firstParty, consentEvents = consent, freshState = fresh, permissionDialogsWatched = watched,
    )

    private fun dialog(t: Double, text: String) = ConsentEvent((t * s).toLong(), ConsentKind.PERMISSION_DIALOG, text)
    private fun screen(t: Double, tree: UiNode? = null) = ConsentEvent((t * s).toLong(), ConsentKind.SCREEN_APPEARED, tree = tree)

    private val location = "Allow ShopNow to access this device's location?"
    private val notifications = "Allow ShopNow to send you notifications?"

    // --- C14 ---

    @Test
    fun `location dialog before the consent screen gives C14 low observed with s5(1)`() {
        val r = engine.evaluate(listOf(session(listOf(dialog(1.2, location), screen(3.5)))))
        val c14 = r.findings.single { it.checkId == CheckId.C14 }
        assertEquals(Severity.LOW, c14.severity)
        assertEquals(EvidenceType.OBSERVED, c14.evidenceType)
        assertEquals(PenaltyBand.NONE, c14.penaltyBand)
        assertEquals(listOf("s5(1)"), c14.dpdpRefs)
        assertEquals(
            listOf("Android permission dialog \"$location\" — shown 1.2 s after launch, 2.3 s before the consent screen appeared [Plan C · No interaction]"),
            c14.evidence,
        )
        assertEquals(97, r.score)
        assertTrue(FixCatalog.loadDefault().fixesFor(r, catalog).any { it.id == "ui-permission-notice" })
    }

    @Test
    fun `only a notification prompt first is informational with no legal reference`() {
        val r = engine.evaluate(listOf(session(listOf(dialog(1.0, notifications), screen(3.0)))))
        val c14 = r.findings.single { it.checkId == CheckId.C14 }
        assertEquals(Severity.INFO, c14.severity)
        assertTrue(c14.dpdpRefs.isEmpty())
        assertTrue(c14.evidence.single().contains("(notification permission: grants no access to personal data)"))
        assertEquals(100, r.score)
    }

    @Test
    fun `Hindi notification prompt is recognised as notification only`() {
        val hi = "ShopNow को आपको सूचनाएं भेजने की अनुमति दें?"
        assertEquals(Severity.INFO, engine.evaluate(listOf(session(listOf(dialog(1.0, hi), screen(3.0))))).findings.single { it.checkId == CheckId.C14 }.severity)
    }

    @Test
    fun `fresh run with a permission dialog and no consent screen says no notice was seen in the capture`() {
        val c14 = engine.evaluate(listOf(session(listOf(dialog(0.8, location))))).findings.single { it.checkId == CheckId.C14 }
        assertTrue(c14.evidence.single(), c14.evidence.single().endsWith("and no consent screen or notice was seen in this capture (30.0 s) [Plan C · No interaction]"))
    }

    @Test
    fun `dialog after the consent screen is not C14`() {
        assertNull(engine.evaluate(listOf(session(listOf(screen(1.0), dialog(4.0, location))))).findings.firstOrNull { it.checkId == CheckId.C14 })
    }

    @Test
    fun `dialog that does not name the audited app is not attributed`() {
        val other = "Allow Camera to take pictures and record video?"
        assertNull(engine.evaluate(listOf(session(listOf(dialog(1.0, other), screen(3.0))))).findings.firstOrNull { it.checkId == CheckId.C14 })
    }

    @Test
    fun `kept data and no consent screen leaves the notice history unknown`() {
        val r = engine.evaluate(listOf(session(listOf(dialog(1.0, location)), fresh = false)))
        assertNull(r.findings.firstOrNull { it.checkId == CheckId.C14 })
    }

    @Test
    fun `audits that did not watch permission dialogs establish nothing`() {
        assertNull(engine.evaluate(listOf(session(listOf(dialog(1.0, location), screen(3.0)), watched = null))).findings.firstOrNull { it.checkId == CheckId.C14 })
    }

    @Test
    fun `a permission dialog is never a consent screen or choice`() {
        val sess = session(listOf(dialog(1.0, "Allow ShopNow to access your contacts?")))
        assertFalse(sess.consentScreenSeen)
        assertNull(sess.consentChoice)
    }

    // --- C15 ---

    private fun treeOf(vararg labels: String, link: String? = null) = UiNode(
        className = "android.widget.FrameLayout",
        children = labels.map { Fixtures.label(it) } + listOfNotNull(link?.let { UiNode("android.widget.TextView", it, isClickable = true) }) +
            listOf(Fixtures.button("Reject"), Fixtures.button("Accept")),
    )

    @Test
    fun `LeakyShop's consent dialog has no privacy-policy link, reported as info`() {
        val r = engine.evaluate(listOf(session(listOf(screen(0.9, Fixtures.leakyDialog)))))
        val c15 = r.findings.single { it.checkId == CheckId.C15 }
        assertEquals(Severity.INFO, c15.severity)
        assertEquals(EvidenceType.OBSERVED, c15.evidenceType)
        assertTrue(c15.dpdpRefs.isEmpty())
        assertEquals(
            listOf(
                "Consent screen captured 0.9 s after launch: 5 text elements read (\"We value your privacy\", \"We use your data to give " +
                    "you the best offers.\", \"Share usage data to improve offers\", …); none names a privacy policy or notice [Plan C · No interaction]",
            ),
            c15.evidence,
        )
        assertEquals(100, r.score)
        assertTrue(FixCatalog.loadDefault().fixesFor(r, catalog).any { it.id == "ui-privacy-link" })
    }

    @Test
    fun `a privacy policy link in English, Hindi or Kannada, or a policy address, clears C15`() {
        for (link in listOf("Privacy Policy", "Read our privacy notice", "Terms & Privacy", "गोपनीयता नीति पढ़ें", "ಗೌಪ್ಯತಾ ನೀತಿ", "https://shop.app/privacy")) {
            val tree = treeOf("We value your privacy", "We use cookies and share data with partners.", link = link)
            assertNull(link, engine.evaluate(listOf(session(listOf(screen(1.0, tree))))).findings.firstOrNull { it.checkId == CheckId.C15 })
        }
    }

    @Test
    fun `the word privacy alone is not a policy link`() {
        assertFalse(ConsentLexicon.mentionsPrivacyPolicy(listOf("We value your privacy", "You can withdraw consent anytime in Settings › Privacy.")))
        assertTrue(ConsentLexicon.mentionsPrivacyPolicy(listOf("By continuing you agree to our Terms of Service and Privacy Policy.")))
        assertTrue(ConsentLexicon.mentionsPrivacyPolicy(listOf("See our Data Policy")))
    }

    @Test
    fun `any captured screen with a link clears C15 for the audit`() {
        val withLink = treeOf("Your privacy choices", link = "Privacy policy")
        val r = engine.evaluate(listOf(session(listOf(screen(1.0, Fixtures.leakyDialog))), session(listOf(screen(1.0, withLink)), plan = AuditPlan.REJECT)))
        assertNull(r.findings.firstOrNull { it.checkId == CheckId.C15 })
    }

    @Test
    fun `no captured tree, or a tree that may have been cut off, is not established`() {
        assertNull(engine.evaluate(listOf(session(listOf(screen(1.0))))).findings.firstOrNull { it.checkId == CheckId.C15 })
        val huge = UiNode("android.widget.FrameLayout", children = List(600) { Fixtures.label("Consent and privacy item $it") })
        assertNull(engine.evaluate(listOf(session(listOf(screen(1.0, huge))))).findings.firstOrNull { it.checkId == CheckId.C15 })
    }

    @Test
    fun `a hand-marked consent moment's tree counts like a detected screen`() {
        val mark = ConsentEvent(2 * s, ConsentKind.MANUAL_MARK, tree = Fixtures.leakyDialog)
        assertTrue(engine.evaluate(listOf(session(listOf(mark)))).findings.any { it.checkId == CheckId.C15 })
    }
}
