package com.dpdpxray.app.apkguard

import com.dpdpxray.core.apk.ApkRiskRules
import com.dpdpxray.core.apk.DeclaredComponent
import com.dpdpxray.core.apk.InstallOrigin
import com.dpdpxray.core.apk.InstallSource
import com.dpdpxray.core.apk.ServiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkGuardMappingTest {
    private val a11y = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    private val admin = "android.permission.BIND_DEVICE_ADMIN"

    @Test fun `services and device admin receivers map to declared components with known on off state`() {
        val services = listOf(
            RawComponent("com.x.Reader", a11y),
            RawComponent("com.x.Listener", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
            RawComponent("com.x.Sync", null),
            RawComponent("com.x.NotAnAdmin", admin),
        )
        val receivers = listOf(RawComponent("com.x.Admin", admin), RawComponent("com.x.Boot", null), RawComponent("com.x.Odd", a11y))
        val enabled = mapOf(ServiceKind.ACCESSIBILITY to setOf("com.x.Reader"), ServiceKind.DEVICE_ADMIN to emptySet())
        val result = ApkFactsMapper.components(services, receivers, enabled)
        assertEquals(
            listOf(
                DeclaredComponent(ServiceKind.ACCESSIBILITY, "com.x.Reader", true),
                DeclaredComponent(ServiceKind.NOTIFICATION_LISTENER, "com.x.Listener", null),
                DeclaredComponent(ServiceKind.DEVICE_ADMIN, "com.x.Admin", false),
            ),
            result,
        )
    }

    @Test fun `granted permissions come only from the granted flag`() {
        val granted = ApkFactsMapper.granted(arrayOf("a", "b", "c"), intArrayOf(2, 0, 3), grantedFlag = 2)
        assertEquals(setOf("a", "c"), granted)
        assertEquals(emptySet<String>(), ApkFactsMapper.granted(null, null, 2))
        assertEquals(emptySet<String>(), ApkFactsMapper.granted(arrayOf("a"), null, 2))
    }

    @Test fun `certificate fingerprints are upper case sha-256 with readable short and full forms`() {
        val hex = ApkFactsMapper.sha256Hex("abc".toByteArray())
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", hex)
        assertEquals("BA:78:16:BF … F2:00:15:AD", ApkFactsMapper.shortFingerprint(hex))
        assertTrue(ApkFactsMapper.colonHex(hex).startsWith("BA:78:16:BF:8F"))
        assertEquals(32 * 3 - 1, ApkFactsMapper.colonHex(hex).length)
    }

    @Test fun `install origin label names the installer without judging it`() {
        assertEquals("Google Play (com.android.vending)", ApkFactsMapper.originLabel(InstallOrigin(InstallSource.PLAY_STORE, "com.android.vending", "Google Play")))
        assertEquals("APK file, not installed", ApkFactsMapper.originLabel(InstallOrigin(InstallSource.NOT_INSTALLED)))
        assertEquals("Not reported by Android", ApkFactsMapper.originLabel(InstallOrigin(InstallSource.UNKNOWN)))
        assertEquals("Another app: com.x.dropper", ApkFactsMapper.originLabel(InstallOrigin(InstallSource.OTHER_APP, "com.x.dropper")))
        assertEquals("APK file via com.google.android.packageinstaller", ApkFactsMapper.originLabel(InstallOrigin(InstallSource.FILE, "com.google.android.packageinstaller")))
    }

    @Test fun `file sizes are human readable`() {
        assertEquals("812 B", ApkFactsMapper.size(812))
        assertEquals("12.3 MB", ApkFactsMapper.size(12_300_000))
        assertEquals("48.0 kB", ApkFactsMapper.size(48_000))
        assertEquals("unknown size", ApkFactsMapper.size(null))
    }

    // --- routes and intents -----------------------------------------------------------------------------------------

    @Test fun `routes accept only real package names and opaque keys`() {
        assertEquals("apkguard", ApkGuardRoutes.HUB)
        assertEquals("apkguard/app/com.sbi.lotusintouch", ApkGuardRoutes.app("com.sbi.lotusintouch"))
        assertEquals("apkguard/file/3f2a-77", ApkGuardRoutes.file("3f2a-77"))
        listOf("", "settings", "../home", "com.x/../y", "com.x?y=1", "1com.x").forEach {
            assertThrows(IllegalArgumentException::class.java) { ApkGuardRoutes.app(it) }
        }
        listOf("", "a/b", "a?b").forEach { assertThrows(IllegalArgumentException::class.java) { ApkGuardRoutes.file(it) } }
    }

    @Test fun `audit picker opens pre-searched only for a real package name`() {
        assertEquals("pick?q=com.sbi.lotusintouch", ApkGuardRoutes.auditPicker("com.sbi.lotusintouch"))
        listOf("", "settings", "../home", "com.x?y=1", "com.x&q=2", "com x.y").forEach {
            assertThrows(IllegalArgumentException::class.java) { ApkGuardRoutes.auditPicker(it) }
        }
    }

    @Test fun `quarantine is offered only for an installed app other than TraceVeil`() {
        val own = "com.dpdpxray.app"
        assertTrue(ApkGuardRoutes.quarantineOffered(installed = true, packageName = "com.traceveil.riskdemo", ownPackage = own))
        assertFalse(ApkGuardRoutes.quarantineOffered(installed = false, packageName = "com.traceveil.riskdemo", ownPackage = own))
        assertFalse(ApkGuardRoutes.quarantineOffered(installed = true, packageName = own, ownPackage = own))
        assertFalse(ApkGuardRoutes.quarantineOffered(installed = true, packageName = "../home", ownPackage = own))
    }

    @Test fun `only content uris for APK files are accepted from other apps`() {
        val apk = IncomingApk.APK_MIME
        assertEquals("content://wa/1", IncomingApk.pickUri("android.intent.action.VIEW", apk, "content://wa/1", null))
        assertEquals("content://files/2", IncomingApk.pickUri("android.intent.action.SEND", apk, null, "content://files/2"))
        assertNull(IncomingApk.pickUri("android.intent.action.VIEW", apk, "file:///sdcard/x.apk", null))
        assertNull(IncomingApk.pickUri("android.intent.action.SEND", apk, null, "file:///sdcard/x.apk"))
        assertNull(IncomingApk.pickUri("android.intent.action.VIEW", "image/png", "content://x/1", null))
        assertNull(IncomingApk.pickUri("android.intent.action.MAIN", null, null, null))
        assertNull(IncomingApk.pickUri("android.intent.action.SEND", apk, null, null))
    }

    @Test fun `notification taps open only a valid package result`() {
        assertEquals("apkguard/app/com.new.app", IncomingApk.installedRoute(IncomingApk.ACTION_OPEN_INSTALLED, "com.new.app"))
        assertNull(IncomingApk.installedRoute(IncomingApk.ACTION_OPEN_INSTALLED, "../settings"))
        assertNull(IncomingApk.installedRoute("android.intent.action.MAIN", "com.new.app"))
    }

    // --- new-install alerts -----------------------------------------------------------------------------------------

    @Test fun `alerts fire for new installs only, never for updates or TraceVeil itself`() {
        val added = "android.intent.action.PACKAGE_ADDED"
        assertTrue(InstallAlertPolicy.shouldAlert(added, replacing = false, packageName = "com.new.app", ownPackage = "com.dpdpxray.app"))
        assertFalse(InstallAlertPolicy.shouldAlert(added, replacing = true, packageName = "com.new.app", ownPackage = "com.dpdpxray.app"))
        assertFalse(InstallAlertPolicy.shouldAlert(added, replacing = false, packageName = "com.dpdpxray.app", ownPackage = "com.dpdpxray.app"))
        assertFalse(InstallAlertPolicy.shouldAlert(added, replacing = false, packageName = null, ownPackage = "com.dpdpxray.app"))
        assertFalse(InstallAlertPolicy.shouldAlert("android.intent.action.PACKAGE_REMOVED", replacing = false, packageName = "com.new.app", ownPackage = "com.dpdpxray.app"))
    }

    @Test fun `alert text names the app, asks to check before opening and is never a verdict`() {
        val summary = ApkRiskRules.summary(emptyList())
        val alert = InstallAlertPolicy.alert("Fast Loan", summary)
        assertEquals("New app installed: Fast Loan — check it before you open it", alert.title)
        assertTrue(alert.text.contains("not a safety guarantee"))
        assertTrue(alert.hindi.contains("Fast Loan"))
        assertTrue(alert.spokenEn.startsWith("New app installed: Fast Loan."))
        listOf(alert.title, alert.text, alert.spokenEn).forEach {
            assertFalse(it, it.lowercase().contains("malicious"))
            assertFalse(it, it.lowercase().contains("is safe"))
        }
    }

    @Test fun `alert mentions how many things need careful review`() {
        val review = com.dpdpxray.core.apk.ApkSignal(
            "sms_read", com.dpdpxray.core.apk.ApkSeverity.REVIEW_CAREFULLY, com.dpdpxray.core.apk.ApkEvidence.OBSERVED,
            com.dpdpxray.core.apk.Bilingual("t", "त"), com.dpdpxray.core.apk.Bilingual("d", "द"), com.dpdpxray.core.apk.Bilingual("a", "अ"),
        )
        val alert = InstallAlertPolicy.alert("X", ApkRiskRules.summary(listOf(review, review)))
        assertTrue(alert.text, alert.text.startsWith("2 things to review carefully."))
        assertTrue(alert.spokenEn.contains("2 things to review carefully"))
        assertTrue(alert.spokenHi.contains("2"))
    }

    // --- permission profile model card ---------------------------------------------------------------------------

    @Test fun `the result screen adds one inferred model card and keeps every rule signal`() {
        val facts = com.dpdpxray.core.apk.ApkFacts(
            packageName = "com.traceveil.riskdemo", label = "TEST Challan", versionName = "1.0", versionCode = 1, targetSdk = 35,
            permissions = setOf("android.permission.INTERNET", "android.permission.READ_SMS", "android.permission.RECEIVE_SMS"),
            origin = InstallOrigin(InstallSource.NOT_INSTALLED),
        )
        val rules = ApkRiskRules.evaluate(facts)
        val shown = ApkGuardModel.withModelCard(rules)
        val cards = shown.signals.filter { it.id == com.dpdpxray.core.apk.ApkModelSignal.ID }
        assertEquals(1, cards.size)
        assertEquals(com.dpdpxray.core.apk.ApkEvidence.INFERRED, cards.single().evidence)
        assertTrue(cards.single().severity != com.dpdpxray.core.apk.ApkSeverity.REVIEW_CAREFULLY)
        assertEquals(rules.signals, shown.signals - cards.toSet())
        assertEquals(rules.summary, shown.summary)
        // Loaded once and reused.
        assertTrue(ApkGuardModel.permissionRisk() === ApkGuardModel.permissionRisk())
    }

    @Test fun `long or empty labels stay readable`() {
        assertEquals("New app installed: com.x.app — check it before you open it", InstallAlertPolicy.alert(" ", ApkRiskRules.summary(emptyList()), "com.x.app").title)
        assertTrue(InstallAlertPolicy.alert("A".repeat(200), ApkRiskRules.summary(emptyList())).title.length < 120)
    }

    @Test
    fun `a preload date before Android 1_0 is not shown as an install date`() {
        assertFalse(ApkInspector.plausibleInstallTime(3_456_000_000L)) // 10 Feb 1970, seen for vivo-preloaded PhonePe
        assertTrue(ApkInspector.plausibleInstallTime(1_791_500_000_000L)) // Oct 2026
    }
}
