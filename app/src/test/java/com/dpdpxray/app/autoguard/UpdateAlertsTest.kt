package com.dpdpxray.app.autoguard

import com.dpdpxray.app.apkguard.ApkGuardRoutes
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.update.AppVersion
import com.dpdpxray.core.update.UpdateKind
import com.dpdpxray.core.update.UpdatedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAlertsTest {
    private fun item(
        kind: UpdateKind = UpdateKind.UPDATED, old: AppVersion? = AppVersion("1.0", 1), new: AppVersion? = AppVersion("1.1", 2),
        score: Int? = 8, band: ScoreBand = ScoreBand.AT_RISK, pkg: String = "com.leakyshop.demo", label: String = "LeakyShop",
    ) = UpdatedApp(pkg, label, kind, old, new, "audit-1", 1_000, score, band)

    // --- wording ------------------------------------------------------------------------------------------------------

    @Test
    fun `an update names both versions and the last result and only offers a re-check`() {
        val a = UpdateAlertPolicy.alert(item())
        assertEquals("LeakyShop updated (1.0 → 1.1)", a.title)
        assertEquals("Last check: 8 · At risk. Tap to re-check consent.", a.text)
        assertEquals("LeakyShop अपडेट हुआ (1.0 → 1.1)। पिछली जाँच: 8 · जोखिम में। सहमति दोबारा जाँचने के लिए टैप करें।", a.hindi)
        assertEquals("LeakyShop was updated. Its last check scored 8, at risk. Open TraceVeil to check it again.", a.spokenEn)
    }

    @Test
    fun `a downgrade says it is an older version`() {
        val a = UpdateAlertPolicy.alert(item(UpdateKind.DOWNGRADED, old = AppVersion("1.1", 2), new = AppVersion("1.0", 1)))
        assertEquals("LeakyShop changed to an older version (1.1 → 1.0)", a.title)
        assertEquals("Last check: 8 · At risk. Tap to re-check consent.", a.text)
    }

    @Test
    fun `an unrecorded old version is said plainly`() {
        val a = UpdateAlertPolicy.alert(item(UpdateKind.VERSION_NOT_RECORDED, old = null))
        assertEquals("LeakyShop updated (now 1.1)", a.title)
        assertEquals("Last check: 8 · At risk (version not recorded then). Tap to re-check consent.", a.text)
    }

    @Test
    fun `an inconclusive last check has no score`() {
        val a = UpdateAlertPolicy.alert(item(score = null, band = ScoreBand.INCONCLUSIVE))
        assertEquals("Last check: inconclusive. Tap to re-check consent.", a.text)
        assertFalse(a.spokenEn.contains("scored"))
    }

    @Test
    fun `the wording never claims what changed inside the app`() {
        listOf(UpdateKind.UPDATED, UpdateKind.DOWNGRADED, UpdateKind.VERSION_NOT_RECORDED).forEach { k ->
            val a = UpdateAlertPolicy.alert(item(k))
            val all = listOf(a.title, a.text, a.spokenEn).joinToString(" ").lowercase()
            listOf("fixed", "safe", "compliant", "now shares", "started").forEach { assertFalse("$k: $it", all.contains(it)) }
        }
    }

    @Test
    fun `a very long app name is shortened in the title`() {
        val a = UpdateAlertPolicy.alert(item(label = "x".repeat(90)))
        assertTrue(a.title.startsWith("x".repeat(59) + "…"))
    }

    @Test
    fun `last-check text for the in-app card`() {
        assertEquals("8 · At risk", UpdateAlertPolicy.lastCheck(8, ScoreBand.AT_RISK))
        assertEquals("Inconclusive", UpdateAlertPolicy.lastCheck(null, ScoreBand.INCONCLUSIVE))
        assertEquals("Inconclusive", UpdateAlertPolicy.lastCheck(40, ScoreBand.INCONCLUSIVE))
    }

    // --- dedup ledger -------------------------------------------------------------------------------------------------

    @Test
    fun `one notification per package and version code`() {
        val key = UpdateAlertLedger.key(item())!!
        assertEquals(UpdateAlertLedger.Key("com.leakyshop.demo", 2), key)
        var ledger = emptyMap<UpdateAlertLedger.Key, Long>()
        assertTrue(UpdateAlertLedger.isNew(ledger, key))
        ledger = UpdateAlertLedger.record(ledger, key, at = 10)
        assertFalse(UpdateAlertLedger.isNew(ledger, key))
        // The next version of the same app is a new alert; a different app at the same code is too.
        assertTrue(UpdateAlertLedger.isNew(ledger, UpdateAlertLedger.Key("com.leakyshop.demo", 3)))
        assertTrue(UpdateAlertLedger.isNew(ledger, UpdateAlertLedger.Key("other.app", 2)))
    }

    @Test
    fun `a new check of the app makes the same version news again`() {
        val key = UpdateAlertLedger.Key("com.leakyshop.demo", 2)
        val ledger = UpdateAlertLedger.record(emptyMap(), key, at = 5_000)
        // Alerted at 5 000 against a check made at 1 000: not again.
        assertFalse(UpdateAlertLedger.isNew(ledger, key, lastCheckedAtMs = 1_000))
        // The person checked the app again at 6 000 (for example a rehearsal on 1.0) and 1.1 was installed again: news.
        assertTrue(UpdateAlertLedger.isNew(ledger, key, lastCheckedAtMs = 6_000))
        val plan = UpdateAlertPlan.plan(NewAppWatch.Read.Since(4), listOf(item().copy(lastAuditedAtMs = 6_000)), listOf("com.leakyshop.demo"), ledger, initialised = true)
        assertEquals(1, plan.post.size)
    }

    @Test
    fun `an item without an installed version code has no key`() {
        assertNull(UpdateAlertLedger.key(item(new = AppVersion("1.1", null))))
        assertNull(UpdateAlertLedger.key(item(new = null)))
    }

    @Test
    fun `the ledger survives storage and drops malformed lines`() {
        val ledger = mapOf(UpdateAlertLedger.Key("a.app", 2) to 10L, UpdateAlertLedger.Key("b.app", 7) to 20L)
        val stored = UpdateAlertLedger.encode(ledger)
        assertEquals(ledger, UpdateAlertLedger.decode(stored + setOf("junk", "c.app\tx\t1", "\t1\t1")))
        assertTrue(UpdateAlertLedger.decode(null).isEmpty())
    }

    @Test
    fun `the ledger keeps the most recent alerts`() {
        var ledger = emptyMap<UpdateAlertLedger.Key, Long>()
        (1..UpdateAlertLedger.LIMIT + 5).forEach { i -> ledger = UpdateAlertLedger.record(ledger, UpdateAlertLedger.Key("p$i.app", 1), at = i.toLong()) }
        assertEquals(UpdateAlertLedger.LIMIT, ledger.size)
        assertFalse(UpdateAlertLedger.isNew(ledger, UpdateAlertLedger.Key("p${UpdateAlertLedger.LIMIT + 5}.app", 1)))
        assertTrue(UpdateAlertLedger.isNew(ledger, UpdateAlertLedger.Key("p1.app", 1)))
    }

    // --- which updates are alerted --------------------------------------------------------------------------------------

    private val leaky = item()
    private val other = item(pkg = "other.app", label = "Other")

    @Test
    fun `after a same-boot read only packages Android reported as changed are alerted`() {
        val plan = UpdateAlertPlan.plan(NewAppWatch.Read.Since(4), listOf(leaky, other), changedNames = listOf("com.leakyshop.demo"), ledger = emptyMap(), initialised = true)
        assertEquals(listOf(leaky), plan.post)
        assertTrue(plan.seed.isEmpty())
    }

    @Test
    fun `after a restart every updated checked app not yet alerted is alerted`() {
        // Alerted at 2 000, after the check it was compared with (1 000).
        val ledger = UpdateAlertLedger.record(emptyMap(), UpdateAlertLedger.key(other)!!, 2_000)
        val plan = UpdateAlertPlan.plan(NewAppWatch.Read.AllInstalled, listOf(leaky, other), changedNames = emptyList(), ledger = ledger, initialised = true)
        assertEquals(listOf(leaky), plan.post)
    }

    @Test
    fun `the first check only records what is already updated and alerts nothing`() {
        val baseline = UpdateAlertPlan.plan(NewAppWatch.Read.Baseline, listOf(leaky, other), emptyList(), emptyMap(), initialised = true)
        assertTrue(baseline.post.isEmpty())
        assertEquals(listOf(leaky, other), baseline.seed)
        // The first run after this feature arrives behaves the same, whatever the read: old updates are not news.
        val firstRun = UpdateAlertPlan.plan(NewAppWatch.Read.Since(4), listOf(leaky), listOf("com.leakyshop.demo"), emptyMap(), initialised = false)
        assertTrue(firstRun.post.isEmpty())
        assertEquals(listOf(leaky), firstRun.seed)
    }

    @Test
    fun `an update alerted already is never alerted again`() {
        val ledger = UpdateAlertLedger.record(emptyMap(), UpdateAlertLedger.key(leaky)!!, 2_000)
        val plan = UpdateAlertPlan.plan(NewAppWatch.Read.Since(4), listOf(leaky), listOf("com.leakyshop.demo"), ledger, initialised = true)
        assertTrue(plan.post.isEmpty())
    }

    @Test
    fun `the live receiver checks only real updates of other apps`() {
        assertTrue(UpdateAlertPlan.liveUpdate("android.intent.action.PACKAGE_ADDED", replacing = true, "com.leakyshop.demo", "com.dpdpxray.app"))
        assertFalse(UpdateAlertPlan.liveUpdate("android.intent.action.PACKAGE_ADDED", replacing = false, "com.leakyshop.demo", "com.dpdpxray.app"))
        assertFalse(UpdateAlertPlan.liveUpdate("android.intent.action.PACKAGE_ADDED", replacing = true, "com.dpdpxray.app", "com.dpdpxray.app"))
        assertFalse(UpdateAlertPlan.liveUpdate("android.intent.action.PACKAGE_REMOVED", replacing = true, "com.leakyshop.demo", "com.dpdpxray.app"))
        assertFalse(UpdateAlertPlan.liveUpdate("android.intent.action.PACKAGE_ADDED", replacing = true, null, "com.dpdpxray.app"))
    }

    // --- re-check deep link ---------------------------------------------------------------------------------------------

    @Test
    fun `a tapped update alert opens the check flow with that app selected`() {
        assertEquals("pick?q=com.leakyshop.demo&select=com.leakyshop.demo", AutoGuardRoutes.route(AutoGuardRoutes.ACTION_RECHECK, "com.leakyshop.demo"))
        assertEquals("pick?q=com.leakyshop.demo&select=com.leakyshop.demo", ApkGuardRoutes.recheckPicker("com.leakyshop.demo"))
    }

    @Test
    fun `the picker route with a pre-selected app still belongs to the Check tab`() {
        assertEquals(com.dpdpxray.app.ui.shell.AppTab.CHECK, com.dpdpxray.app.ui.shell.AppTabs.tabOf("pick?q={q}&select={select}"))
    }

    @Test
    fun `a crafted or missing package opens nothing`() {
        assertNull(AutoGuardRoutes.route(AutoGuardRoutes.ACTION_RECHECK, null))
        assertNull(AutoGuardRoutes.route(AutoGuardRoutes.ACTION_RECHECK, "home&select=x"))
        assertNull(AutoGuardRoutes.route(AutoGuardRoutes.ACTION_RECHECK, "../settings"))
        // The other Auto-guard actions are unchanged and ignore the package.
        assertEquals(AutoGuardRoutes.CHECKUP, AutoGuardRoutes.route(AutoGuardRoutes.ACTION_OPEN_CHECKUP, "com.leakyshop.demo"))
        assertEquals(AutoGuardRoutes.CHECKUP, AutoGuardRoutes.route(AutoGuardRoutes.ACTION_OPEN_CHECKUP))
    }
}
