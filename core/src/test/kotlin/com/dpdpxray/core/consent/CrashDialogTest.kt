package com.dpdpxray.core.consent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashDialogTest {
    @Test fun `Android's stopped and not-responding dialogs for the audited app are recognised`() {
        assertEquals(CrashDialog.Kind.STOPPED, CrashDialog.detect(listOf("HackTracker keeps stopping", "App info", "Close app"), "HackTracker"))
        assertEquals(CrashDialog.Kind.STOPPED, CrashDialog.detect(listOf("Unfortunately, HackTracker has stopped."), "HackTracker"))
        assertEquals(CrashDialog.Kind.NOT_RESPONDING, CrashDialog.detect(listOf("HackTracker isn’t responding", "Close app", "Wait"), "HackTracker"))
        assertEquals(CrashDialog.Kind.STOPPED, CrashDialog.detect(listOf("HackTracker बार-बार बंद हो रहा है"), "HackTracker"))
    }

    @Test fun `another app's crash, or a screen that only mentions stopping, is not the audited app's crash`() {
        assertNull(CrashDialog.detect(listOf("Chrome keeps stopping", "Close app"), "HackTracker"))
        assertNull(CrashDialog.detect(listOf("HackTracker", "Your timer has stopped? Start again"), "HackTracker"))
        assertNull(CrashDialog.detect(listOf("Anything keeps stopping"), "  "))
    }
}
