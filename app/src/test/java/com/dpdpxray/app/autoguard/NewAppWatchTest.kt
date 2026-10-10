package com.dpdpxray.app.autoguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewAppWatchTest {
    private val own = "com.dpdpxray.app"
    private val day = 24 * 3_600_000L
    private val now = 1_791_500_000_000L
    private val bank = ChangedApp("com.bank.app", now - 400 * day, false)
    private val chat = ChangedApp("com.old.chat", now - 30 * day, false)
    private val known = mapOf(bank.packageName to bank.firstInstallTime, chat.packageName to chat.firstInstallTime)
    private val saved = WatchState(sequence = 40, bootCount = 7, known = known)

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

    @Test fun `a new install is alerted, an update of a known app is not`() {
        val fresh = app("com.new.loan", now)
        val updatedChat = chat // an update keeps the first-install time
        assertEquals(listOf(fresh), NewAppWatch.newInstalls(listOf(updatedChat, fresh), own, known, emptyMap()))
    }

    @Test fun `system apps and TraceVeil itself are never alerted`() {
        val list = listOf(app("com.vendor.system", now, system = true), app(own, now), app(" ", now))
        assertTrue(NewAppWatch.newInstalls(list, own, known, emptyMap()).isEmpty())
    }

    @Test fun `an install already alerted by the live receiver is not alerted again`() {
        val fresh = app("com.new.loan", now)
        val alerted = mapOf("com.new.loan" to fresh.firstInstallTime)
        assertTrue(NewAppWatch.newInstalls(listOf(fresh), own, known, alerted).isEmpty())
        assertFalse(NewAppWatch.isNew(alerted, "com.new.loan", fresh.firstInstallTime))
    }

    @Test fun `a reinstall has a new first-install time and is alerted again`() {
        val reinstalled = app(chat.packageName, now)
        val alerted = mapOf(chat.packageName to chat.firstInstallTime)
        assertEquals(listOf(reinstalled), NewAppWatch.newInstalls(listOf(reinstalled), own, known, alerted))
        assertTrue(NewAppWatch.isNew(alerted, chat.packageName, reinstalled.firstInstallTime))
    }

    @Test fun `after a restart only apps missing from the remembered list are new`() {
        val installedBeforeShutdown = app("com.installed.before.shutdown", now - 60_000)
        val installed = listOf(bank, chat, app("com.preload.pay", 3_456_000_000L), installedBeforeShutdown, app("com.vendor.launcher", now, system = true))
        val knownWithPreload = known + ("com.preload.pay" to 3_456_000_000L)
        assertEquals(listOf(installedBeforeShutdown), NewAppWatch.newInstalls(installed, own, knownWithPreload, emptyMap()))
    }

    @Test fun `a wrong or changed clock cannot make an old app new`() {
        // Install times far in the past, far in the future or from 1970 do not matter: only the remembered list does.
        val weird = listOf(app("com.bank.app", bank.firstInstallTime), app("com.old.chat", chat.firstInstallTime))
        assertTrue(NewAppWatch.newInstalls(weird, own, known, emptyMap()).isEmpty())
        val installedWhileClockWasAhead = app("com.new.app", now + 400 * day)
        assertEquals(listOf(installedWhileClockWasAhead), NewAppWatch.newInstalls(listOf(installedWhileClockWasAhead), own, known, emptyMap()))
    }

    @Test fun `duplicates are reported once, oldest install first`() {
        val a = app("com.a.app", now + 300)
        val b = app("com.b.app", now + 100)
        assertEquals(listOf(b, a), NewAppWatch.newInstalls(listOf(a, b, a), own, known, emptyMap()))
    }

    // --- what is remembered ---------------------------------------------------------------------------------------

    @Test fun `a baseline remembers every installed app except system apps`() {
        val installed = listOf(bank, chat, app("com.vendor.launcher", now, system = true))
        assertEquals(known, NewAppWatch.nextKnown(null, NewAppWatch.Read.Baseline, installed, emptyList()))
    }

    @Test fun `after a full read the list is exactly what is installed`() {
        val installed = listOf(bank, app("com.new.loan", now))
        assertEquals(
            mapOf(bank.packageName to bank.firstInstallTime, "com.new.loan" to now),
            NewAppWatch.nextKnown(known, NewAppWatch.Read.AllInstalled, installed, emptyList()),
        )
    }

    @Test fun `within a boot only changed packages are updated, and removed ones are dropped`() {
        val fresh = app("com.new.loan", now)
        // chat was uninstalled (reported as changed, no longer installed); bank did not change.
        val next = NewAppWatch.nextKnown(known, NewAppWatch.Read.Since(40), listOf(fresh), listOf(fresh.packageName, chat.packageName))
        assertEquals(mapOf(bank.packageName to bank.firstInstallTime, fresh.packageName to now), next)
    }

    @Test fun `the reported sequence is saved with the boot count`() {
        assertEquals(WatchState(44, 7, known), NewAppWatch.next(NewAppWatch.Read.Since(40), reportedSequence = 44, bootCount = 7, known = known))
    }

    @Test fun `no reported changes keeps the same-boot sequence and starts at zero after a restart`() {
        assertEquals(40, NewAppWatch.next(NewAppWatch.Read.Since(40), null, 7, known).sequence)
        assertEquals(0, NewAppWatch.next(NewAppWatch.Read.AllInstalled, null, 8, known).sequence)
        assertEquals(0, NewAppWatch.next(NewAppWatch.Read.Baseline, null, 7, known).sequence)
        assertEquals(12, NewAppWatch.next(NewAppWatch.Read.Baseline, 12, 7, known).sequence)
    }

    // --- ledger -----------------------------------------------------------------------------------------------------

    @Test fun `the ledger keeps the most recent installs and round-trips through storage`() {
        var ledger = emptyMap<String, Long>()
        repeat(NewAppWatch.LEDGER_LIMIT + 5) { i -> ledger = NewAppWatch.record(ledger, "com.app.n$i", 1_000L + i) }
        assertEquals(NewAppWatch.LEDGER_LIMIT, ledger.size)
        assertFalse("oldest dropped first", "com.app.n0" in ledger)
        assertTrue("com.app.n${NewAppWatch.LEDGER_LIMIT + 4}" in ledger)
        assertEquals(ledger, NewAppWatch.decodeLedger(NewAppWatch.encodeLedger(ledger)))
    }

    @Test fun `malformed stored lines are dropped, not guessed`() {
        val stored = setOf("com.ok.app\t123", "no-tab", "com.bad.time\tsoon", "\t5", "")
        assertEquals(mapOf("com.ok.app" to 123L), NewAppWatch.decodeLedger(stored))
        assertTrue(NewAppWatch.decodeLedger(null).isEmpty())
    }
}
