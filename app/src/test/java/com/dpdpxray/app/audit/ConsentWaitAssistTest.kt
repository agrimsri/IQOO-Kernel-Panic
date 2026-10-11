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

    private fun assist(s: Script, interactive: Boolean = true, event: ConsentEvent = consent) = ConsentWaitAssist(
        probe = { event.takeIf { s.consentAtMs != null && s.now >= s.consentAtMs && !(blockedNow(s)) } },
        detectBlocker = { s.blocker.takeIf { blockedNow(s) } },
        trySkip = { if (s.skipWorks) { s.skipTaps++; "Skip" } else { s.skipTaps++; null } },
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
}
