package com.dpdpxray.app.ui.modes

import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.guide.Bi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EverydayCopyTest {
    private fun hasHindi(b: Bi) = b.hi.any { it in 'ऀ'..'ॿ' }
    private val home = EverydayCopy.all()
    private val sheet = EverydaySheetCopy.all()

    @Test fun `every everyday string is written in English and Hindi`() {
        (home + sheet).forEach { assertTrue(it.en, it.en.isNotBlank() && hasHindi(it)) }
    }

    @Test fun `everyday copy speaks of capabilities, never verdicts, and nothing India-only`() {
        val en = (home + sheet).joinToString(" ") { it.en }.lowercase()
        listOf("safe", "malware", "virus", "malicious", "fraud", "illegal", "compliant", "certified", "dpdp", "₹", "rupee", "lakh", "crore")
            .forEach { assertFalse(it, en.contains(it)) }
        val hi = (home + sheet).joinToString(" ") { it.hi }
        listOf("सुरक्षित", "मैलवेयर", "वायरस", "₹", "रुपये").forEach { assertFalse(it, hi.contains(it)) }
    }

    @Test fun `everyday Home has no developer jargon`() {
        val en = home.joinToString(" ") { it.en }.lowercase()
        listOf(Regex("\\bplans?\\b"), Regex("shizuku"), Regex("\\bc[0-9]\\b"), Regex("\\baudits?\\b"), Regex("\\bci\\b"), Regex("\\bsdk"))
            .forEach { assertFalse(it.pattern, it.containsMatchIn(en)) }
    }

    @Test fun `the brief's card titles are used as written`() {
        assertEquals("Apps promise.\nTraceVeil checks.", EverydayCopy.heroTitle.en)
        assertEquals(
            listOf("Check a file someone sent me", "Check my apps", "New-app alerts", "Does this app respect my 'No'?", "Why you can trust TraceVeil"),
            listOf(EverydayCopy.file, EverydayCopy.apps, EverydayCopy.alerts, EverydayCopy.sayNo, EverydayCopy.trust).map { it.title.en },
        )
        assertEquals("Mode: Everyday", ModeCopy.title(Audience.USER).en)
        assertEquals("Mode: Developer", ModeCopy.title(Audience.DEVELOPER).en)
    }

    @Test fun `alert status follows the notification permission and the consent watcher`() {
        assertEquals(AlertStatus.BLOCKED, EverydayCopy.alertStatus(notificationsAllowed = false, watcherOn = true))
        assertEquals(AlertStatus.BLOCKED, EverydayCopy.alertStatus(notificationsAllowed = false, watcherOn = false))
        assertEquals(AlertStatus.ON, EverydayCopy.alertStatus(notificationsAllowed = true, watcherOn = true))
        assertEquals(AlertStatus.WHILE_OPEN, EverydayCopy.alertStatus(notificationsAllowed = true, watcherOn = false))
        assertEquals("On", EverydayCopy.alertLabel(AlertStatus.ON, readAloud = false).en)
        assertEquals("On · read aloud", EverydayCopy.alertLabel(AlertStatus.ON, readAloud = true).en)
        assertEquals(EverydayCopy.alertLabel(AlertStatus.BLOCKED, false), EverydayCopy.alertLabel(AlertStatus.BLOCKED, true))
    }

    @Test fun `the sheet's spoken guide never names the app`() {
        val g = EverydaySheetCopy.guide("Secret Bank")
        assertTrue(g.body.en.contains("Secret Bank") && g.body.hi.contains("Secret Bank"))
        assertFalse(g.spoken.en.contains("Secret Bank") || g.spoken.hi.contains("Secret Bank"))
        assertTrue(g.spoken.en.length <= 160)
    }

    @Test fun `the Quick check copy matches what the run does`() {
        // Everyday Quick is one Reject run with normal 15-second observation that keeps the app's data.
        assertTrue(EverydaySheetCopy.quickDetail.en.contains("Reject"))
        assertTrue(EverydaySheetCopy.quickDetail.en.contains("15 seconds"))
        assertTrue(EverydaySheetCopy.quickDetail.en.contains("keeps its data"))
    }
}
