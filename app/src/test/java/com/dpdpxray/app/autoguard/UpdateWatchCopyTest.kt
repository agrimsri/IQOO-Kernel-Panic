package com.dpdpxray.app.autoguard

import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.update.AppVersion
import com.dpdpxray.core.update.UpdateKind
import com.dpdpxray.core.update.UpdatedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateWatchCopyTest {
    private fun item(kind: UpdateKind, old: AppVersion?, new: AppVersion?, score: Int? = 8, band: ScoreBand = ScoreBand.AT_RISK) =
        UpdatedApp("com.leakyshop.demo", "LeakyShop", kind, old, new, "audit-1", 1_000, score, band)

    @Test
    fun `card title counts the apps`() {
        assertEquals("Updated since your last check (1)", UpdateWatchCopy.cardTitle(1))
        assertEquals("Updated since your last check (3)", UpdateWatchCopy.cardTitle(3))
    }

    @Test
    fun `row version text for each kind`() {
        assertEquals("1.0 → 1.1", UpdateWatchCopy.versionLine(item(UpdateKind.UPDATED, AppVersion("1.0", 1), AppVersion("1.1", 2))))
        assertEquals("1.1 → 1.0 (older version)", UpdateWatchCopy.versionLine(item(UpdateKind.DOWNGRADED, AppVersion("1.1", 2), AppVersion("1.0", 1))))
        assertEquals("Now 1.1 · version at your check not recorded", UpdateWatchCopy.versionLine(item(UpdateKind.VERSION_NOT_RECORDED, null, AppVersion("1.1", 2))))
    }

    @Test
    fun `row last-check text and score chip`() {
        assertEquals("Last check: 8 · At risk", UpdateWatchCopy.lastCheckLine(item(UpdateKind.UPDATED, AppVersion("1.0", 1), AppVersion("1.1", 2))))
        assertEquals("8", UpdateWatchCopy.scoreChip(item(UpdateKind.UPDATED, AppVersion("1.0", 1), AppVersion("1.1", 2))))
        val inconclusive = item(UpdateKind.UPDATED, AppVersion("1.0", 1), AppVersion("1.1", 2), score = null, band = ScoreBand.INCONCLUSIVE)
        assertEquals("Last check: Inconclusive", UpdateWatchCopy.lastCheckLine(inconclusive))
        assertEquals("?", UpdateWatchCopy.scoreChip(inconclusive))
    }

    @Test
    fun `settings switch has English and Hindi copy that promises no automatic check`() {
        assertEquals("Alert when a checked app updates", UpdateWatchCopy.switchTitle.pick(GuideLang.EN))
        assertTrue(UpdateWatchCopy.switchTitle.pick(GuideLang.HI).isNotBlank())
        assertTrue(UpdateWatchCopy.switchDetail.pick(GuideLang.EN).contains("nothing starts until you tap Start"))
        assertTrue(UpdateWatchCopy.switchDetail.pick(GuideLang.HI).contains("Start"))
    }
}
