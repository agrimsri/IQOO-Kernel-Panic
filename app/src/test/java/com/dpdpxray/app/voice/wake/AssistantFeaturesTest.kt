package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantFeaturesTest {
    @Test fun `static shortcut ids map to their feature`() {
        assertEquals(AssistantFeature.CHECK_APP, AssistantFeatures.parse("check_app"))
        assertEquals(AssistantFeature.CHECK_APK, AssistantFeatures.parse("check_apk"))
        assertEquals(AssistantFeature.ASK_LAST_CHECK, AssistantFeatures.parse("ask_last_check"))
    }

    @Test fun `spoken feature text from the assistant maps to a feature`() {
        assertEquals(AssistantFeature.CHECK_APP, AssistantFeatures.parse("check an app"))
        assertEquals(AssistantFeature.CHECK_APP, AssistantFeatures.parse("Audit an app"))
        assertEquals(AssistantFeature.CHECK_APK, AssistantFeatures.parse("check an APK file"))
        assertEquals(AssistantFeature.CHECK_APK, AssistantFeatures.parse("scan apk"))
        assertEquals(AssistantFeature.ASK_LAST_CHECK, AssistantFeatures.parse("ask about my last check"))
        assertEquals(AssistantFeature.ASK_LAST_CHECK, AssistantFeatures.parse("my latest audit"))
    }

    @Test fun `unknown or empty text opens nothing special`() {
        assertNull(AssistantFeatures.parse(null))
        assertNull(AssistantFeatures.parse(""))
        assertNull(AssistantFeatures.parse("order a pizza"))
        assertNull(AssistantFeatures.parse("settings"))
    }

    @Test fun `check an app goes to the app picker`() {
        assertEquals(AssistantRoute.Navigate("pick"), AssistantFeatures.route(AssistantFeature.CHECK_APP, latestAuditId = null, apkRouteAvailable = false))
    }

    @Test fun `check an apk uses the apk route only when this build has it`() {
        assertEquals(AssistantRoute.Navigate("apkguard"), AssistantFeatures.route(AssistantFeature.CHECK_APK, null, apkRouteAvailable = true))
        val missing = AssistantFeatures.route(AssistantFeature.CHECK_APK, null, apkRouteAvailable = false)
        assertEquals(AssistantRoute.Notice::class, missing::class)
    }

    @Test fun `ask about my last check opens the copilot for the newest saved audit`() {
        assertEquals(AssistantRoute.Navigate("voice/audit-1791595268053"), AssistantFeatures.route(AssistantFeature.ASK_LAST_CHECK, "audit-1791595268053", false))
    }

    @Test fun `no saved audit or an unsafe id gives a notice instead of a crash`() {
        assertEquals(AssistantRoute.Notice::class, AssistantFeatures.route(AssistantFeature.ASK_LAST_CHECK, null, false)::class)
        assertEquals(AssistantRoute.Notice::class, AssistantFeatures.route(AssistantFeature.ASK_LAST_CHECK, "../settings", false)::class)
    }
}
