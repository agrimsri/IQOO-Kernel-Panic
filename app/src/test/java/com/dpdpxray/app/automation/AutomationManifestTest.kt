package com.dpdpxray.app.automation

import com.dpdpxray.core.automation.AutomationContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The runner's entry point is explicit-only, adb-only and leaves no trace in Recents. */
class AutomationManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val doc = run {
        val file = File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    private fun activity(): Element {
        val nodes = doc.documentElement.getElementsByTagName("activity")
        val name = ".automation.AutomationActivity"
        return (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttributeNS(android, "name") == name }
    }

    @Test fun `the contract names the declared activity`() {
        val pkg = AutomationContract.APP_PACKAGE
        assertEquals("$pkg/$pkg${activity().getAttributeNS(android, "name")}", AutomationContract.COMPONENT)
    }

    @Test fun `only the adb shell or the system may open it, by explicit component`() {
        val a = activity()
        assertEquals("true", a.getAttributeNS(android, "exported"))
        // DUMP is held by the shell user (adb) and the system; ordinary apps on the phone cannot hold it.
        assertEquals("android.permission.DUMP", a.getAttributeNS(android, "permission"))
        assertEquals("no intent filter: reachable only by its exact component name", 0, a.getElementsByTagName("intent-filter").length)
        assertEquals("true", a.getAttributeNS(android, "excludeFromRecents"))
        assertEquals("true", a.getAttributeNS(android, "noHistory"))
    }

    @Test fun `TraceVeil does not ask for DUMP itself`() {
        val nodes = doc.documentElement.getElementsByTagName("uses-permission")
        val names = (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(android, "name") }
        assertTrue(names.toString(), "android.permission.DUMP" !in names)
    }
}
