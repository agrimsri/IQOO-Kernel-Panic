package com.dpdpxray.app.autoguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Static contract for the Auto-guard jobs and boot receiver, parsed from the source manifest. */
class AutoGuardManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val doc = run {
        val file = File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    private fun elements(tag: String): List<Element> = doc.getElementsByTagName(tag).let { n -> (0 until n.length).map { n.item(it) as Element } }
    private fun Element.a(name: String): String = getAttributeNS(ns, name)
    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    @Test fun `both job services are private and bindable only by the job scheduler`() {
        listOf(".autoguard.NewAppCheckJobService", ".autoguard.PhoneCheckupJobService").forEach { name ->
            val service = elements("service").single { it.a("name") == name }
            assertEquals(name, "false", service.a("exported"))
            assertEquals(name, "android.permission.BIND_JOB_SERVICE", service.a("permission"))
            assertTrue("$name must not be startable through an intent filter", service.children("intent-filter").isEmpty())
        }
    }

    @Test fun `the boot receiver is private and listens only for restart and own update`() {
        val receiver = elements("receiver").single { it.a("name") == ".autoguard.AutoGuardBootReceiver" }
        assertEquals("false", receiver.a("exported"))
        val actions = receiver.children("intent-filter").flatMap { it.children("action") }.map { it.a("name") }.toSet()
        assertEquals(setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"), actions)
    }

    @Test fun `persisted jobs have their permission and nothing more powerful is requested`() {
        val permissions = elements("uses-permission").map { it.a("name") }.toSet()
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in permissions)
        listOf(
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.BIND_DEVICE_ADMIN",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.SCHEDULE_EXACT_ALARM",
        ).forEach { assertFalse("$it must not be requested", it in permissions) }
    }
}
