package com.dpdpxray.app.consent

import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.app.audit.AuditPlanCoverage
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConsentBusSessionTest {
    @get:Rule val temp = TemporaryFolder()
    @After fun disarm() = ConsentBus.disarm()

    @Test fun `current session accepts its screenshot event`() {
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        val event = ConsentEvent(1000, ConsentKind.SCREEN_APPEARED)
        assertTrue(ConsentBus.emitForSession(ConsentBus.generation, event))
        assertEquals(listOf(event), ConsentBus.events.value)
    }

    @Test fun `rearming same app advances session generation`() {
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        val old = ConsentBus.generation
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        assertNotEquals(old, ConsentBus.generation)
    }

    @Test fun `late screenshot cannot contaminate a new plan for the same app`() {
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        val old = ConsentBus.generation
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        assertFalse(ConsentBus.emitForSession(old, ConsentEvent(1000, ConsentKind.SCREEN_APPEARED)))
        assertTrue(ConsentBus.events.value.isEmpty())
    }

    @Test fun `disarmed audit does not accept delayed screenshot completion`() {
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        val current = ConsentBus.generation
        ConsentBus.disarm()
        assertFalse(ConsentBus.emitForSession(current, ConsentEvent(1000, ConsentKind.SCREEN_APPEARED)))
    }

    @Test fun `dialog disappearance is not proof of the requested Reject choice`() {
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        ConsentBus.expectedChoice = ConsentKind.REJECT
        ConsentBus.emit(ConsentEvent(1, ConsentKind.SCREEN_APPEARED))
        ConsentBus.onConsentDialogClosed(2)
        assertFalse(ConsentBus.choiceMade)
        assertEquals(2L, ConsentBus.unconfirmedDialogClosedAtNanos)
        val session = AuditSession("test", "com.leakyshop.demo", "LeakyShop", AuditPlan.REJECT,
            netEvents = listOf(NetEvent(3, "graph.facebook.com")), consentEvents = ConsentBus.events.value)
        assertFalse(AuditPlanCoverage.completedPlans(listOf(session)).contains(AuditPlan.REJECT))
        assertFalse(PhaseAssigner.assign(session.netEvents, session.consentEvents).any { it.phase == Phase.AFTER_REJECT })
    }

    @Test fun `an expected Accept is not evidence when the dialog closes`() {
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        ConsentBus.expectedChoice = ConsentKind.ACCEPT
        ConsentBus.onConsentDialogClosed(5)
        assertFalse(ConsentBus.choiceMade)
    }

    @Test fun `actual Accept tap in a Reject plan is preserved and cannot complete that plan`() {
        ConsentBus.arm("com.leakyshop.demo", temp.newFolder())
        ConsentBus.expectedChoice = ConsentKind.REJECT
        ConsentBus.emit(ConsentEvent(2, ConsentKind.ACCEPT, "Accept"))
        ConsentBus.onConsentDialogClosed(3)
        assertEquals(listOf(ConsentKind.ACCEPT), ConsentBus.events.value.map { it.kind })
        assertNull(ConsentBus.unconfirmedDialogClosedAtNanos)
        val session = AuditSession("test", "com.leakyshop.demo", "LeakyShop", AuditPlan.REJECT, consentEvents = ConsentBus.events.value)
        assertFalse(AuditPlanCoverage.completedPlans(listOf(session)).contains(AuditPlan.REJECT))
    }
}
