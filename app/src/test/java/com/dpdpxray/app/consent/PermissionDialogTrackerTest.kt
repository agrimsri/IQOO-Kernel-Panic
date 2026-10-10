package com.dpdpxray.app.consent

import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Android permission dialogs become one event per distinct dialog, never a consent screen or choice. */
class PermissionDialogTrackerTest {
    @get:Rule val temp = TemporaryFolder()
    @After fun disarm() = ConsentBus.disarm()

    private val location = "Allow LeakyShop to access this device's location?"
    private val camera = "Allow LeakyShop to take pictures and record video?"

    @Test fun `a dialog re-read while it stays on screen is one event`() {
        val tracker = PermissionDialogTracker()
        assertEquals(ConsentEvent(10, ConsentKind.PERMISSION_DIALOG, location), tracker.onDialog(10, location))
        assertNull(tracker.onDialog(20, location))
    }

    @Test fun `the same dialog shown again after another window counts again`() {
        val tracker = PermissionDialogTracker()
        tracker.onDialog(10, location)
        tracker.onOtherWindow()
        assertEquals(ConsentEvent(30, ConsentKind.PERMISSION_DIALOG, location), tracker.onDialog(30, location))
    }

    @Test fun `a second permission right after the first is its own event, and empty text is ignored`() {
        val tracker = PermissionDialogTracker()
        tracker.onDialog(10, location)
        assertEquals(ConsentEvent(11, ConsentKind.PERMISSION_DIALOG, camera), tracker.onDialog(11, camera))
        assertNull(tracker.onDialog(12, null))
    }

    @Test fun `the bus keeps two different dialogs a moment apart but drops a duplicate of one`() {
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        ConsentBus.emit(ConsentEvent(1_000_000_000, ConsentKind.PERMISSION_DIALOG, location))
        ConsentBus.emit(ConsentEvent(1_400_000_000, ConsentKind.PERMISSION_DIALOG, camera))
        ConsentBus.emit(ConsentEvent(1_600_000_000, ConsentKind.PERMISSION_DIALOG, location))
        assertEquals(listOf(location, camera), ConsentBus.events.value.map { it.nodeText })
        assertFalse("a permission dialog is not a consent screen", ConsentBus.screenSeen)
        assertFalse("tapping Allow in Android's dialog is not a consent choice", ConsentBus.choiceMade)
    }
}
