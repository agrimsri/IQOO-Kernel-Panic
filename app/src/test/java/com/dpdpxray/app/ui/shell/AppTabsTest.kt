package com.dpdpxray.app.ui.shell

import com.dpdpxray.app.apkguard.ApkGuardRoutes
import com.dpdpxray.app.quarantine.QuarantineRoutes
import com.dpdpxray.app.ui.AuditRoutes
import com.dpdpxray.app.ui.guide.OnboardingGate
import com.dpdpxray.app.voice.wake.AssistantFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTabsTest {
    @Test fun `tab routes are the existing destinations, not new copies`() {
        assertEquals(OnboardingGate.HOME, AppTab.HOME.route)
        assertEquals(ApkGuardRoutes.AUDIT_PICKER, AppTab.CHECK.route)
        assertEquals(AssistantFeatures.PICK_ROUTE, AppTab.CHECK.route)
        assertEquals(AppTab.CHECK, AppTabs.tabOf(ApkGuardRoutes.HUB))
        assertEquals(AppTab.CHECK, AppTabs.tabOf(AssistantFeatures.APK_GUARD_ROUTE))
    }

    @Test fun `top-level destinations show the bar and select their tab`() {
        assertEquals(AppTab.HOME, AppTabs.tabOf("home"))
        assertEquals(AppTab.CHECK, AppTabs.tabOf("pick?q={q}"))
        assertEquals(AppTab.CHECK, AppTabs.tabOf("pick"))
        assertEquals(AppTab.CHECK, AppTabs.tabOf("apkguard"))
        assertEquals(AppTab.REPORTS, AppTabs.tabOf(AppTabs.REPORTS))
        assertEquals(AppTab.MORE, AppTabs.tabOf(AppTabs.MORE))
    }

    @Test fun `flows, details and onboarding have no bar`() {
        listOf(
            null, "", OnboardingGate.ONBOARDING, "live", "results/{id}", "finding/{id}/{check}", "fixes/{id}", "report/{id}",
            AuditRoutes.VOICE, "lab", "camera", "settings", ApkGuardRoutes.APP, ApkGuardRoutes.FILE, QuarantineRoutes.ROUTE,
        ).forEach { assertNull("$it must not show the bar", AppTabs.tabOf(it)) }
    }

    @Test fun `four tabs in a fixed order with short labels`() {
        assertEquals(listOf("Home", "Check", "Reports", "More"), AppTab.entries.map { it.label })
        AppTab.entries.forEach { assert(it.label.length <= 10) { "${it.label} is too long for the bar" } }
    }
}
