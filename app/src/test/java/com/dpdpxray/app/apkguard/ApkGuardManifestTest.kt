package com.dpdpxray.app.apkguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The manifest offers TraceVeil for APK files without ever gaining the power to install them. */
class ApkGuardManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val doc = run {
        val file = File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun mainActivityFilters(): List<Element> {
        val activities = doc.documentElement.children("activity")
        val main = activities.single { it.getAttributeNS(android, "name") == ".MainActivity" }
        return main.children("intent-filter")
    }

    private fun Element.attrs(tag: String, attr: String) = children(tag).map { it.getAttributeNS(android, attr) }

    @Test fun `APK files can be opened or shared to TraceVeil`() {
        val filters = mainActivityFilters()
        val view = filters.single { "android.intent.action.VIEW" in it.attrs("action", "name") }
        assertTrue("android.intent.category.DEFAULT" in view.attrs("category", "name"))
        assertTrue(IncomingApk.APK_MIME in view.attrs("data", "mimeType"))
        assertTrue("content" in view.attrs("data", "scheme"))
        val send = filters.single { "android.intent.action.SEND" in it.attrs("action", "name") }
        assertTrue("android.intent.category.DEFAULT" in send.attrs("category", "name"))
        assertTrue(IncomingApk.APK_MIME in send.attrs("data", "mimeType"))
        // A shared stream has no data URI, so a scheme here would stop the filter from matching.
        assertTrue(send.attrs("data", "scheme").all { it.isEmpty() })
    }

    @Test fun `TraceVeil never asks for the power to install packages`() {
        val permissions = doc.documentElement.children("uses-permission").map { it.getAttributeNS(android, "name") }
        assertFalse("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
        assertFalse("android.permission.INSTALL_PACKAGES" in permissions)
    }
}
