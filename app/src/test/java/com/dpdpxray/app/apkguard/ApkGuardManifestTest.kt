package com.dpdpxray.app.apkguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The manifest offers TraceVeil for APK files; installing goes only through Android's own confirmed installer. */
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

    @Test fun `file managers that still send INSTALL_PACKAGE can choose TraceVeil too`() {
        val install = mainActivityFilters().single { "android.intent.action.INSTALL_PACKAGE" in it.attrs("action", "name") }
        assertTrue("android.intent.category.DEFAULT" in install.attrs("category", "name"))
        assertTrue(IncomingApk.APK_MIME in install.attrs("data", "mimeType"))
        assertEquals(listOf("content"), install.attrs("data", "scheme").filter { it.isNotEmpty() })
    }

    @Test fun `TraceVeil can only hand a file to Android's installer, which asks the person, and never installs by itself`() {
        val permissions = doc.documentElement.children("uses-permission").map { it.getAttributeNS(android, "name") }
        // "Install anyway" uses a PackageInstaller session; Android shows its own confirmation every time.
        assertTrue("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
        listOf(
            "android.permission.INSTALL_PACKAGES",
            "android.permission.DELETE_PACKAGES",
            "android.permission.REQUEST_DELETE_PACKAGES",
            "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
            "android.permission.ENFORCE_UPDATE_OWNERSHIP",
        ).forEach { assertFalse("$it must not be requested", it in permissions) }
    }

    @Test fun `the installer result receiver is private`() {
        val receiver = doc.documentElement.children("receiver").single { it.getAttributeNS(android, "name") == ".apkguard.InstallResultReceiver" }
        assertEquals("false", receiver.getAttributeNS(android, "exported"))
        assertTrue("reached only through its own PendingIntent", receiver.children("intent-filter").isEmpty())
    }

    @Test fun `a build without the install hand-off removes the permission`() {
        val file = File("src/noInstallHandoff/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/noInstallHandoff/AndroidManifest.xml")
        val overlay = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val removed = overlay.documentElement.children("uses-permission").single()
        assertEquals("android.permission.REQUEST_INSTALL_PACKAGES", removed.getAttributeNS(android, "name"))
        assertEquals("remove", removed.getAttributeNS("http://schemas.android.com/tools", "node"))
    }
}
