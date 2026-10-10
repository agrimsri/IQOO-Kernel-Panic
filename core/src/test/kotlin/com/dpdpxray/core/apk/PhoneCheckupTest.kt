package com.dpdpxray.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCheckupTest {
    private val play = InstallOrigin(InstallSource.PLAY_STORE, InstallSources.GOOGLE_PLAY, "Google Play")
    private val apkFile = InstallOrigin(InstallSource.FILE, "com.android.packageinstaller")

    /** An app with every capability read and switched off, installed from Google Play. */
    private fun app(
        pkg: String,
        label: String? = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() },
        granted: Set<String>? = emptySet(),
        accessibility: Boolean? = false,
        notifications: Boolean? = false,
        overlay: Boolean? = false,
        installApps: Boolean? = false,
        admin: Boolean? = false,
        origin: InstallOrigin? = play,
    ) = CheckupFacts(pkg, label, granted, accessibility, notifications, overlay, installApps, admin, origin)

    private fun PhoneCheckupResult.section(area: CheckupArea) = sections.single { it.area == area }

    @Test fun `counts each area from what Android reports`() {
        val r = PhoneCheckup.evaluate(listOf(
            app("com.loan.fast", granted = setOf("android.permission.RECEIVE_SMS"), accessibility = true, origin = apkFile),
            app("com.chat.sms", granted = setOf("android.permission.READ_SMS", "android.permission.CAMERA")),
            app("com.photo.edit", granted = setOf("android.permission.CAMERA"), overlay = true),
            app("com.files.box", installApps = true, notifications = true, admin = true),
        ))
        assertEquals(4, r.appsChecked)
        // Listed by the name people see ("Fast" before "Sms"), not by package name.
        assertEquals(listOf("com.loan.fast", "com.chat.sms"), r.section(CheckupArea.SMS).apps.map { it.packageName })
        assertEquals(1, r.section(CheckupArea.SCREEN_CONTROL).count)
        assertEquals(1, r.section(CheckupArea.NOTIFICATIONS).count)
        assertEquals(1, r.section(CheckupArea.DRAW_OVER_APPS).count)
        assertEquals(1, r.section(CheckupArea.INSTALL_APPS).count)
        assertEquals(1, r.section(CheckupArea.DEVICE_ADMIN).count)
        assertEquals(listOf("com.loan.fast"), r.section(CheckupArea.OUTSIDE_STORE).apps.map { it.packageName })
        assertEquals(4, r.appsToReview)
        assertTrue(r.shouldNotify)
        assertEquals(CheckupArea.entries.toList(), r.sections.map { it.area })
    }

    @Test fun `only a granted SMS permission counts, a requested one does not`() {
        val r = PhoneCheckup.evaluate(listOf(app("com.a.b", granted = setOf("android.permission.SEND_SMS", "android.permission.READ_CONTACTS"))))
        assertEquals(0, r.section(CheckupArea.SMS).count)
        assertFalse(r.shouldNotify)
    }

    @Test fun `headline and notification match the plain summary`() {
        val r = PhoneCheckup.evaluate(listOf(
            app("com.a.one", granted = setOf("android.permission.READ_SMS")),
            app("com.a.two", granted = setOf("android.permission.RECEIVE_SMS")),
            app("com.a.three", accessibility = true),
        ))
        assertEquals("2 apps can read SMS and 1 can control your screen.", r.headline.en)
        assertEquals("Phone checkup", r.notificationTitle.en)
        assertEquals("2 apps can read SMS and 1 can control your screen. Review →", r.notificationText.en)
        assertEquals("Phone checkup: 2 apps can read SMS and 1 can control your screen. Review →", r.line(hindi = false))
    }

    @Test fun `singular wording and verb agreement`() {
        val r = PhoneCheckup.evaluate(listOf(app("com.a.one", admin = true)))
        assertEquals("1 app has device admin rights.", r.headline.en)
        assertEquals("1 ऐप के पास डिवाइस एडमिन अधिकार है।", r.headline.hi)
        val two = PhoneCheckup.evaluate(listOf(app("com.a.one", admin = true), app("com.a.two", admin = true)))
        assertEquals("2 apps have device admin rights.", two.headline.en)
        assertEquals("2 ऐप के पास डिवाइस एडमिन अधिकार हैं।", two.headline.hi)
    }

    @Test fun `long summaries keep the first three areas and count the rest`() {
        val r = PhoneCheckup.evaluate(listOf(
            app("com.a.one", granted = setOf("android.permission.READ_SMS"), accessibility = true, notifications = true, overlay = true, origin = apkFile),
        ))
        assertEquals(
            "1 app can read SMS, 1 can control your screen, 1 can read your notifications and 2 more things to review.",
            r.headline.en,
        )
        assertTrue(r.headline.hi, r.headline.hi.endsWith("देखने लायक 2 और बातें हैं।"))
    }

    @Test fun `hindi text is filled for every line and every area`() {
        val r = PhoneCheckup.evaluate(listOf(
            app("com.a.one", granted = setOf("android.permission.READ_SMS")),
            app("com.a.two", accessibility = true),
        ))
        assertEquals("1 ऐप SMS पढ़ सकता है और 1 ऐप आपकी स्क्रीन नियंत्रित कर सकता है।", r.headline.hi)
        assertEquals("फ़ोन जाँच", r.notificationTitle.hi)
        assertTrue(r.notificationText.hi.endsWith("देखें →"))
        assertTrue(r.line(hindi = true).startsWith("फ़ोन जाँच: "))
        val hindiScript = Regex("[\\u0900-\\u097F]")
        listOf(r.headline, r.notificationText, r.scope).forEach { assertTrue(it.hi, hindiScript.containsMatchIn(it.hi)) }
        CheckupArea.entries.forEach { area ->
            listOf(area.title, area.checks, area.review).forEach { assertTrue("${area.name}: ${it.hi}", hindiScript.containsMatchIn(it.hi) && it.en.isNotBlank()) }
        }
    }

    @Test fun `plural hindi agrees with the count`() {
        val r = PhoneCheckup.evaluate(listOf(
            app("com.a.one", granted = setOf("android.permission.READ_SMS")),
            app("com.a.two", granted = setOf("android.permission.READ_SMS")),
        ))
        assertEquals("2 ऐप SMS पढ़ सकते हैं।", r.headline.hi)
    }

    @Test fun `exactly one extra area is counted in the singular`() {
        val r = PhoneCheckup.evaluate(listOf(
            app("com.a.one", granted = setOf("android.permission.READ_SMS"), accessibility = true, notifications = true, overlay = true),
        ))
        assertEquals("1 app can read SMS, 1 can control your screen, 1 can read your notifications and 1 more thing to review.", r.headline.en)
        assertTrue(r.headline.hi, r.headline.hi.endsWith("देखने लायक 1 और बात है।"))
        assertEquals(1, r.appsToReview)
    }

    @Test fun `an app reported twice is counted once`() {
        val r = PhoneCheckup.evaluate(listOf(app("com.a.one", admin = true), app("com.a.one", admin = true)))
        assertEquals(1, r.appsChecked)
        assertEquals(1, r.section(CheckupArea.DEVICE_ADMIN).count)
    }

    @Test fun `one checked app is said in the singular`() {
        val r = PhoneCheckup.evaluate(listOf(app("com.a.one")))
        assertEquals("No app needs review in these checks (1 app checked).", r.headline.en)
        assertTrue(r.headline.hi, r.headline.hi.contains("1 ऐप जाँचा गया"))
    }

    @Test fun `a phone with nothing to review says so without calling it safe`() {
        val r = PhoneCheckup.evaluate(listOf(app("com.a.one"), app("com.a.two")))
        assertFalse(r.shouldNotify)
        assertEquals(0, r.appsToReview)
        assertEquals("No app needs review in these checks (2 apps checked).", r.headline.en)
        assertTrue(r.headline.hi.contains("2 ऐप"))
    }

    @Test fun `an empty phone is reported as nothing found to check`() {
        val r = PhoneCheckup.evaluate(emptyList())
        assertEquals(0, r.appsChecked)
        assertFalse(r.shouldNotify)
        assertEquals("No apps you installed were found to check.", r.headline.en)
        assertTrue(r.sections.all { it.count == 0 && it.unknown == 0 })
        assertNull(r.notRead)
    }

    @Test fun `unknown stays unknown and is never counted as zero`() {
        val r = PhoneCheckup.evaluate(listOf(
            app("com.a.one", granted = null, notifications = null, origin = InstallOrigin(InstallSource.UNKNOWN)),
            app("com.a.two", notifications = null),
        ))
        val sms = r.section(CheckupArea.SMS)
        assertEquals(0, sms.count); assertEquals(1, sms.unknown); assertEquals(1, sms.checked)
        assertTrue(sms.readable)
        val notes = r.section(CheckupArea.NOTIFICATIONS)
        assertEquals(2, notes.unknown); assertFalse(notes.readable)
        assertEquals(1, r.section(CheckupArea.OUTSIDE_STORE).unknown)
        assertEquals("No app needs review in the checks Android let TraceVeil read (2 apps checked).", r.headline.en)
        assertTrue(r.notRead!!.en.contains("Can read your notifications"))
        assertFalse(r.notRead!!.en.contains("SMS"))
    }

    @Test fun `install sources outside a known store are counted, stores and unknown are not`() {
        val r = PhoneCheckup.evaluate(listOf(
            app("com.a.file", origin = apkFile),
            app("com.a.other", origin = InstallOrigin(InstallSource.OTHER_APP, "com.some.downloader")),
            app("com.a.galaxy", origin = InstallOrigin(InstallSource.OTHER_STORE, "com.sec.android.app.samsungapps", "Samsung Galaxy Store")),
            app("com.a.play"),
            app("com.a.unknown", origin = InstallOrigin(InstallSource.UNKNOWN)),
            app("com.a.nothing", origin = null),
        ))
        val outside = r.section(CheckupArea.OUTSIDE_STORE)
        assertEquals(listOf("com.a.file", "com.a.other"), outside.apps.map { it.packageName })
        assertEquals(2, outside.unknown)
        assertEquals("2 apps came from outside a known app store.", r.headline.en)
    }

    @Test fun `apps are listed by name and fall back to the package name`() {
        val r = PhoneCheckup.evaluate(listOf(
            app("com.z.zeta", label = "zeta", admin = true),
            app("com.a.alpha", label = "Alpha", admin = true),
            app("com.m.blank", label = "  ", admin = true),
        ))
        assertEquals(listOf("Alpha", "com.m.blank", "zeta"), r.section(CheckupArea.DEVICE_ADMIN).apps.map { it.label })
    }

    @Test fun `scope says what was checked and what was not`() {
        val r = PhoneCheckup.evaluate(listOf(app("com.a.one")))
        listOf("SMS", "accessibility", "notification access", "display over other apps", "install unknown apps", "device admin", "installed from")
            .forEach { assertTrue(it, r.scope.en.contains(it)) }
        listOf("came with the phone", "TraceVeil itself", "camera", "what any app actually does", "not an antivirus")
            .forEach { assertTrue(it, r.scope.en.contains(it)) }
    }

    @Test fun `wording never judges an app`() {
        val all = PhoneCheckup.evaluate(listOf(
            app("com.a.one", granted = setOf("android.permission.READ_SMS"), accessibility = true, notifications = true, overlay = true,
                installApps = true, admin = true, origin = apkFile),
        ))
        val texts = buildList {
            listOf(all.headline, all.notificationTitle, all.notificationText, all.scope).forEach { add(it.en) }
            CheckupArea.entries.forEach { add(it.title.en); add(it.checks.en); add(it.review.en) }
            add(PhoneCheckup.evaluate(emptyList()).headline.en)
            add(PhoneCheckup.evaluate(listOf(app("com.b.c"))).headline.en)
        }
        val banned = listOf("safe", "malware", "malicious", "fraud", "dangerous", "virus-free", "clean")
        texts.forEach { t -> banned.forEach { word -> assertFalse("'$word' in: $t", t.contains(word, ignoreCase = true)) } }
    }
}
