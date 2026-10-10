package com.dpdpxray.core.quarantine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineUiRulesTest {

    @Test
    fun `only Android's permission controllers count as permission dialogs`() {
        assertTrue(QuarantineUiRules.isPermissionController("com.android.permissioncontroller"))
        assertTrue(QuarantineUiRules.isPermissionController("com.google.android.permissioncontroller"))
        assertFalse(QuarantineUiRules.isPermissionController("com.google.android.packageinstaller"))
        assertFalse(QuarantineUiRules.isPermissionController("com.evil.permissioncontroller.clone"))
        assertFalse(QuarantineUiRules.isPermissionController(null))
    }

    @Test
    fun `dialog text prefers the permission message and is whitespace-collapsed and bounded`() {
        assertEquals(
            "Allow Demo to send and view SMS messages?",
            QuarantineUiRules.dialogText("  Allow Demo to send\nand view SMS messages? ", listOf("Allow", "Don't allow")),
        )
        assertEquals("Allow Demo to access your contacts? · Allow · Don't allow",
            QuarantineUiRules.dialogText(null, listOf("Allow Demo to access your contacts?", "Allow", "Allow", " ", "Don't allow")))
        assertNull(QuarantineUiRules.dialogText(null, listOf(" ", "")))
        val long = QuarantineUiRules.dialogText("x".repeat(2_000), emptyList())!!
        assertEquals(QuarantineUiRules.MAX_DIALOG_TEXT, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `a dialog names the target when its text contains the app label or package`() {
        assertTrue(QuarantineUiRules.namesTarget("Allow Risk Demo to send and view SMS messages?", "Risk Demo", "com.traceveil.riskdemo"))
        assertTrue(QuarantineUiRules.namesTarget("Allow risk demo to access contacts?", "Risk Demo", "com.traceveil.riskdemo"))
        assertFalse(QuarantineUiRules.namesTarget("Allow Maps to access this device's location?", "Risk Demo", "com.traceveil.riskdemo"))
        assertFalse(QuarantineUiRules.namesTarget("Allow X?", "X", "com.x")) // one-letter labels are too ambiguous
    }

    @Test
    fun `accessibility and display-over-other-apps settings are recognised by class or exact window title`() {
        assertEquals(SensitiveSettings.ACCESSIBILITY,
            QuarantineUiRules.settingsScreen("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity", null))
        assertEquals(SensitiveSettings.ACCESSIBILITY, QuarantineUiRules.settingsScreen("com.android.settings", "com.android.settings.SubSettings", "Accessibility"))
        assertEquals(SensitiveSettings.DISPLAY_OVER_OTHER_APPS,
            QuarantineUiRules.settingsScreen("com.android.settings", "com.android.settings.Settings\$OverlaySettingsActivity", null))
        assertEquals(SensitiveSettings.DISPLAY_OVER_OTHER_APPS,
            QuarantineUiRules.settingsScreen("com.android.settings", "com.android.settings.SubSettings", " Display over other apps "))
        assertEquals(SensitiveSettings.DISPLAY_OVER_OTHER_APPS, QuarantineUiRules.settingsScreen("com.android.settings", null, "Appear on top"))
    }

    @Test
    fun `other settings screens and other apps are not matched`() {
        assertNull(QuarantineUiRules.settingsScreen("com.android.settings", "com.android.settings.Settings", "Settings"))
        assertNull(QuarantineUiRules.settingsScreen("com.android.settings", "com.android.settings.SubSettings", "Accessibility shortcuts help"))
        assertNull(QuarantineUiRules.settingsScreen("com.traceveil.riskdemo", "com.traceveil.riskdemo.AccessibilityTeaserActivity", "Accessibility"))
        assertNull(QuarantineUiRules.settingsScreen(null, "Settings\$AccessibilitySettingsActivity", null))
    }
}
