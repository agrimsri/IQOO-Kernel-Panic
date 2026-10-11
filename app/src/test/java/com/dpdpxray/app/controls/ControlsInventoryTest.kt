package com.dpdpxray.app.controls

import com.dpdpxray.app.autoguard.AutoGuardJobs
import com.dpdpxray.app.control.Preflight
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.data.SettingsCodec
import com.dpdpxray.app.data.SettingsSink
import com.dpdpxray.app.data.XraySettings
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.SetupSteps
import com.dpdpxray.app.voice.ApiEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "What TraceVeil uses · switch off any time" (Home, and Settings › Permissions & background). The inventory is pure:
 * these tests pin which rows appear, the state each reading gives, and what each row's action does.
 */
class ControlsInventoryTest {
    private val mic = "android.permission.RECORD_AUDIO"
    private val notify = "android.permission.POST_NOTIFICATIONS"
    private val install = "android.permission.REQUEST_INSTALL_PACKAGES"

    private fun readings(
        settings: XraySettings = XraySettings(),
        requested: Set<String> = setOf(mic, notify),
        notificationsOn: Boolean? = true,
        micGranted: Boolean? = false,
        installUnknownAllowed: Boolean? = null,
        wakeListening: Boolean = false,
        onlineVoiceInBuild: Boolean = false,
        onlineVoiceSetUp: Boolean = false,
        onlineVoiceAllowed: Boolean = true,
        runnerOn: Boolean = false,
        savedChecks: Int = 0,
        checkRunning: Boolean = false,
    ) = ControlReadings(
        settings, requested, notificationsOn, micGranted, installUnknownAllowed, wakeListening, onlineVoiceInBuild, onlineVoiceSetUp, onlineVoiceAllowed, runnerOn, savedChecks, checkRunning,
    )

    private fun dev(r: ControlReadings = readings()) = ControlsInventory.build(r, Audience.DEVELOPER)
    private fun everyday(r: ControlReadings = readings()) = ControlsInventory.build(r, Audience.USER)
    private fun List<Control>.of(id: ControlId) = single { it.id == id }
    private fun hasHindi(b: Bi) = b.hi.any { it in 'ऀ'..'ॿ' }

    // ---- what is listed ------------------------------------------------------------------------------------------

    private fun preflight(
        vpnReady: Boolean = false, watcher: Boolean = false, shizukuRunning: Boolean = false, shizukuGranted: Boolean = false,
        privateDns: Boolean = false, otherVpn: Boolean = false,
    ) = Preflight(vpnReady, watcher, shizukuRunning, shizukuGranted, privateDns, modelFound = false, modelLoaded = false, otherVpnActive = otherVpn)

    private fun setup(pf: Preflight = preflight()) = ControlsInventory.setupStates(SetupSteps.from(pf), pf.otherVpnActive)

    // ---- what is listed ------------------------------------------------------------------------------------------

    @Test fun `a fresh install lists the permissions and switches the Setup sheet does not already cover, in order`() {
        assertEquals(
            listOf(
                ControlId.NOTIFICATIONS, ControlId.MICROPHONE,
                ControlId.NEW_APP_CHECK, ControlId.UPDATE_ALERTS, ControlId.SPEAK_ALERTS, ControlId.APK_DOWNLOADS, ControlId.WEEKLY_CHECKUP,
                ControlId.WAKE_WORD, ControlId.AUTO_LOAD_AI, ControlId.WEBSITE_CHECK, ControlId.RUNNER, ControlId.SIGNED_EXPORTS, ControlId.SAVED_CHECKS,
            ),
            dev().map { it.id },
        )
    }

    @Test fun `the VPN, consent watcher, Private DNS and Shizuku have one home - the Setup sheet - so they are not rows here`() {
        val setupOwned = setOf("CHECK_VPN", "VPN", "CONSENT_WATCHER", "WATCHER", "PRIVATE_DNS", "SHIZUKU")
        assertTrue(ControlId.entries.none { it.name in setupOwned })
    }

    @Test fun `every row says what it is, what it does and how to change it, in English and Hindi`() {
        val everything = readings(
            requested = setOf(mic, notify, install), installUnknownAllowed = true,
            onlineVoiceInBuild = true, onlineVoiceSetUp = true, runnerOn = true, savedChecks = 3,
        )
        assertEquals(ControlId.entries, dev(everything).map { it.id })
        // Everyday mode leaves out only the developer-only information row.
        assertEquals(ControlId.entries - ControlId.SIGNED_EXPORTS, everyday(everything).map { it.id })
        listOf(dev(everything), everyday(everything)).forEach { list ->
            list.forEach { c ->
                listOf(c.title, c.what, c.hint).forEach { b ->
                    assertTrue("${c.id}: blank English", b.en.isNotBlank())
                    assertTrue("${c.id}: no Hindi in '${b.hi}'", hasHindi(b))
                }
                assertEquals(c.id.group, c.group)
            }
        }
        ControlGroup.entries.forEach { g ->
            assertTrue(hasHindi(ControlsCopy.groupTitle(g)))
            ControlsCopy.groupFooter(g)?.let { assertTrue(hasHindi(it)) }
        }
    }

    @Test fun `rows are grouped as other phone permissions, TraceVeil's own switches and your data`() {
        val groups = dev().groupBy { it.group }.mapValues { (_, v) -> v.map { it.id } }
        assertEquals(listOf(ControlId.NOTIFICATIONS, ControlId.MICROPHONE), groups[ControlGroup.PHONE])
        assertEquals(listOf(ControlId.SAVED_CHECKS), groups[ControlGroup.DATA])
        assertEquals(ControlGroup.entries, ControlsInventory.groups(dev()))
        assertEquals("Other phone permissions", ControlsCopy.groupTitle(ControlGroup.PHONE).en)
    }

    // ---- Android's switches: always "open the right screen", never switched in place -------------------------------

    @Test fun `Android permissions open their system screen and are never flipped in place`() {
        val list = dev(readings(requested = setOf(mic, notify, install), installUnknownAllowed = true))
        list.filter { it.group == ControlGroup.PHONE }.forEach { c ->
            assertTrue("${c.id} must open a system screen", c.action is ControlAction.Open)
            assertEquals(ControlsCopy.openSetting, (c.action as ControlAction.Open).label)
            assertNull(ControlsInventory.storeOf(c.id))
        }
    }

    @Test fun `notifications and microphone appear only when the manifest requests them, and unknown stays unknown`() {
        val none = dev(readings(requested = emptySet())).map { it.id }
        assertFalse(ControlId.NOTIFICATIONS in none)
        assertFalse(ControlId.MICROPHONE in none)

        val list = dev(readings(notificationsOn = null, micGranted = true))
        assertEquals(ControlState.NOT_REPORTED, list.of(ControlId.NOTIFICATIONS).state)
        assertEquals(SystemScreen.NOTIFICATIONS, (list.of(ControlId.NOTIFICATIONS).action as ControlAction.Open).screen)
        assertEquals(ControlState.ON, list.of(ControlId.MICROPHONE).state)
        assertEquals(SystemScreen.APP_PERMISSIONS, (list.of(ControlId.MICROPHONE).action as ControlAction.Open).screen)
        assertTrue(list.of(ControlId.MICROPHONE).hint.en.contains("Don't allow"))
        assertEquals(ControlState.NOT_GRANTED, dev(readings(micGranted = false)).of(ControlId.MICROPHONE).state)
        assertEquals(ControlState.OFF, dev(readings(notificationsOn = false)).of(ControlId.NOTIFICATIONS).state)
    }

    // ---- the Setup sheet's items, counted in the same summary ---------------------------------------------------------

    @Test fun `the Setup sheet's VPN, watcher and Shizuku are counted from SetupSteps, Private DNS is not`() {
        assertEquals(listOf(ControlState.NOT_GRANTED, ControlState.OFF, ControlState.NOT_RUNNING), setup())
        assertEquals(
            listOf(ControlState.ON, ControlState.ON, ControlState.ON),
            setup(preflight(vpnReady = true, watcher = true, shizukuRunning = true, shizukuGranted = true, privateDns = true)),
        )
        assertEquals(ControlState.NOT_GRANTED, setup(preflight(shizukuRunning = true))[2])
        // With another VPN on, TraceVeil does not ask Android about its own VPN: not known, never counted as off.
        assertEquals(ControlState.NOT_REPORTED, setup(preflight(otherVpn = true)).first())
    }

    @Test fun `install unknown apps is left out when the manifest does not ask for it, and listed when it does`() {
        assertFalse(ControlId.INSTALL_UNKNOWN_APPS in dev(readings(requested = setOf(mic, notify))).map { it.id })
        val row = dev(readings(requested = setOf(install), installUnknownAllowed = true)).of(ControlId.INSTALL_UNKNOWN_APPS)
        assertEquals(ControlState.ON, row.state)
        assertEquals(SystemScreen.INSTALL_UNKNOWN_APPS, (row.action as ControlAction.Open).screen)
        assertEquals(ControlState.OFF, dev(readings(requested = setOf(install), installUnknownAllowed = false)).of(ControlId.INSTALL_UNKNOWN_APPS).state)
        assertEquals(ControlState.NOT_REPORTED, dev(readings(requested = setOf(install), installUnknownAllowed = null)).of(ControlId.INSTALL_UNKNOWN_APPS).state)
    }

    // ---- TraceVeil's own switches: flipped in place -----------------------------------------------------------------

    @Test fun `TraceVeil's own switches show the stored settings and flip in place`() {
        val defaults = dev()
        mapOf(
            ControlId.NEW_APP_CHECK to true, ControlId.UPDATE_ALERTS to true, ControlId.SPEAK_ALERTS to false,
            ControlId.APK_DOWNLOADS to true, ControlId.WEEKLY_CHECKUP to true, ControlId.WAKE_WORD to false, ControlId.AUTO_LOAD_AI to true, ControlId.RUNNER to false,
        ).forEach { (id, on) ->
            val c = defaults.of(id)
            assertEquals("$id", ControlAction.Toggle(on), c.action)
            assertEquals("$id", if (on) ControlState.ON else ControlState.OFF, c.state)
            assertNotNull("$id has nowhere to write", ControlsInventory.storeOf(id))
        }
        val off = dev(readings(settings = XraySettings(autoCheckNewApps = true, weeklyCheckup = false, autoLoadModel = false, heyTraceVeil = true)))
        assertEquals(ControlAction.Toggle(false), off.of(ControlId.WEEKLY_CHECKUP).action)
        assertEquals(ControlAction.Toggle(false), off.of(ControlId.AUTO_LOAD_AI).action)
        assertEquals(ControlAction.Toggle(true), off.of(ControlId.WAKE_WORD).action)
    }

    @Test fun `update alerts and spoken alerts ride on the new-app check, so they are off and locked while it is off`() {
        val list = dev(readings(settings = XraySettings(autoCheckNewApps = false, alertCheckedAppUpdates = true, speakApkGuardAlerts = true)))
        listOf(ControlId.UPDATE_ALERTS, ControlId.SPEAK_ALERTS).forEach { id ->
            assertEquals(ControlState.OFF, list.of(id).state)
            assertEquals(ControlAction.Toggle(on = false, enabled = false), list.of(id).action)
            assertTrue(list.of(id).hint.en.contains("new-app check"))
        }
    }

    @Test fun `the APK downloads check has its own job, so it stays switchable while the new-app check is off`() {
        val list = dev(readings(settings = XraySettings(autoCheckNewApps = false, checkApkDownloads = true)))
        val row = list.of(ControlId.APK_DOWNLOADS)
        assertEquals(ControlState.ON, row.state)
        assertEquals(ControlAction.Toggle(on = true, enabled = true), row.action)
        assertEquals("Check new APK downloads", row.title.en)
        assertTrue(row.what.en, row.what.en.contains("Downloads"))
        assertTrue(row.hint.en, row.hint.en.contains("cancels its background job"))
        val off = dev(readings(settings = XraySettings(checkApkDownloads = false))).of(ControlId.APK_DOWNLOADS)
        assertEquals(ControlAction.Toggle(false), off.action)
        assertEquals(false, ControlsInventory.settingsAfter(ControlId.APK_DOWNLOADS, false, XraySettings())!!.checkApkDownloads)
    }

    @Test fun `each settings switch writes exactly its own stored key`() {
        val keys = mapOf(
            ControlId.NEW_APP_CHECK to "auto_check_new_apps",
            ControlId.UPDATE_ALERTS to "alert_checked_app_updates",
            ControlId.SPEAK_ALERTS to "speak_apk_guard_alerts",
            ControlId.APK_DOWNLOADS to "check_apk_downloads",
            ControlId.WEEKLY_CHECKUP to "weekly_checkup",
            ControlId.WAKE_WORD to "hey_traceveil",
            ControlId.AUTO_LOAD_AI to "auto_load_model",
        )
        val base = XraySettings()
        keys.forEach { (id, key) ->
            assertEquals(SwitchStore.SETTINGS, ControlsInventory.storeOf(id))
            listOf(true, false).forEach { on ->
                val after = ControlsInventory.settingsAfter(id, on, base)!!
                val before = encode(base)
                val changed = encode(after).filter { (k, v) -> before[k] != v }.keys
                assertTrue("$id -> $changed", changed.isEmpty() || changed == setOf(key))
                assertEquals(on, encode(after)[key])
            }
        }
        // Rows that do not live in the settings file never touch it.
        ControlId.entries.filter { it !in keys }.forEach { assertNull(ControlsInventory.settingsAfter(it, false, base)) }
        assertEquals(SwitchStore.RUNNER, ControlsInventory.storeOf(ControlId.RUNNER))
        assertEquals(SwitchStore.ONLINE_VOICE, ControlsInventory.storeOf(ControlId.ONLINE_VOICE))
    }

    @Test fun `switching the background checks off cancels their jobs through the existing sync plan`() {
        val newAppsOff = ControlsInventory.settingsAfter(ControlId.NEW_APP_CHECK, false, XraySettings())!!
        val plan = AutoGuardJobs.plan(newAppsOff.autoCheckNewApps, newAppsOff.weeklyCheckup)
        assertEquals(setOf(AutoGuardJobs.NEW_APPS_PERIODIC, AutoGuardJobs.NEW_APPS_NOW), plan.cancel)

        val weeklyOff = ControlsInventory.settingsAfter(ControlId.WEEKLY_CHECKUP, false, XraySettings())!!
        assertEquals(setOf(AutoGuardJobs.WEEKLY_CHECKUP), AutoGuardJobs.plan(weeklyOff.autoCheckNewApps, weeklyOff.weeklyCheckup).cancel)
    }

    @Test fun `wake word - switching it off stops the microphone first, switching it on never starts it`() {
        assertTrue(ControlsInventory.stopsListening(ControlId.WAKE_WORD, on = false))
        assertFalse(ControlsInventory.stopsListening(ControlId.WAKE_WORD, on = true))
        assertFalse(ControlsInventory.stopsListening(ControlId.NEW_APP_CHECK, on = false))
        val listening = dev(readings(settings = XraySettings(heyTraceVeil = true), wakeListening = true)).of(ControlId.WAKE_WORD)
        assertEquals(ControlState.ON, listening.state)
        assertTrue(listening.what.en.contains("Listening now"))
        assertTrue(dev().of(ControlId.WAKE_WORD).what.en.contains("Start"))
    }

    @Test fun `online voice - only in builds that include it, no switch until it is set up, then a real off switch`() {
        assertFalse(ControlId.ONLINE_VOICE in dev(readings(onlineVoiceInBuild = false, onlineVoiceSetUp = true)).map { it.id })

        val notSetUp = dev(readings(onlineVoiceInBuild = true, onlineVoiceSetUp = false)).of(ControlId.ONLINE_VOICE)
        assertEquals(ControlState.OFF, notSetUp.state)
        assertEquals(ControlAction.None, notSetUp.action)

        val on = dev(readings(onlineVoiceInBuild = true, onlineVoiceSetUp = true, onlineVoiceAllowed = true)).of(ControlId.ONLINE_VOICE)
        assertEquals(ControlState.ON, on.state)
        assertEquals(ControlAction.Toggle(true), on.action)
        assertTrue(on.what.en.contains("Sarvam AI"))

        val off = dev(readings(onlineVoiceInBuild = true, onlineVoiceSetUp = true, onlineVoiceAllowed = false)).of(ControlId.ONLINE_VOICE)
        assertEquals(ControlState.OFF, off.state)
        assertEquals(ControlAction.Toggle(false), off.action)
    }

    @Test fun `with online voice switched off, its key file is not even read`() {
        var read = false
        val config = ApiEnv.load(onlineVoiceAvailable = false) { read = true; "USE_API=true\nSARVAM_API_KEY=k" }
        assertFalse(config.useApi)
        assertFalse(read)
    }

    @Test fun `the website check's browser is a network use with no switch, shown in both modes`() {
        listOf(dev(), everyday()).forEach { list ->
            val row = list.of(ControlId.WEBSITE_CHECK)
            assertEquals(ControlState.INFO, row.state)
            assertEquals(ControlAction.None, row.action)
            assertTrue(row.what.en.contains("only while you watch the check"))
        }
        assertEquals(ControlGroup.TRACEVEIL, ControlId.WEBSITE_CHECK.group)
    }

    @Test fun `signed exports have no switch and are shown as information in developer mode only`() {
        val row = dev().of(ControlId.SIGNED_EXPORTS)
        assertEquals(ControlState.INFO, row.state)
        assertEquals(ControlAction.None, row.action)
        assertFalse(ControlId.SIGNED_EXPORTS in everyday().map { it.id })
    }

    // ---- modes ------------------------------------------------------------------------------------------------------

    @Test fun `everyday mode uses friendlier names in both languages`() {
        listOf(ControlId.NEW_APP_CHECK, ControlId.SPEAK_ALERTS, ControlId.WAKE_WORD, ControlId.AUTO_LOAD_AI, ControlId.RUNNER).forEach { id ->
            val d = dev(readings(runnerOn = true)).of(id).title
            val e = everyday(readings(runnerOn = true)).of(id).title
            assertTrue("$id", d.en != e.en && d.hi != e.hi)
            assertTrue(hasHindi(e))
        }
        assertEquals("Auto-guard new-app check", dev().of(ControlId.NEW_APP_CHECK).title.en)
        assertEquals("Check new apps automatically", everyday().of(ControlId.NEW_APP_CHECK).title.en)
        // The same rows and states in both modes; only the words and the developer-only rows differ.
        assertEquals(
            dev().filter { it.id != ControlId.RUNNER && it.id != ControlId.SIGNED_EXPORTS }.map { it.id to it.state },
            everyday().map { it.id to it.state },
        )
    }

    @Test fun `the laptop runner shows in everyday mode only while it is on, so it can be switched off`() {
        assertFalse(ControlId.RUNNER in everyday(readings(runnerOn = false)).map { it.id })
        val on = everyday(readings(runnerOn = true)).of(ControlId.RUNNER)
        assertEquals(ControlAction.Toggle(true), on.action)
    }

    // ---- summary line -----------------------------------------------------------------------------------------------

    @Test fun `summary counts on and off over this list and the Setup sheet, keeps not-reported apart, ignores info and data`() {
        val s = ControlsInventory.summary(dev(readings(savedChecks = 4)), setup())
        // On: notifications, new-app check, update alerts, APK downloads check, weekly checkup, load AI.
        // Off: microphone (not granted), spoken alerts, wake word, runner; and from Setup: VPN, watcher, Shizuku.
        assertEquals(6, s.on)
        assertEquals(7, s.off)
        assertEquals(0, s.notReported)
        assertEquals("6 on · 7 off", s.line.en)
        assertEquals("6 चालू · 7 बंद", s.line.hi)

        val unknown = ControlsInventory.summary(dev(readings(notificationsOn = null)), setup(preflight(otherVpn = true)))
        assertEquals(2, unknown.notReported)
        assertEquals("5 on · 6 off · 2 not reported", unknown.line.en)
        assertTrue(hasHindi(unknown.line))

        // Without the Setup sheet's items the count covers this list only.
        assertEquals(ControlsSummary(6, 4, 0), ControlsInventory.summary(dev()))
    }

    @Test fun `state words are the chip text, in both languages`() {
        assertEquals("On", ControlsCopy.state(ControlState.ON).en)
        assertEquals("Off", ControlsCopy.state(ControlState.OFF).en)
        assertEquals("Not granted", ControlsCopy.state(ControlState.NOT_GRANTED).en)
        ControlState.entries.forEach { assertTrue("$it", hasHindi(ControlsCopy.state(it))) }
    }

    // ---- data -------------------------------------------------------------------------------------------------------

    @Test fun `saved checks - count, back up and delete need saved checks, delete waits for a running check`() {
        val none = dev(readings(savedChecks = 0)).of(ControlId.SAVED_CHECKS)
        assertEquals(ControlAction.Checks(saved = 0, canBackUp = false, canDelete = false), none.action)
        assertEquals("None saved yet", none.what.en)

        val some = dev(readings(savedChecks = 5)).of(ControlId.SAVED_CHECKS)
        assertEquals(ControlAction.Checks(saved = 5, canBackUp = true, canDelete = true), some.action)
        assertEquals("5 saved on this phone", some.what.en)
        assertEquals("1 saved on this phone", dev(readings(savedChecks = 1)).of(ControlId.SAVED_CHECKS).what.en)

        val running = dev(readings(savedChecks = 5, checkRunning = true)).of(ControlId.SAVED_CHECKS)
        assertEquals(ControlAction.Checks(saved = 5, canBackUp = true, canDelete = false), running.action)
    }

    @Test fun `delete all asks first and says it cannot be undone`() {
        val title = ControlsCopy.deleteTitle(3)
        val body = ControlsCopy.deleteBody(3)
        assertEquals("Delete 3 saved checks?", title.en)
        assertTrue(body.en.contains("cannot be undone"))
        assertTrue(hasHindi(title) && hasHindi(body))
        assertEquals("Delete 1 saved check?", ControlsCopy.deleteTitle(1).en)
    }

    // ---- truth ------------------------------------------------------------------------------------------------------

    @Test fun `nothing in the inventory claims safety, compliance or a verdict`() {
        val everything = readings(
            requested = setOf(mic, notify, install), installUnknownAllowed = true,
            onlineVoiceInBuild = true, onlineVoiceSetUp = true, runnerOn = true, savedChecks = 3,
        )
        val words = (dev(everything) + everyday(everything)).flatMap { listOf(it.title.en, it.what.en, it.hint.en) } +
            listOf(ControlsCopy.pageIntro.en) + ControlGroup.entries.mapNotNull { ControlsCopy.groupFooter(it)?.en }
        val banned = Regex("\\b(safe|safety|compliant|certified|malicious|fraud|illegal|guarantee)", RegexOption.IGNORE_CASE)
        words.forEach { assertFalse(it, banned.containsMatchIn(it)) }
    }

    @Test fun `one home per control - the Setup sheet's handlers are the only ones for the VPN, watcher, Private DNS and Shizuku`() {
        val src = listOf("src/main/java/com/dpdpxray/app", "app/src/main/java/com/dpdpxray/app").map(::File).first { it.isDirectory }
        fun source(path: String) = File(src, path).readText()
        val controls = listOf("controls/ControlsUi.kt", "controls/ControlsReader.kt", "controls/ControlsInventory.kt").joinToString("\n") { source(it) }
        listOf("openVpnSettings", "openAccessibilitySettings", "openPrivateDnsSettings", "openShizuku", "requestShizuku", "VpnService").forEach {
            assertFalse("controls code must not handle $it itself", controls.contains(it))
        }
        // The controls are shown inside Home's Setup sheet, and the full page is built from that same sheet content.
        val home = source("ui/screens/HomeScreen.kt")
        assertTrue(home.contains("extra = { ControlsSheetGroups(vm, lang) }"))
        assertTrue(source("controls/ControlsUi.kt").contains("SetupContent("))
    }

    @Test fun `the full page has a stable route`() {
        assertEquals("controls", ControlsRoutes.ROUTE)
        assertEquals("Permissions & background", ControlsCopy.pageTitle.en)
    }

    private fun encode(s: XraySettings): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        SettingsCodec.write(s, object : SettingsSink {
            override fun putBoolean(key: String, value: Boolean) { map[key] = value }
            override fun putString(key: String, value: String) { map[key] = value }
            override fun putStringSet(key: String, value: Set<String>) { map[key] = value }
        })
        return map
    }
}
