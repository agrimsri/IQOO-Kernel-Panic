package com.dpdpxray.app.autoguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewAppWatchTest {
    private val own = "com.dpdpxray.app"
    private val lastCheck = 1_791_500_000_000L
    private val saved = WatchState(sequence = 40, bootCount = 7, checkedAtMs = lastCheck)

    private fun app(pkg: String, installedAt: Long, system: Boolean = false) = ChangedApp(pkg, installedAt, system)

    // --- what to read -----------------------------------------------------------------------------------------------

    @Test fun `the first check only records a baseline`() {
        assertEquals(NewAppWatch.Read.Baseline, NewAppWatch.read(saved = null, bootCount = 7))
        assertEquals(NewAppWatch.Read.Baseline, NewAppWatch.read(saved = null, bootCount = null))
    }

    @Test fun `the same boot reads changes since the saved sequence`() {
        assertEquals(NewAppWatch.Read.Since(40), NewAppWatch.read(saved, bootCount = 7))
    }

    @Test fun `after a restart the saved sequence is not trusted`() {
        assertEquals(NewAppWatch.Read.AllInstalled, NewAppWatch.read(saved, bootCount = 8))
        assertEquals(NewAppWatch.Read.AllInstalled, NewAppWatch.read(saved, bootCount = null))
        assertEquals(NewAppWatch.Read.AllInstalled, NewAppWatch.read(saved.copy(bootCount = null), bootCount = 7))
    }

    // --- which apps are new -----------------------------------------------------------------------------------------

    @Test fun `a new install after the last check is alerted, an update of an old app is not`() {
        val fresh = app("com.new.loan", lastCheck + 60_000)
        val updated = app("com.old.chat", lastCheck - 30L * 24 * 3600 * 1000)
        assertEquals(listOf(fresh), NewAppWatch.newInstalls(listOf(updated, fresh), own, saved, emptyMap()))
    }

    @Test fun `system apps and TraceVeil itself are never alerted`() {
        val list = listOf(app("com.vendor.system", lastCheck + 1, system = true), app(own, lastCheck + 1), app(" ", lastCheck + 1))
        assertTrue(NewAppWatch.newInstalls(list, own, saved, emptyMap()).isEmpty())
    }

    @Test fun `an app installed just before the last check finished is still new`() {
        val racing = app("com.race.app", lastCheck - 60_000)
        val old = app("com.old.app", lastCheck - NewAppWatch.MARGIN_MS - 1)
        assertEquals(listOf(racing), NewAppWatch.newInstalls(listOf(racing, old), own, saved, emptyMap()))
    }

    @Test fun `an install already alerted by the live receiver is not alerted again`() {
        val fresh = app("com.new.loan", lastCheck + 60_000)
        val alerted = mapOf("com.new.loan" to fresh.firstInstallTime)
        assertTrue(NewAppWatch.newInstalls(listOf(fresh), own, saved, alerted).isEmpty())
        assertFalse(NewAppWatch.isNew(alerted, "com.new.loan", fresh.firstInstallTime))
    }

    @Test fun `a reinstall has a new first-install time and is alerted again`() {
        val reinstalled = app("com.new.loan", lastCheck + 120_000)
        val alerted = mapOf("com.new.loan" to lastCheck - 3_600_000)
        assertEquals(listOf(reinstalled), NewAppWatch.newInstalls(listOf(reinstalled), own, saved, alerted))
        assertTrue(NewAppWatch.isNew(alerted, "com.new.loan", reinstalled.firstInstallTime))
    }

    @Test fun `after a restart every installed app is a candidate but only the ones installed since the last check are new`() {
        val installed = listOf(
            app("com.bank.app", lastCheck - 400L * 24 * 3600 * 1000),
            app("com.preload.pay", 3_456_000_000L), // preloaded app with a 1970 date
            app("com.installed.before.shutdown", lastCheck + 5 * 60_000),
            app("com.vendor.launcher", lastCheck + 10 * 60_000, system = true),
        )
        assertEquals(listOf("com.installed.before.shutdown"), NewAppWatch.newInstalls(installed, own, saved, emptyMap()).map { it.packageName })
    }

    @Test fun `duplicates are reported once, oldest install first`() {
        val a = app("com.a.app", lastCheck + 300)
        val b = app("com.b.app", lastCheck + 100)
        assertEquals(listOf(b, a), NewAppWatch.newInstalls(listOf(a, b, a), own, saved, emptyMap()))
    }

    // --- what is saved ----------------------------------------------------------------------------------------------

    @Test fun `the reported sequence is saved with the boot and the start time`() {
        val next = NewAppWatch.next(NewAppWatch.Read.Since(40), reportedSequence = 44, bootCount = 7, startedAt = lastCheck + 900_000)
        assertEquals(WatchState(44, 7, lastCheck + 900_000), next)
    }

    @Test fun `no reported changes keeps the same-boot sequence and starts at zero after a restart`() {
        assertEquals(40, NewAppWatch.next(NewAppWatch.Read.Since(40), null, 7, 1L).sequence)
        assertEquals(0, NewAppWatch.next(NewAppWatch.Read.AllInstalled, null, 8, 1L).sequence)
        assertEquals(0, NewAppWatch.next(NewAppWatch.Read.Baseline, null, 7, 1L).sequence)
        assertEquals(12, NewAppWatch.next(NewAppWatch.Read.Baseline, 12, 7, 1L).sequence)
    }

    @Test fun `the ledger keeps the most recent installs and round-trips through storage`() {
        var ledger = emptyMap<String, Long>()
        repeat(NewAppWatch.LEDGER_LIMIT + 5) { i -> ledger = NewAppWatch.record(ledger, "com.app.n$i", 1_000L + i) }
        assertEquals(NewAppWatch.LEDGER_LIMIT, ledger.size)
        assertFalse("oldest dropped first", "com.app.n0" in ledger)
        assertTrue("com.app.n${NewAppWatch.LEDGER_LIMIT + 4}" in ledger)
        assertEquals(ledger, NewAppWatch.decodeLedger(NewAppWatch.encodeLedger(ledger)))
    }

    @Test fun `malformed stored ledger lines are dropped, not guessed`() {
        val stored = setOf("com.ok.app\t123", "no-tab", "com.bad.time\tsoon", "\t5", "")
        assertEquals(mapOf("com.ok.app" to 123L), NewAppWatch.decodeLedger(stored))
        assertTrue(NewAppWatch.decodeLedger(null).isEmpty())
    }
}
