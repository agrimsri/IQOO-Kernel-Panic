package com.dpdpxray.app.ui.screens

import com.dpdpxray.core.model.ScoreBand
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportsCopyTest {
    @Test fun `the line counts each band in band order and leaves out bands with none`() {
        val bands = listOf(ScoreBand.AT_RISK, ScoreBand.READY, ScoreBand.AT_RISK, ScoreBand.INCONCLUSIVE)
        assertEquals("Saved on this phone, newest first · 1 Ready · 2 At risk · 1 Inconclusive", ReportsCopy.summary(bands))
    }

    @Test fun `with no checks only the first part is left`() {
        assertEquals("Saved on this phone, newest first", ReportsCopy.summary(emptyList()))
    }
}
