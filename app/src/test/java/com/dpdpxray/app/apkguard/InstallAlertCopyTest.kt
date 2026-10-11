package com.dpdpxray.app.apkguard

import com.dpdpxray.core.apk.ApkEvidence
import com.dpdpxray.core.apk.ApkRiskRules
import com.dpdpxray.core.apk.ApkSeverity
import com.dpdpxray.core.apk.ApkSignal
import com.dpdpxray.core.apk.Bilingual
import com.dpdpxray.core.apk.InstallHighlights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The after-install notification: what the automatic static check found, in one line, with a consent check offered. */
class InstallAlertCopyTest {
    private fun signal(id: String, severity: ApkSeverity) =
        ApkSignal(id, severity, ApkEvidence.OBSERVED, Bilingual("t", "त"), Bilingual("d", "द"), Bilingual("a", "अ"))

    private val sms = Bilingual("can read SMS", "SMS पढ़ सकता है")
    private val overlay = Bilingual("can draw over other apps", "दूसरे ऐप्स के ऊपर दिख सकता है")

    @Test fun `title counts what deserves attention and the text names it, with the SDKs found in the code`() {
        val summary = ApkRiskRules.summary(listOf(signal("a", ApkSeverity.WORTH_KNOWING), signal("b", ApkSeverity.WORTH_KNOWING), signal("c", ApkSeverity.WORTH_KNOWING)))
        val h = InstallHighlights(0, 3, listOf(sms, overlay), listOf("AppsFlyer", "Adjust", "Mixpanel", "Amplitude", "Meta (Facebook SDK)"))
        val alert = InstallAlertPolicy.alert("Fast Loan", summary, "com.fast.loan", h)
        assertEquals("New app: Fast Loan · 3 things worth knowing", alert.title)
        assertEquals("Can read SMS; can draw over other apps; 5 tracking SDKs in its code. Tap for details.", alert.text)
        assertTrue(alert.hindi, alert.hindi.startsWith("नया ऐप: Fast Loan · जानने लायक 3 बातें"))
        assertTrue(alert.hindi, alert.hindi.contains("इसके कोड में 5 ट्रैकिंग SDK"))
        assertEquals("Run a consent check", InstallAlertPolicy.CONSENT_CHECK_ACTION)
    }

    @Test fun `review-carefully counts win, and one SDK is singular`() {
        val summary = ApkRiskRules.summary(listOf(signal("sms_read", ApkSeverity.REVIEW_CAREFULLY), signal("x", ApkSeverity.WORTH_KNOWING)))
        val alert = InstallAlertPolicy.alert("Fast Loan", summary, "com.fast.loan", InstallHighlights(1, 1, listOf(sms), listOf("AppsFlyer")))
        assertEquals("New app: Fast Loan · 1 thing to review carefully", alert.title)
        assertEquals("Can read SMS; 1 tracking SDK in its code. Tap for details.", alert.text)
        assertTrue(alert.spokenEn.contains("1 thing to review carefully"))
    }

    @Test fun `code that was not read, or showed no tracking SDK, is simply not mentioned`() {
        val summary = ApkRiskRules.summary(listOf(signal("sms_read", ApkSeverity.REVIEW_CAREFULLY)))
        val notRead = InstallAlertPolicy.alert("X", summary, "com.x", InstallHighlights(1, 0, listOf(sms), null))
        val none = InstallAlertPolicy.alert("X", summary, "com.x", InstallHighlights(1, 0, listOf(sms), emptyList()))
        assertEquals("Can read SMS. Tap for details.", notRead.text)
        assertEquals(notRead.text, none.text)
        assertFalse(none.text.contains("0 tracking"))
    }

    @Test fun `with nothing to point out it still asks for a check and is not a safety guarantee`() {
        val alert = InstallAlertPolicy.alert("Notes", ApkRiskRules.summary(emptyList()), "org.notes", InstallHighlights(0, 0, emptyList(), emptyList()))
        assertEquals("New app: Notes · check it before you open it", alert.title)
        assertTrue(alert.text, alert.text.contains("not a safety guarantee"))
        assertTrue(alert.text.endsWith("Tap for details."))
    }

    @Test fun `tracking SDKs alone are enough to name`() {
        val alert = InstallAlertPolicy.alert("Notes", ApkRiskRules.summary(emptyList()), "org.notes", InstallHighlights(0, 0, emptyList(), listOf("Mixpanel", "Amplitude")))
        assertEquals("New app: Notes · check it before you open it", alert.title)
        assertEquals("2 tracking SDKs in its code. Tap for details.", alert.text)
    }

    @Test fun `the copy never judges the app`() {
        val summary = ApkRiskRules.summary(listOf(signal("sms_read", ApkSeverity.REVIEW_CAREFULLY)))
        val alert = InstallAlertPolicy.alert("A".repeat(200), summary, "com.x", InstallHighlights(1, 0, listOf(sms, overlay), listOf("AppsFlyer")))
        assertTrue(alert.title.length < 120)
        listOf(alert.title, alert.text, alert.spokenEn).forEach { text ->
            listOf("malicious", "malware", "is safe", "fraud", "spy").forEach { assertFalse("$it in $text", text.lowercase().contains(it)) }
        }
    }
}
