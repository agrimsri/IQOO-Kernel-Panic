package com.dpdpxray.app.autoguard

import com.dpdpxray.app.apkguard.ApkGuardRoutes
import com.dpdpxray.core.apk.CheckupArea
import com.dpdpxray.core.apk.InstallOrigin
import com.dpdpxray.core.apk.InstallSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialAccessTest {
    @Test fun `app ops modes map from AppOpsManager values`() {
        assertEquals(OpMode.ALLOWED, OpMode.of(0))
        assertEquals(OpMode.IGNORED, OpMode.of(1))
        assertEquals(OpMode.ERRORED, OpMode.of(2))
        assertEquals(OpMode.DEFAULT, OpMode.of(3))
        assertEquals(OpMode.FOREGROUND, OpMode.of(4))
        assertEquals(OpMode.UNKNOWN, OpMode.of(99))
    }

    @Test fun `display over other apps counts only when Android allows it`() {
        assertEquals(true, SpecialAccess.displayOverApps(declared = true, mode = OpMode.ALLOWED, permissionGranted = false))
        assertEquals(false, SpecialAccess.displayOverApps(declared = true, mode = OpMode.IGNORED, permissionGranted = true))
        assertEquals(false, SpecialAccess.displayOverApps(declared = true, mode = OpMode.ERRORED, permissionGranted = true))
        // Default mode falls back to the permission itself, as Android does.
        assertEquals(false, SpecialAccess.displayOverApps(declared = true, mode = OpMode.DEFAULT, permissionGranted = false))
        assertEquals(true, SpecialAccess.displayOverApps(declared = true, mode = OpMode.DEFAULT, permissionGranted = true))
        assertNull(SpecialAccess.displayOverApps(declared = true, mode = OpMode.DEFAULT, permissionGranted = null))
    }

    @Test fun `an app that does not declare the permission is a certain no, a failed reading is unknown`() {
        assertEquals(false, SpecialAccess.displayOverApps(declared = false, mode = null, permissionGranted = null))
        assertNull(SpecialAccess.displayOverApps(declared = true, mode = null, permissionGranted = true))
        assertNull(SpecialAccess.displayOverApps(declared = true, mode = OpMode.UNKNOWN, permissionGranted = true))
        assertEquals(false, SpecialAccess.installApps(eligible = false, mode = OpMode.ALLOWED))
        assertNull(SpecialAccess.installApps(eligible = true, mode = null))
    }

    @Test fun `install unknown apps counts only an explicit allow`() {
        assertEquals(true, SpecialAccess.installApps(eligible = true, mode = OpMode.ALLOWED))
        assertEquals(false, SpecialAccess.installApps(eligible = true, mode = OpMode.DEFAULT))
        assertEquals(false, SpecialAccess.installApps(eligible = true, mode = OpMode.ERRORED))
    }

    @Test fun `only old apps may install without declaring the permission`() {
        assertTrue(SpecialAccess.installEligible(declared = true, targetSdk = 35))
        assertFalse(SpecialAccess.installEligible(declared = false, targetSdk = 35))
        assertTrue(SpecialAccess.installEligible(declared = false, targetSdk = 25))
        assertFalse(SpecialAccess.installEligible(declared = false, targetSdk = null))
    }

    @Test fun `accessibility settings list gives package names`() {
        val value = "com.screen.reader/com.screen.reader.ReaderService:com.loan.fast/.Helper: :broken"
        assertEquals(setOf("com.screen.reader", "com.loan.fast"), SpecialAccess.packagesInComponentList(value))
        assertTrue(SpecialAccess.packagesInComponentList(null).isEmpty())
        assertTrue(SpecialAccess.packagesInComponentList("").isEmpty())
    }

    @Test fun `phone-wide lists are applied per app and an unreadable list stays unknown`() {
        val raw = RawCheckupApp("com.loan.fast", "Fast Loan", setOf("android.permission.READ_SMS"), true, false, InstallOrigin(InstallSource.FILE))
        val f = SpecialAccess.facts(raw, accessibilityOn = setOf("com.loan.fast"), notificationAccess = null, deviceAdmins = emptySet())
        assertEquals(true, f.accessibilityOn)
        assertNull(f.notificationAccessOn)
        assertEquals(false, f.deviceAdminActive)
        assertEquals(true, f.displayOverAppsAllowed)
        assertEquals(false, f.installAppsAllowed)
        assertEquals(InstallSource.FILE, f.origin?.source)
        assertEquals("Fast Loan", f.label)
    }

    // --- routes -------------------------------------------------------------------------------------------------

    @Test fun `notification taps open only the checkup or APK Guard`() {
        assertEquals("checkup", AutoGuardRoutes.CHECKUP)
        assertEquals("checkup", AutoGuardRoutes.route(AutoGuardRoutes.ACTION_OPEN_CHECKUP))
        assertEquals(ApkGuardRoutes.HUB, AutoGuardRoutes.route(AutoGuardRoutes.ACTION_OPEN_APK_GUARD))
        assertNull(AutoGuardRoutes.route("android.intent.action.MAIN"))
        assertNull(AutoGuardRoutes.route(null))
    }

    @Test fun `areas reviewed in a single Android screen have a settings link, per-app areas do not`() {
        listOf(CheckupArea.SCREEN_CONTROL, CheckupArea.NOTIFICATIONS, CheckupArea.DRAW_OVER_APPS, CheckupArea.INSTALL_APPS, CheckupArea.DEVICE_ADMIN)
            .forEach { assertNotNull(it.name, AutoGuardRoutes.settingsAction(it)) }
        assertNull(AutoGuardRoutes.settingsAction(CheckupArea.SMS))
        assertNull(AutoGuardRoutes.settingsAction(CheckupArea.OUTSIDE_STORE))
        assertEquals("android.settings.ACCESSIBILITY_SETTINGS", AutoGuardRoutes.settingsAction(CheckupArea.SCREEN_CONTROL))
    }
}
