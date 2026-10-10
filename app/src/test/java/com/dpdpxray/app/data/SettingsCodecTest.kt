package com.dpdpxray.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsCodecTest {
    private class MapKv(val map: MutableMap<String, Any> = mutableMapOf()) : SettingsKv, SettingsSink {
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String) = map[key] as? Set<String> ?: emptySet()
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun putString(key: String, value: String) { map[key] = value }
        override fun putStringSet(key: String, value: Set<String>) { map[key] = value }
    }

    @Test fun `fresh install starts onboarding with spoken guidance off in English`() {
        val s = SettingsCodec.read(MapKv())
        assertFalse(s.onboardingDone)
        assertFalse(s.spokenGuidance)
        assertEquals("en", s.guideLanguage)
        assertEquals(XraySettings(), s)
    }

    @Test fun `existing settings survive an upgrade that adds guide fields`() {
        val old = MapKv(mutableMapOf("stage_mode" to true, "fast_demo" to true, "auto_load_model" to false, "first_party" to setOf("shop.test")))
        val s = SettingsCodec.read(old)
        assertEquals(XraySettings(setOf("shop.test"), stageMode = true, fastDemo = true, autoLoadModel = false), s)
    }

    @Test fun `auto-guard checks are on for a fresh install and for an upgrade`() {
        val fresh = SettingsCodec.read(MapKv())
        assertEquals(true, fresh.autoCheckNewApps)
        assertEquals(true, fresh.weeklyCheckup)
        val upgraded = SettingsCodec.read(MapKv(mutableMapOf("stage_mode" to true, "speak_apk_guard_alerts" to true)))
        assertEquals(true, upgraded.autoCheckNewApps)
        assertEquals(true, upgraded.weeklyCheckup)
    }

    @Test fun `switching auto-guard checks off is remembered`() {
        val kv = MapKv()
        val next = XraySettings(autoCheckNewApps = false, weeklyCheckup = false)
        SettingsCodec.write(next, kv)
        assertEquals(next, SettingsCodec.read(kv))
        assertEquals(false, kv.map["auto_check_new_apps"])
        assertEquals(false, kv.map["weekly_checkup"])
    }

    @Test fun `update alerts for checked apps are on by default and switching them off is remembered`() {
        assertEquals(true, SettingsCodec.read(MapKv()).alertCheckedAppUpdates)
        assertEquals(true, SettingsCodec.read(MapKv(mutableMapOf("auto_check_new_apps" to true))).alertCheckedAppUpdates)
        val kv = MapKv()
        SettingsCodec.write(XraySettings(alertCheckedAppUpdates = false), kv)
        assertEquals(false, kv.map["alert_checked_app_updates"])
        assertEquals(false, SettingsCodec.read(kv).alertCheckedAppUpdates)
    }

    @Test fun `guide fields round trip`() {
        val kv = MapKv()
        val next = XraySettings(onboardingDone = true, spokenGuidance = true, guideLanguage = "hi", stageMode = true)
        SettingsCodec.write(next, kv)
        assertEquals(next, SettingsCodec.read(kv))
        assertEquals(true, kv.map["onboarding_done"])
        assertEquals(true, kv.map["spoken_guidance"])
        assertEquals("hi", kv.map["guide_language"])
    }

    @Test fun `installs from before modes existed read as developer mode`() {
        val old = MapKv(mutableMapOf("onboarding_done" to true, "stage_mode" to true, "guide_language" to "hi"))
        assertEquals(Audience.DEVELOPER, SettingsCodec.read(old).audience)
        assertEquals(Audience.DEVELOPER, SettingsCodec.read(MapKv()).audience)
        assertEquals(Audience.DEVELOPER, XraySettings().audience)
    }

    @Test fun `audience round trips and an unknown value falls back to developer`() {
        val kv = MapKv()
        SettingsCodec.write(XraySettings(audience = Audience.USER), kv)
        assertEquals("user", kv.map["audience"])
        assertEquals(Audience.USER, SettingsCodec.read(kv).audience)
        SettingsCodec.write(XraySettings(audience = Audience.DEVELOPER), kv)
        assertEquals("developer", kv.map["audience"])
        assertEquals(Audience.DEVELOPER, SettingsCodec.read(kv).audience)
        kv.map["audience"] = "somebody-else"
        assertEquals(Audience.DEVELOPER, SettingsCodec.read(kv).audience)
    }
}
