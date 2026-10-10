package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCopyTest {
    @Test fun `the start button names the chosen length`() {
        assertEquals("Start listening (15 minutes)", WakeCopy.startLabel(WakeDuration.MIN_15))
        assertEquals("Start listening (1 hour)", WakeCopy.startLabel(WakeDuration.HOUR_1))
        assertEquals("Start listening (until I turn it off)", WakeCopy.startLabel(WakeDuration.UNTIL_OFF))
    }

    @Test fun `the notification never promises a timer it does not have`() {
        assertTrue(WakeCopy.notificationText(limited = true).contains("timer"))
        assertTrue(WakeCopy.notificationText(limited = false).contains("until you stop it"))
        listOf(true, false).forEach {
            val text = WakeCopy.notificationText(it)
            assertTrue(text.contains("never saved or sent"))
            assertFalse(text.contains("15 minutes"))
        }
        assertTrue(WakeCopy.notificationTitle(paused = false).contains("tap to stop"))
        assertTrue(WakeCopy.notificationTitle(paused = true).contains("tap to stop"))
    }

    @Test fun `settings copy explains the default length and every way it stops`() {
        assertTrue(WakeCopy.DURATION_NOTE.contains("charging"))
        assertTrue(WakeCopy.DURATION_NOTE.contains("1 hour"))
        assertTrue(WakeCopy.DURATION_NOTE.contains("15% battery"))
        assertFalse(WakeCopy.INTRO.contains("15 minutes"))
        WakeSensitivity.entries.forEach { assertTrue(WakeCopy.sensitivityNote(it).startsWith(it.label)) }
        assertTrue(WakeCopy.EVENTS_NOTE.contains("no audio or words"))
    }
}
