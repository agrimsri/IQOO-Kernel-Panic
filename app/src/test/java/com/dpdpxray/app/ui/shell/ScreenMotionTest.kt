package com.dpdpxray.app.ui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMotionTest {
    @Test fun `tab to tab is a fade-through, anything else slides`() {
        assertTrue(ScreenMotion.isTabSwitch("home", AppTabs.REPORTS))
        assertTrue(ScreenMotion.isTabSwitch("apkguard", "pick?q={q}"))
        assertFalse(ScreenMotion.isTabSwitch("home", "results/{id}"))
        assertFalse(ScreenMotion.isTabSwitch("results/{id}", "finding/{id}/{check}"))
        assertFalse(ScreenMotion.isTabSwitch(null, "home"))
    }

    @Test fun `no screen moves when animations are off`() {
        listOf(true, false).forEach { tab ->
            assertEquals(EnterTransition.None, ScreenMotion.enter(true, tab))
            assertEquals(ExitTransition.None, ScreenMotion.exit(true, tab))
            assertEquals(EnterTransition.None, ScreenMotion.popEnter(true, tab))
            assertEquals(ExitTransition.None, ScreenMotion.popExit(true, tab))
            assertNotEquals(EnterTransition.None, ScreenMotion.enter(false, tab))
        }
    }
}
