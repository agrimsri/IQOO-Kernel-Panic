package com.dpdpxray.app.quarantine

import com.dpdpxray.core.quarantine.AddressScope
import com.dpdpxray.core.quarantine.AttemptProtocol
import com.dpdpxray.core.quarantine.PacketHeader
import com.dpdpxray.core.quarantine.QuarantineStopReason
import com.dpdpxray.core.quarantine.SensitiveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineBusTest {
    private fun syn(ip: String) = PacketHeader(4, ip, AddressScope.PUBLIC, 6, AttemptProtocol.TCP_SYN, 443, null, false, false)

    @Test
    fun `records belong to the open run only and a late packet from an older run is ignored`() {
        val old = QuarantineBus.open("com.traceveil.riskdemo", "Risk Demo")
        QuarantineBus.close(old)
        val run = QuarantineBus.open("com.traceveil.riskdemo", "Risk Demo")
        QuarantineBus.lookup(old, 1, "late.example", 1, QuarantineNet.RESOLVER_V4)
        QuarantineBus.dropped(old, 2, syn("203.0.113.1"))
        QuarantineBus.lookup(run, 3, "panel.example", 1, QuarantineNet.RESOLVER_V4)
        QuarantineBus.dropped(run, 4, syn("203.0.113.9"))
        val o = QuarantineBus.close(run)!!
        assertEquals(listOf("panel.example"), o.domains.map { it.hostname })
        assertEquals(listOf("203.0.113.9"), o.blocked.map { it.address })
        assertNull(QuarantineBus.close(run))
    }

    @Test
    fun `the watched package is set only while a run records`() {
        val run = QuarantineBus.open("com.traceveil.riskdemo", "Risk Demo")
        assertEquals("com.traceveil.riskdemo", QuarantineBus.watchedPackage)
        QuarantineBus.close(run)
        assertNull(QuarantineBus.watchedPackage)
        assertFalse(QuarantineBus.permissionDialog(1, "com.google.android.permissioncontroller", "Allow Risk Demo to send and view SMS messages?"))
    }

    @Test
    fun `dialogs are attributed by the run's label and settings screens are recorded`() {
        val run = QuarantineBus.open("com.traceveil.riskdemo", "Risk Demo")
        assertTrue(QuarantineBus.permissionDialog(1, "com.google.android.permissioncontroller", "Allow Risk Demo to send and view SMS messages?"))
        assertTrue(QuarantineBus.permissionDialog(2, "com.google.android.permissioncontroller", "Allow Maps to access this device's location?"))
        assertTrue(QuarantineBus.settingsScreen(3, SensitiveSettings.ACCESSIBILITY, "Accessibility"))
        QuarantineBus.targetOnScreen(4)
        val o = QuarantineBus.close(run)!!
        assertEquals(listOf(true, false), o.permissionDialogs.map { it.namesTarget })
        assertEquals(listOf(SensitiveSettings.ACCESSIBILITY), o.settingsScreens.map { it.kind })
        assertEquals(4L, o.targetOnScreenNanos)
    }

    @Test
    fun `tunnel state follows its own run and keeps the first end reason`() {
        val run = QuarantineBus.open("com.traceveil.riskdemo", "Risk Demo")
        assertEquals(QuarantineTunnel.Starting(run), QuarantineBus.tunnel.value)
        QuarantineBus.tunnelRunning(run + 100, "other")
        assertEquals(QuarantineTunnel.Starting(run), QuarantineBus.tunnel.value)
        QuarantineBus.tunnelRunning(run, "com.traceveil.riskdemo")
        assertEquals(QuarantineTunnel.Running(run, "com.traceveil.riskdemo"), QuarantineBus.tunnel.value)
        assertTrue(QuarantineBus.tunnelEnded(run, QuarantineStopReason.USER))
        assertFalse(QuarantineBus.tunnelEnded(run, QuarantineStopReason.TUNNEL_CLOSED)) // the loop's later "closed" is ignored
        assertEquals(QuarantineTunnel.Ended(run, QuarantineStopReason.USER), QuarantineBus.tunnel.value)
        QuarantineBus.close(run)
    }

    @Test
    fun `publish copies the current observations for the live screen`() {
        val run = QuarantineBus.open("com.traceveil.riskdemo", "Risk Demo")
        QuarantineBus.lookup(run, 1, "a.example", 1, QuarantineNet.RESOLVER_V4)
        assertTrue(QuarantineBus.live.value.domains.isEmpty())
        QuarantineBus.publish()
        assertEquals(1, QuarantineBus.live.value.domains.size)
        QuarantineBus.close(run)
    }
}
