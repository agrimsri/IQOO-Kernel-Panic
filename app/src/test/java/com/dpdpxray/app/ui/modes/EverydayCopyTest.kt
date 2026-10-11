package com.dpdpxray.app.ui.modes

import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.update.AppVersion
import com.dpdpxray.core.update.UpdateKind
import com.dpdpxray.core.update.UpdatedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- Update Watch on the everyday Home ---------------------------------------------------------------------------

    private fun updated(label: String, kind: UpdateKind, old: AppVersion?, new: AppVersion?) =
        UpdatedApp("com.example.${label.lowercase()}", label, kind, old, new, "audit-$label", 1_000L, 21, ScoreBand.AT_RISK)

    @Test fun `the update card is hidden until a checked app has a new version`() {
        assertNull(EverydayUpdates.card(emptyList()))
        val card = EverydayUpdates.card(listOf(updated("LeakyShop", UpdateKind.UPDATED, AppVersion("1.0", 1), AppVersion("1.1", 2))))!!
        assertEquals("An app you checked has updated", card.title.en)
        assertEquals(listOf("LeakyShop 1.0 → 1.1"), card.rows.map { it.line.en })
        assertEquals(listOf("com.example.leakyshop"), card.rows.map { it.packageName })
        assertEquals(0, card.more)
        assertEquals("Check again", EverydayCopy.checkAgain.en)
    }

    @Test fun `several updated apps get a plural title, at most three rows in the data source's order, and a count of the rest`() {
        val five = (1..5).map { updated("App$it", UpdateKind.UPDATED, AppVersion("1.0", 1), AppVersion("2.0", 2)) }
        val card = EverydayUpdates.card(five)!!
        assertEquals("5 apps you checked have updated", card.title.en)
        assertTrue(card.title.hi, hasHindi(card.title) && card.title.hi.contains("5"))
        assertEquals(EverydayUpdates.MAX_ROWS, card.rows.size)
        assertEquals(five.take(EverydayUpdates.MAX_ROWS).map { it.packageName }, card.rows.map { it.packageName })
        assertEquals(2, card.more)
        assertEquals(0, EverydayUpdates.card(five.take(3))!!.more)
    }

    @Test fun `each update line says only what Android reports, in English and Hindi`() {
        val up = EverydayCopy.updatedLine(updated("Shop", UpdateKind.UPDATED, AppVersion("1.0", 1), AppVersion("1.1", 2)))
        assertEquals(Bi("Shop 1.0 → 1.1", "Shop 1.0 → 1.1"), up)
        val down = EverydayCopy.updatedLine(updated("Shop", UpdateKind.DOWNGRADED, AppVersion("1.1", 2), AppVersion("1.0", 1)))
        assertEquals("Shop 1.1 → 1.0 (older version)", down.en)
        assertTrue(down.hi, hasHindi(down) && down.hi.startsWith("Shop 1.1 → 1.0"))
        val unknown = EverydayCopy.updatedLine(updated("Shop", UpdateKind.VERSION_NOT_RECORDED, null, AppVersion("1.1", 2)))
        assertEquals("Shop · now 1.1", unknown.en)
        assertTrue(unknown.hi, hasHindi(unknown) && unknown.hi.endsWith("1.1"))
    }

    @Test fun `the update card's words go through the everyday wording checks`() {
        listOf(EverydayCopy.updatedTitle(1), EverydayCopy.updatedTitle(2), EverydayCopy.updatedNote, EverydayCopy.checkAgain)
            .forEach { assertTrue(it.en, it in home) }
    }
}
