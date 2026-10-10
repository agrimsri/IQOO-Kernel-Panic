package com.dpdpxray.app.consent

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.UiNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** "Shown again after the choice" (REPROMPT): when the watcher records it, and that the first-screen path is untouched. */
class ConsentReappearanceTest {
    @get:Rule val temp = TemporaryFolder()
    @After fun disarm() = ConsentBus.disarm()

    private val S = 1_000_000_000L
    private val tree = UiNode(text = "Wait! Get 10% off by allowing personalised offers")

    @Test fun `a consent screen that comes back after the recorded choice is recorded`() {
        assertTrue(ConsentReappearance.shouldRecord(isConsent = true, wasVisible = false, screenSeen = true, choiceMade = true, recordedSoFar = 0))
    }

    @Test fun `nothing is recorded before the choice, while the screen stays up, or for other screens`() {
        // Before any choice: the first-screen path records it (or it is the same screen), never as REPROMPT.
        assertFalse(ConsentReappearance.shouldRecord(isConsent = true, wasVisible = false, screenSeen = false, choiceMade = false, recordedSoFar = 0))
        assertFalse(ConsentReappearance.shouldRecord(isConsent = true, wasVisible = false, screenSeen = true, choiceMade = false, recordedSoFar = 0))
        // The dialog is still on screen (for example closing after the tap): not shown again.
        assertFalse(ConsentReappearance.shouldRecord(isConsent = true, wasVisible = true, screenSeen = true, choiceMade = true, recordedSoFar = 0))
        // Not a consent screen.
        assertFalse(ConsentReappearance.shouldRecord(isConsent = false, wasVisible = false, screenSeen = true, choiceMade = true, recordedSoFar = 0))
        // A screen bouncing in and out is recorded a few times at most.
        assertFalse(ConsentReappearance.shouldRecord(isConsent = true, wasVisible = false, screenSeen = true, choiceMade = true, recordedSoFar = ConsentReappearance.MAX_PER_PLAN))
    }

    @Test fun `the bus keeps the first screen and the choice, and counts the screens shown again`() {
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        val g = ConsentBus.generation
        val first = ConsentEvent(2 * S, ConsentKind.SCREEN_APPEARED, tree = UiNode(text = "We value your privacy"))
        ConsentBus.emitForSession(g, first)
        ConsentBus.emit(ConsentEvent(8 * S, ConsentKind.REJECT, "Reject all"))
        assertEquals(0, ConsentBus.repromptCount)
        assertTrue(ConsentBus.emitForSession(g, ConsentReappearance.event(13 * S, tree)))
        // The same kind within 1.5 s is a duplicate, as for every consent event.
        ConsentBus.emitForSession(g, ConsentReappearance.event(13 * S + 500_000_000L, tree))
        assertEquals(1, ConsentBus.repromptCount)

        val events = ConsentBus.events.value
        assertEquals(ConsentKind.REPROMPT, events.last().kind)
        assertEquals(tree, events.last().tree)
        val session = AuditSession(id = "b", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.REJECT, consentEvents = events)
        assertEquals(ConsentKind.REJECT, session.consentChoice?.kind)
        assertTrue(session.consentScreenSeen)
        assertEquals(listOf(first), events.filter { it.kind == ConsentKind.SCREEN_APPEARED })
    }

    @Test fun `rearming for the next plan starts the count again`() {
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        ConsentBus.emitForSession(ConsentBus.generation, ConsentReappearance.event(13 * S, tree))
        assertEquals(1, ConsentBus.repromptCount)
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        assertEquals(0, ConsentBus.repromptCount)
    }
}
