package com.dpdpxray.core.darkpattern

import com.dpdpxray.core.copilot.VerdictWords
import com.dpdpxray.core.darkpattern.DarkPatternFixtures.b
import com.dpdpxray.core.darkpattern.DarkPatternFixtures.composeButton
import com.dpdpxray.core.darkpattern.DarkPatternFixtures.dialog
import com.dpdpxray.core.darkpattern.DarkPatternFixtures.fixedDialog
import com.dpdpxray.core.darkpattern.DarkPatternFixtures.leakyDialog
import com.dpdpxray.core.darkpattern.DarkPatternFixtures.leakySessions
import com.dpdpxray.core.darkpattern.DarkPatternFixtures.nagPopup
import com.dpdpxray.core.darkpattern.DarkPatternFixtures.shown
import com.dpdpxray.core.darkpattern.DarkPatternFixtures.text
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.UiNode
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.screen.Fixtures
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic dark-pattern signals at the consent moment. Test data (see DarkPatternFixtures), not device observations. */
class DarkPatternScannerTest {
    private val S = 1_000_000_000L

    private fun session(plan: AuditPlan, vararg events: ConsentEvent, audit: ScreenAudit? = null) =
        AuditSession(id = plan.name, targetPackage = "com.example", targetLabel = "Example", plan = plan, consentEvents = events.toList(), screenAudit = audit)

    private fun one(tree: UiNode) = listOf(session(AuditPlan.SILENT, shown(tree)))

    private fun patterns(signals: List<DarkPatternSignal>) = signals.map { it.pattern }

    private fun consentDialog(vararg content: UiNode) = dialog(b(0, 0, 1000, 2000), text("We value your privacy", b(50, 50, 950, 150)), *content)

    private val accept = composeButton("Accept", b(500, 1800, 950, 1950), b(600, 1850, 850, 1900), b(500, 1810, 950, 1940))
    private val reject = composeButton("Reject", b(50, 1800, 480, 1950), b(150, 1850, 400, 1900), b(50, 1810, 480, 1940))

    // --- The LeakyShop builds -------------------------------------------------------------------------------------------

    @Test
    fun `leaky build with the nag shows four patterns, all observed, in Annexure order`() {
        val signals = DarkPatternScanner.scan(leakySessions())
        assertEquals(
            listOf(CcpaPattern.FALSE_URGENCY, CcpaPattern.CONFIRM_SHAMING, CcpaPattern.INTERFACE_INTERFERENCE, CcpaPattern.NAGGING),
            patterns(signals),
        )
        assertTrue(signals.all { it.evidenceType == EvidenceType.OBSERVED })
        assertEquals(listOf("CCPA India 2023 Annexure 1 #1", "CCPA India 2023 Annexure 1 #3", "CCPA India 2023 Annexure 1 #6", "CCPA India 2023 Annexure 1 #10"), signals.map { it.source })
    }

    @Test
    fun `leaky first popup alone gives interface interference with size, style and tap cost`() {
        val signals = DarkPatternScanner.scan(leakySessions(nag = false))
        assertEquals(listOf(CcpaPattern.INTERFACE_INTERFERENCE), patterns(signals))
        val ii = signals.single()
        assertEquals(
            listOf(
                "\"Accept & Continue\" is shown as a button; the way to refuse (\"Manage\") is plain text.",
                "\"Accept & Continue\" covers 3.4× the screen area of \"Manage\" (587×171 px vs 171×171 px).",
                "Refusing took 2 taps (\"Manage\" → \"Reject all\"); accepting took 1 (\"Accept & Continue\").",
            ),
            ii.evidence,
        )
        assertEquals(listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT), ii.plans)
    }

    @Test
    fun `fixed build shows no signals`() {
        assertEquals(emptyList<DarkPatternSignal>(), DarkPatternScanner.scan(DarkPatternFixtures.fixedSessions()))
        assertEquals(3, DarkPatternScanner.screensChecked(DarkPatternFixtures.fixedSessions()))
        assertEquals(emptyList<DarkPatternSignal>(), DarkPatternScanner.scan(one(Fixtures.fixedDialog)))
    }

    @Test
    fun `older test fixture of the leaky dialog without bounds still shows the plain-text refusal`() {
        val ii = DarkPatternScanner.scan(one(Fixtures.leakyDialog)).single()
        assertEquals(CcpaPattern.INTERFACE_INTERFERENCE, ii.pattern)
        assertEquals(listOf("\"Accept & Continue\" is shown as a button; the way to refuse (\"Manage\") is plain text."), ii.evidence)
    }

    @Test
    fun `nothing is checked without a consent-screen tree`() {
        val noTree = listOf(session(AuditPlan.REJECT, ConsentEvent(2 * S, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * S, ConsentKind.REJECT, "Reject")))
        assertEquals(0, DarkPatternScanner.screensChecked(noTree))
        assertEquals(emptyList<DarkPatternSignal>(), DarkPatternScanner.scan(noTree))
        assertEquals(0, DarkPatternScanner.screensChecked(one(Fixtures.productGrid)))
        assertEquals(emptyList<DarkPatternSignal>(), DarkPatternScanner.scan(one(Fixtures.productGrid)))
    }

    // --- Nagging (#10) ---------------------------------------------------------------------------------------------------

    @Test
    fun `nagging needs the consent screen back after a recorded Reject`() {
        val nag = DarkPatternScanner.scan(leakySessions()).single { it.pattern == CcpaPattern.NAGGING }
        assertEquals(
            listOf("The consent popup came back 5.2 s after the recorded Reject: \"Wait! Get 10% off by allowing personalised offers\"."),
            nag.evidence,
        )
        assertEquals(listOf(AuditPlan.REJECT), nag.plans)
    }

    @Test
    fun `no nagging without a choice, too soon after it, after Accept, or for a screen that asks nothing`() {
        fun nagging(vararg events: ConsentEvent, plan: AuditPlan = AuditPlan.REJECT) =
            DarkPatternScanner.scan(listOf(session(plan, *events))).any { it.pattern == CcpaPattern.NAGGING }
        val screen = shown(leakyDialog)
        val rejected = ConsentEvent(8 * S, ConsentKind.REJECT, "Reject all")
        assertTrue(nagging(screen, rejected, ConsentEvent(13 * S, ConsentKind.REPROMPT, tree = nagPopup)))
        // No recorded choice: a screen seen twice is not "after the choice".
        assertTrue(!nagging(screen, ConsentEvent(13 * S, ConsentKind.REPROMPT, tree = nagPopup)))
        // Within a second of the tap the closing dialog can still be on screen.
        assertTrue(!nagging(screen, rejected, ConsentEvent((8.6 * S).toLong(), ConsentKind.REPROMPT, tree = nagPopup)))
        // After Accept: not counted as nagging.
        assertTrue(!nagging(screen, ConsentEvent(8 * S, ConsentKind.ACCEPT, "Accept"), ConsentEvent(13 * S, ConsentKind.REPROMPT, tree = nagPopup), plan = AuditPlan.ACCEPT))
        // Re-shown screen without any accept-type control does not ask again.
        val info = dialog(b(0, 0, 1000, 1000), text("Privacy settings saved", b(0, 0, 1000, 100)), text("Read our privacy policy", b(0, 200, 1000, 300)))
        assertTrue(!nagging(screen, rejected, ConsentEvent(13 * S, ConsentKind.REPROMPT, tree = info)))
        // A REPROMPT without a tree is not evidence of what was shown.
        assertTrue(!nagging(screen, rejected, ConsentEvent(13 * S, ConsentKind.REPROMPT)))
    }

    // --- Interface interference (#6) -------------------------------------------------------------------------------------

    @Test
    fun `a refuse button under half the accept area is a size signal, equal buttons are not`() {
        val small = composeButton("Reject", b(50, 1850, 250, 1950), b(80, 1870, 220, 1930), b(50, 1855, 250, 1945))
        val ii = DarkPatternScanner.scan(one(consentDialog(small, accept))).single()
        assertEquals(CcpaPattern.INTERFACE_INTERFERENCE, ii.pattern)
        assertEquals(listOf("\"Accept\" covers 3.4× the screen area of \"Reject\" (450×150 px vs 200×100 px)."), ii.evidence)
        assertEquals(emptyList<DarkPatternSignal>(), DarkPatternScanner.scan(one(consentDialog(reject, accept))))
    }

    @Test
    fun `refusal as plain text next to an accept button is a style signal`() {
        val link = text("No thanks", b(50, 1800, 480, 1950), clickable = true)
        val ii = DarkPatternScanner.scan(one(consentDialog(link, accept))).single()
        assertEquals(listOf("\"Accept\" is shown as a button; the way to refuse (\"No thanks\") is plain text."), ii.evidence)
    }

    @Test
    fun `tap cost compares the recorded refusal with the recorded acceptance`() {
        val screen = shown(consentDialog(reject, accept))
        val sessions = listOf(
            session(AuditPlan.REJECT, screen, ConsentEvent(5 * S, ConsentKind.SETTINGS, "More options"), ConsentEvent(6 * S, ConsentKind.SETTINGS, "Partners"), ConsentEvent(7 * S, ConsentKind.REJECT, "Reject all")),
            session(AuditPlan.ACCEPT, screen, ConsentEvent(5 * S, ConsentKind.ACCEPT, "Accept")),
        )
        assertEquals(
            listOf("Refusing took 3 taps (\"More options\" → \"Partners\" → \"Reject all\"); accepting took 1 (\"Accept\")."),
            DarkPatternScanner.scan(sessions).single().evidence,
        )
        // Same number of taps each way: no signal.
        val even = listOf(session(AuditPlan.REJECT, screen, ConsentEvent(5 * S, ConsentKind.REJECT, "Reject")), session(AuditPlan.ACCEPT, screen, ConsentEvent(5 * S, ConsentKind.ACCEPT, "Accept")))
        assertEquals(emptyList<DarkPatternSignal>(), DarkPatternScanner.scan(even))
    }

    @Test
    fun `without an Accept plan the accept tap count comes from the accept button on the first screen`() {
        val sessions = listOf(
            session(AuditPlan.REJECT, shown(consentDialog(reject, accept)), ConsentEvent(5 * S, ConsentKind.SETTINGS, "Manage"), ConsentEvent(7 * S, ConsentKind.REJECT, "Reject all")),
        )
        assertEquals(
            listOf("Refusing took 2 taps (\"Manage\" → \"Reject all\"); accepting took 1 (\"Accept\")."),
            DarkPatternScanner.scan(sessions).single().evidence,
        )
    }

    // --- Confirm shaming (#3) --------------------------------------------------------------------------------------------

    @Test
    fun `guilt wording on a decline option is confirm shaming in English, Hindi and Kannada`() {
        for (label in listOf(
            "No, I prefer paying more", "No thanks, I don't care about my savings", "I'll stay unprotected", "Accept Risk",
            "No, I'd rather pay full price", "मुझे बचत नहीं चाहिए", "नहीं, मुझे छूट नहीं चाहिए", "ನನಗೆ ಉಳಿತಾಯ ಬೇಡ",
        )) {
            val signals = DarkPatternScanner.scan(one(consentDialog(text(label, b(50, 1800, 480, 1950), clickable = true), accept)))
            val shaming = signals.singleOrNull { it.pattern == CcpaPattern.CONFIRM_SHAMING }
            assertEquals(label, listOf("The decline option reads \"$label\"."), shaming?.evidence)
        }
    }

    @Test
    fun `plain decline wording is not confirm shaming`() {
        for (label in listOf("No thanks", "Reject all", "Not now", "No", "Decline", "नहीं", "अस्वीकार करें", "ತಿರಸ್ಕರಿಸಿ", "Manage options")) {
            val signals = DarkPatternScanner.scan(one(consentDialog(composeButton(label, b(50, 1800, 480, 1950), b(60, 1850, 470, 1900), b(50, 1810, 480, 1940)), accept)))
            assertTrue(label, signals.none { it.pattern == CcpaPattern.CONFIRM_SHAMING })
        }
    }

    @Test
    fun `body text is not read as a decline option`() {
        val body = text("Some people say: I don't care about privacy. We do.", b(50, 200, 950, 400))
        assertTrue(DarkPatternScanner.scan(one(consentDialog(body, reject, accept))).isEmpty())
    }

    // --- False urgency (#1) ----------------------------------------------------------------------------------------------

    @Test
    fun `countdowns and scarcity wording on the consent screen are false urgency`() {
        for (line in listOf(
            "Offer ends in 09:59", "Only 2 left at this price!", "Limited time offer", "Hurry, deal expires in 4:59",
            "30 others are looking at this right now", "Last chance to save", "सीमित समय का ऑफ़र", "ऑफ़र 09:59 में खत्म", "केवल 3 बचे",
            "ಸೀಮಿತ ಸಮಯ",
        )) {
            val signals = DarkPatternScanner.scan(one(consentDialog(text(line, b(50, 200, 950, 300)), reject, accept)))
            val urgency = signals.singleOrNull { it.pattern == CcpaPattern.FALSE_URGENCY }
            assertEquals(line, listOf("Consent-screen text: \"$line\"."), urgency?.evidence)
        }
    }

    @Test
    fun `ordinary times and words are not false urgency`() {
        for (line in listOf("Last updated 10:30", "We keep data for a limited period", "You can change this at any time", "Only necessary cookies are always on")) {
            assertTrue(line, DarkPatternScanner.scan(one(consentDialog(text(line, b(50, 200, 950, 300)), reject, accept))).isEmpty())
        }
    }

    // --- Forced action (#4) ----------------------------------------------------------------------------------------------

    @Test
    fun `only an accept option with no refuse, manage or close is forced action`() {
        val signals = DarkPatternScanner.scan(one(consentDialog(accept)))
        assertEquals(listOf(CcpaPattern.FORCED_ACTION), patterns(signals))
        assertEquals(listOf("Only \"Accept\" was offered: no Reject, Manage or close option was found on the consent screen."), signals.single().evidence)
    }

    @Test
    fun `a close control or a manage option means not forced`() {
        val close = UiNode(className = "android.widget.ImageButton", contentDescription = "Close", isClickable = true, bounds = b(900, 0, 1000, 100))
        assertTrue(DarkPatternScanner.scan(one(consentDialog(close, accept))).none { it.pattern == CcpaPattern.FORCED_ACTION })
        val manage = composeButton("Manage", b(50, 1800, 480, 1950), b(60, 1850, 470, 1900), b(50, 1810, 480, 1940))
        assertTrue(DarkPatternScanner.scan(one(consentDialog(manage, accept))).none { it.pattern == CcpaPattern.FORCED_ACTION })
    }

    @Test
    fun `an AI-only reading of no refuse option is inferred forced action`() {
        val vlm = ScreenAudit(isConsentScreen = true, rejectOption = RejectOption.NONE, source = AuditSource.VLM)
        val signals = DarkPatternScanner.scan(listOf(session(AuditPlan.SILENT, ConsentEvent(2 * S, ConsentKind.SCREEN_APPEARED), audit = vlm)))
        assertEquals(listOf(CcpaPattern.FORCED_ACTION), patterns(signals))
        assertEquals(EvidenceType.INFERRED, signals.single().evidenceType)
        // When the tree was read, the tree decides: an observed NONE is already covered by the tree rule.
        val observed = vlm.copy(source = AuditSource.MERGED, observedFields = setOf("rejectOption"))
        assertTrue(DarkPatternScanner.scan(listOf(session(AuditPlan.SILENT, shown(consentDialog(reject, accept)), audit = observed))).isEmpty())
    }

    // --- Trick question (#11) --------------------------------------------------------------------------------------------

    @Test
    fun `double negatives on the consent screen are a trick question`() {
        for (line in listOf("Uncheck to not receive offers", "Untick this box if you don't want updates", "Do not opt out of personalised ads", "I do not wish to not receive deals")) {
            val toggle = UiNode(className = "android.widget.CheckBox", text = line, isCheckable = true, isClickable = true, bounds = b(50, 300, 950, 400))
            val trick = DarkPatternScanner.scan(one(consentDialog(toggle, reject, accept))).singleOrNull { it.pattern == CcpaPattern.TRICK_QUESTION }
            assertEquals(line, listOf("Double-negative wording: \"$line\"."), trick?.evidence)
        }
        for (line in listOf("Share usage data to improve offers", "You can opt out at any time", "Untick to stop offers")) {
            val toggle = UiNode(className = "android.widget.CheckBox", text = line, isCheckable = true, isClickable = true, bounds = b(50, 300, 950, 400))
            assertTrue(line, DarkPatternScanner.scan(one(consentDialog(toggle, reject, accept))).none { it.pattern == CcpaPattern.TRICK_QUESTION })
        }
    }

    // --- AI readings ------------------------------------------------------------------------------------------------------

    @Test
    fun `AI readings add inferred signals only where no observed one exists`() {
        val vlm = ScreenAudit(
            isConsentScreen = true, rejectOption = RejectOption.HIDDEN, source = AuditSource.MERGED, observedFields = setOf("rejectOption"),
            darkPatterns = listOf("Hidden reject option", "Confirmshaming on decline", "pre-ticked checkbox", "nagging pop-up"),
        )
        val sessions = leakySessions(nag = false).map { it.copy(screenAudit = vlm) }
        val signals = DarkPatternScanner.scan(sessions)
        assertEquals(listOf(CcpaPattern.CONFIRM_SHAMING, CcpaPattern.INTERFACE_INTERFERENCE, CcpaPattern.NAGGING), patterns(signals))
        assertEquals(EvidenceType.OBSERVED, signals.single { it.pattern == CcpaPattern.INTERFACE_INTERFERENCE }.evidenceType)
        val shaming = signals.single { it.pattern == CcpaPattern.CONFIRM_SHAMING }
        assertEquals(EvidenceType.INFERRED, shaming.evidenceType)
        assertEquals(listOf("On-device AI reading of the consent screen: \"Confirmshaming on decline\". Not confirmed from the screen tree."), shaming.evidence)
        assertEquals(EvidenceType.INFERRED, signals.single { it.pattern == CcpaPattern.NAGGING }.evidenceType)
    }

    // --- Neutral wording and score isolation -----------------------------------------------------------------------------

    @Test
    fun `every string the scanner writes avoids verdict words`() {
        val vlm = ScreenAudit(isConsentScreen = true, rejectOption = RejectOption.NONE, source = AuditSource.VLM, darkPatterns = listOf("trick question", "false urgency timer"))
        val all = DarkPatternScanner.scan(leakySessions()) + DarkPatternScanner.scan(one(consentDialog(accept))) +
            DarkPatternScanner.scan(listOf(session(AuditPlan.SILENT, ConsentEvent(2 * S, ConsentKind.SCREEN_APPEARED), audit = vlm)))
        val texts = all.flatMap { it.evidence + it.source + it.pattern.title + it.pattern.definition } + CcpaPattern.entries.map { it.definition }
        texts.forEach { assertNull(it, VerdictWords.find(it)) }
    }

    @Test
    fun `scanning never changes the rules engine result`() {
        val engine = RulesEngine(TrackerCatalog.loadDefault())
        val sessions = leakySessions()
        val before = engine.evaluate(sessions)
        DarkPatternScanner.scan(sessions)
        assertEquals(before, engine.evaluate(sessions))
    }

    @Test
    fun `six of the thirteen Annexure patterns are checkable at the consent moment`() {
        assertEquals(13, CcpaPattern.entries.size)
        assertEquals((1..13).toList(), CcpaPattern.entries.map { it.number })
        assertEquals(
            listOf(CcpaPattern.FALSE_URGENCY, CcpaPattern.CONFIRM_SHAMING, CcpaPattern.FORCED_ACTION, CcpaPattern.INTERFACE_INTERFERENCE, CcpaPattern.NAGGING, CcpaPattern.TRICK_QUESTION),
            CcpaPattern.CHECKABLE,
        )
    }
}
