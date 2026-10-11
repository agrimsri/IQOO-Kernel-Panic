package com.dpdpxray.core.apk

import com.dpdpxray.core.model.Category
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallHighlightsTest {
    private fun p(name: String) = "android.permission.$name"
    private val play = InstallOrigin(InstallSource.PLAY_STORE, "com.android.vending", "Google Play")

    private fun report(vararg permissions: String) = ApkRiskRules.evaluate(
        ApkFacts(
            packageName = "com.fast.loan", label = "Fast Loan", versionName = "1", versionCode = 1, targetSdk = 35,
            permissions = permissions.map(::p).toSet(), origin = play, certSha256 = listOf("AB".repeat(32)),
            signerSubjects = listOf("CN=Fast Loan"), hasLauncherIcon = true,
        ),
    )

    private val categories = mapOf(
        "AppsFlyer" to Category.ATTRIBUTION, "Meta (Facebook SDK)" to Category.ADVERTISING,
        "Firebase Crashlytics" to Category.CRASH_REPORTING, "Mixpanel" to Category.ANALYTICS,
    )

    @Test fun `review-carefully capabilities come first, in short words`() {
        val h = InstallHighlights.of(report("READ_SMS", "SYSTEM_ALERT_WINDOW", "READ_CONTACTS"), null, categories::get)
        assertTrue(h.reviewCarefully >= 1)
        assertEquals("can read SMS", h.capabilities.first().en)
        assertEquals("SMS पढ़ सकता है", h.capabilities.first().hi)
        assertTrue(h.capabilities.size <= InstallHighlights.MAX_CAPABILITIES)
    }

    @Test fun `plain facts never become highlights`() {
        val h = InstallHighlights.of(report("QUERY_ALL_PACKAGES"), null, categories::get)
        assertEquals(0, h.reviewCarefully)
        assertEquals(0, h.worthKnowing)
        assertTrue(h.capabilities.isEmpty())
    }

    @Test fun `only tracking SDKs found in the code are counted, crash reporting is not`() {
        val found = mapOf(
            "AppsFlyer" to listOf("com.appsflyer"), "Meta (Facebook SDK)" to listOf("com.facebook.appevents"),
            "Firebase Crashlytics" to listOf("com.google.firebase.crashlytics"), "Unlisted" to listOf("x.y"),
        )
        val h = InstallHighlights.of(report(), found, categories::get)
        assertEquals(listOf("AppsFlyer", "Meta (Facebook SDK)"), h.trackingSdks)
    }

    @Test fun `code that was not read says nothing about SDKs, and none found is an empty list`() {
        assertNull(InstallHighlights.of(report(), null, categories::get).trackingSdks)
        assertEquals(emptyList<String>(), InstallHighlights.of(report(), emptyMap(), categories::get).trackingSdks)
    }

    @Test fun `every bundled code signature maps to a catalogue category`() {
        val catalog = TrackerCatalog.loadDefault()
        SdkCodeSignatures.default.entries.forEach { s ->
            assertNotNull("${s.entity} is not in trackers.json", catalog.entryFor(s.entity))
            assertNotNull(InstallHighlights.catalogueCategory(s.entity))
        }
        assertTrue(InstallHighlights.catalogueCategory("AppsFlyer")!!.isTracking)
        assertFalse(InstallHighlights.catalogueCategory("Firebase Crashlytics")!!.isTracking)
    }

    @Test fun `every signal id the rules can raise above info has a short phrase`() {
        val everything = report(
            "READ_SMS", "SEND_SMS", "READ_CALL_LOG", "CALL_PHONE", "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES",
            "READ_CONTACTS", "ACCESS_FINE_LOCATION", "CAMERA", "MANAGE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
        )
        everything.signals.filter { it.severity != ApkSeverity.INFO }.forEach { s ->
            val phrase = InstallHighlights.phrase(s)
            assertTrue("${s.id}: ${phrase.en}", phrase.en.length <= 48)
            assertTrue(s.id, phrase.hi.isNotBlank())
            assertFalse(s.id, phrase.en.first().isUpperCase())
        }
    }

    @Test fun `an unknown signal falls back to its own title, lower-cased`() {
        val s = ApkSignal(
            "something_new", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
            Bilingual("Does a new thing", "नया काम करता है"), Bilingual("d", "द"), Bilingual("a", "अ"),
        )
        assertEquals("does a new thing", InstallHighlights.phrase(s).en)
        assertEquals("नया काम करता है", InstallHighlights.phrase(s).hi)
    }
}
