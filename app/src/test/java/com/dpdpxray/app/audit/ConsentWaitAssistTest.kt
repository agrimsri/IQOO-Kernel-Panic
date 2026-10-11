package com.dpdpxray.app.audit

import com.dpdpxray.core.consent.BlockerKind
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentWaitAssistTest {
    private val consent = ConsentEvent(1L, ConsentKind.SCREEN_APPEARED)

    /** A scripted app: [blockerUntilMs] of blocker, then the consent screen shows at [consentAtMs] (null = never). */
    private class Script(val blockerUntilMs: Long, val consentAtMs: Long?, val skipWorks: Boolean = false, val blocker: BlockerKind = BlockerKind.SIGN_IN) {
        var now = 0L
        var skipTaps = 0
        var handoffs = 0
        var resumes = 0
        val skipClears get() = skipWorks && skipTaps > 0
    }

    private fun assist(s: Script, interactive: Boolean = true, event: ConsentEvent = consent, skip: (() -> String?)? = null) = ConsentWaitAssist(
        probe = { event.takeIf { s.consentAtMs != null && s.now >= s.consentAtMs && !(blockedNow(s)) } },
        detectBlocker = { s.blocker.takeIf { blockedNow(s) } },
        trySkip = { skip?.invoke()?.also { s.skipTaps++ } ?: if (skip != null) { s.skipTaps++; null } else if (s.skipWorks) { s.skipTaps++; "Skip" } else { s.skipTaps++; null } },
        onHandoff = { s.handoffs++ },
        onResume = { s.resumes++ },
        clockMs = { s.now },
        pause = { s.now += it },
        interactive = interactive,
    )

    private fun blockedNow(s: Script) = if (s.skipClears) false else s.now < s.blockerUntilMs

    @Test fun `no blocker behaves like the plain wait`() = runBlocking {
        val s = Script(blockerUntilMs = 0, consentAtMs = 3_000)
        val o = assist(s).await(20_000)
        assertNotNull(o.consent)
        assertNull(o.blocker)
        assertEquals(0, s.skipTaps)
        assertFalse(o.handedOff)
    }

    @Test fun `no blocker and no consent times out at the base timeout`() = runBlocking {
        val s = Script(blockerUntilMs = 0, consentAtMs = null)
        val o = assist(s).await(20_000)
        assertNull(o.consent)
        assertTrue("waited ${s.now}", s.now in 20_000..20_600)
    }

    @Test fun `a skip button gets past the blocker and consent is found`() = runBlocking {
        val s = Script(blockerUntilMs = 60_000, consentAtMs = 4_000, skipWorks = true)
        val o = assist(s).await(20_000)
        assertNotNull(o.consent)
        assertEquals(BlockerKind.SIGN_IN, o.blocker)
        assertEquals(listOf("Skip"), o.skipped)
        assertFalse(o.handedOff)
        assertEquals(0, s.handoffs)
    }

    @Test fun `with no skip button the person is asked once and the wait pauses while blocked`() = runBlocking {
        // Blocked for 90 s, then the consent screen shows: more than the 20 s base wait, but blocked time does not count.
        val s = Script(blockerUntilMs = 90_000, consentAtMs = 92_000)
        val o = assist(s).await(20_000)
        assertNotNull("consent should be reached after the person signs in", o.consent)
        assertTrue(o.handedOff)
        assertEquals(1, s.handoffs)
        assertEquals(1, s.resumes)
        assertTrue("blocked ms ${o.blockedMs}", o.blockedMs in 85_000..92_000)
    }

    @Test fun `a blocker that never clears gives up after the base wait plus the assist allowance`() = runBlocking {
        val s = Script(blockerUntilMs = Long.MAX_VALUE, consentAtMs = null)
        val o = assist(s).await(20_000, assistTimeoutMs = 60_000)
        assertNull(o.consent)
        assertEquals(BlockerKind.SIGN_IN, o.blocker)
        assertTrue("gave up at ${s.now}", s.now in 80_000..81_000)
    }

    @Test fun `the silent plan never taps and never hands over`() = runBlocking {
        val s = Script(blockerUntilMs = Long.MAX_VALUE, consentAtMs = null, skipWorks = true)
        val o = assist(s, interactive = false).await(20_000)
        assertEquals(0, s.skipTaps)
        assertEquals(0, s.handoffs)
        assertFalse(o.handedOff)
        assertEquals(BlockerKind.SIGN_IN, o.blocker)
        assertTrue("waited ${s.now}", s.now in 20_000..20_600)
    }

    @Test fun `notes say what happened and mark a plan that never reached consent`() {
        val skipped = ConsentWaitAssist.Outcome(consent, BlockerKind.SIGN_IN, listOf("Not now"), false, 0)
        assertTrue(BlockerCopy.notes(skipped).single().contains("“Not now”"))
        val blocked = ConsentWaitAssist.Outcome(null, BlockerKind.OTP, emptyList(), true, 45_000)
        val notes = BlockerCopy.notes(blocked)
        assertTrue(notes.any { it.contains("waited 45 s") })
        assertTrue(notes.any { it.startsWith(BlockerCopy.BLOCKED_MARK) })
        assertTrue("silent plans are not blocked by it", BlockerCopy.notes(blocked, blocksPlan = false).none { it.startsWith(BlockerCopy.BLOCKED_MARK) })
        assertTrue(BlockerCopy.notes(ConsentWaitAssist.Outcome(consent, null, emptyList(), false, 0)).isEmpty())
    }

    @Test fun `the popup names the field to fill in, and falls back to the kind of screen`() {
        assertEquals("Fill in “Phone number”", BlockerCopy.promptTitle(BlockerKind.SIGN_IN, listOf("Phone number")))
        assertEquals("Fill in “Email” and “Password”", BlockerCopy.promptTitle(BlockerKind.SIGN_IN, listOf("Email", "Password")))
        assertEquals("Enter the verification code", BlockerCopy.promptTitle(BlockerKind.OTP, emptyList()))
        assertEquals("Solve the CAPTCHA", BlockerCopy.promptTitle(BlockerKind.CAPTCHA, emptyList()))
        assertTrue(BlockerCopy.notificationText("Shop", BlockerKind.SIGN_IN, listOf("Phone number")).contains("“Phone number”"))
        assertTrue(BlockerCopy.promptTitle(BlockerKind.SIGN_IN, listOf("Phone number"), hindi = true).contains("“Phone number”"))
    }

    @Test fun `access needs follow the order a check needs them and clear when met`() {
        fun pf(vpn: Boolean = true, watcher: Boolean = true, other: Boolean = false, running: Boolean = true, granted: Boolean = true) =
            com.dpdpxray.app.control.Preflight(vpn, watcher, running, granted, false, false, false, other)
        assertNull(AccessNeeds.forCheck(pf()))
        assertEquals(AccessNeed.OTHER_VPN, AccessNeeds.forCheck(pf(other = true, vpn = false, watcher = false)))
        assertEquals(AccessNeed.VPN, AccessNeeds.forCheck(pf(vpn = false, watcher = false)))
        assertEquals(AccessNeed.WATCHER, AccessNeeds.forCheck(pf(watcher = false)))
        assertEquals(AccessNeed.SHIZUKU_RUNNING, AccessNeeds.shizuku(running = false, granted = false))
        assertEquals(AccessNeed.SHIZUKU_PERMISSION, AccessNeeds.shizuku(running = true, granted = false))
        assertNull(AccessNeeds.shizuku(running = true, granted = true))
        assertTrue(AccessNeeds.met(AccessNeed.WATCHER, pf()))
        assertFalse(AccessNeeds.met(AccessNeed.SHIZUKU_PERMISSION, pf(granted = false)))
        AccessNeed.values().forEach { n ->
            assertTrue(AccessCopy.reason(n).en.isNotBlank() && AccessCopy.reason(n).hi.isNotBlank())
            assertTrue(AccessCopy.button(n).en.isNotBlank() && AccessCopy.title(n).hi.isNotBlank())
        }
    }

    @Test fun `one unsuccessful try does not hand over when the next one works`() = runBlocking {
        // A form: the first pass types and opens a date picker (nothing to report yet), the second confirms it.
        val s = Script(blockerUntilMs = 2_500, consentAtMs = 3_000)
        var tries = 0
        val o = assist(s, skip = { tries++; if (tries == 1) null else "OK" }).await(20_000)
        assertNotNull(o.consent)
        assertFalse(o.handedOff)
        assertEquals(0, s.handoffs)
    }
}
