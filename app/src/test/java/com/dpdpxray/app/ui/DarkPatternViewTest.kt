package com.dpdpxray.app.ui

import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.app.ui.screens.DarkPatternView
import com.dpdpxray.core.darkpattern.CcpaPattern
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the Results card and the everyday line show for an audit. The in-app sample is synthetic data. */
class DarkPatternViewTest {
    @Test
    fun `the sample audit shows its Manage-only refusal as interface interference and keeps its score`() {
        val sessions = SampleAudit.sessions(1_791_590_915_883L)
        val before = RulesEngine(TrackerCatalog.loadDefault()).evaluate(sessions)
        val view = DarkPatternView.of(sessions)
        assertEquals(listOf(CcpaPattern.INTERFACE_INTERFERENCE), view.signals.map { it.pattern })
        assertEquals(EvidenceType.OBSERVED, view.signals.single().evidenceType)
        assertEquals("1 of 6 checkable patterns seen at the consent moment", view.summary)
        assertEquals(
            "Consent popup design: 1 signal resembling India's CCPA dark-pattern list (interface interference). Not part of the score.",
            view.everydayLine,
        )
        assertEquals(before, RulesEngine(TrackerCatalog.loadDefault()).evaluate(sessions))
    }

    @Test
    fun `an audit without a consent-screen tree shows no everyday line`() {
        val view = DarkPatternView.of(emptyList())
        assertEquals(0, view.screensChecked)
        assertEquals("Not checked: no consent-screen tree was captured", view.summary)
        assertNull(view.everydayLine)
    }
}
