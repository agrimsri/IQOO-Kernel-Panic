package com.dpdpxray.app.consent

import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
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
}
