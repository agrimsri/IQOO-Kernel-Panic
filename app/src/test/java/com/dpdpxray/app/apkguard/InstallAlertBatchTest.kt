package com.dpdpxray.app.apkguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallAlertBatchTest {
    @Test fun `several new apps share one notification that names them`() {
        val alert = InstallAlertPolicy.combined(listOf("Fast Loan", "Photo Edit", "Files Box"))
        assertEquals("3 new apps installed — check them before you open them", alert.title)
        assertEquals("Fast Loan, Photo Edit, Files Box. Tap to see them in APK Guard.", alert.text)
        assertTrue(alert.hindi, alert.hindi.startsWith("3 नए ऐप इंस्टॉल हुए"))
        assertTrue(alert.hindi.contains("Fast Loan"))
        assertTrue(alert.spokenEn.startsWith("3 new apps were installed."))
        assertTrue(alert.spokenHi.contains("3"))
    }

    @Test fun `a long batch names the first four and counts the rest`() {
        val alert = InstallAlertPolicy.combined((1..9).map { "App $it" })
        assertEquals("App 1, App 2, App 3, App 4 and 5 more. Tap to see them in APK Guard.", alert.text)
        assertTrue(alert.hindi, alert.hindi.contains("और 5 अन्य"))
    }

    @Test fun `the combined alert asks for a check and never judges`() {
        val alert = InstallAlertPolicy.combined(listOf("A".repeat(200), "com.x.app"))
        assertTrue(alert.text.length < 200)
        listOf(alert.title, alert.text, alert.spokenEn).forEach { text ->
            listOf("malicious", "malware", "is safe", "fraud").forEach { assertFalse("$it in $text", text.lowercase().contains(it)) }
        }
    }
}
