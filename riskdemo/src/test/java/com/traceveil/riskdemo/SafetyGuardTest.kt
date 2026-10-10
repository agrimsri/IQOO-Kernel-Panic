package com.traceveil.riskdemo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the README's promises: the app only *declares* risky capabilities, never uses them, and stays clearly labelled.
 * These are source checks, so they fail as soon as someone adds code that would make the demo do something real.
 */
class SafetyGuardTest {
    private val main = listOf(File("src/main"), File("riskdemo/src/main")).first { it.isDirectory }
    private val kotlin = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
    private val manifest = File(main, "AndroidManifest.xml").readText()
    private val strings = File(main, "res/values/strings.xml").readText()
    private val accessibilityConfig = File(main, "res/xml/riskdemo_accessibility.xml").readText()

    @Test
    fun `code never reads SMS or contacts, writes to a socket, draws overlays or acts on the screen`() {
        assertTrue("no Kotlin sources found under $main", kotlin.isNotBlank())
        val banned = listOf(
            "Telephony", "SmsManager", "ContactsContract", "ContentResolver", "contentResolver", "query(",
            "OutputStream", "outputStream", "HttpURLConnection", "URLConnection", "DatagramSocket", "send(",
            "WindowManager", "TYPE_APPLICATION_OVERLAY", "addView(",
            "DevicePolicyManager", "rootInActiveWindow", "performGlobalAction", "dispatchGesture", "performAction(",
            "PackageInstaller", "Runtime.getRuntime", "ProcessBuilder",
        )
        banned.forEach { assertFalse("main source must not use $it", kotlin.contains(it)) }
    }

    @Test
    fun `manifest declares the demo signals APK Guard looks for`() {
        listOf(
            "android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_CONTACTS",
            "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.INTERNET",
            "android.permission.BIND_ACCESSIBILITY_SERVICE", "android.accessibilityservice.AccessibilityService",
        ).forEach { assertTrue("manifest should declare $it", manifest.contains(it)) }
    }

    @Test
    fun `manifest declares nothing more powerful than the demo needs`() {
        listOf(
            "BIND_DEVICE_ADMIN", "DEVICE_ADMIN_ENABLED", "SEND_SMS", "RECEIVE_BOOT_COMPLETED", "BOOT_COMPLETED",
            "REQUEST_INSTALL_PACKAGES", "BIND_NOTIFICATION_LISTENER_SERVICE", "BIND_VPN_SERVICE", "<receiver", "<provider",
        ).forEach { assertFalse("manifest must not declare $it", manifest.contains(it)) }
    }

    @Test
    fun `accessibility service asks for no events, no screen content and only its own package`() {
        assertFalse(accessibilityConfig.contains("accessibilityEventTypes"))
        assertTrue(accessibilityConfig.contains("android:canRetrieveWindowContent=\"false\""))
        assertTrue(accessibilityConfig.contains("android:packageNames=\"com.traceveil.riskdemo\""))
        listOf("canPerformGestures", "canRequestFilterKeyEvents", "flagRequestFilterKeyEvents", "canTakeScreenshot")
            .forEach { assertFalse("accessibility config must not ask for $it", accessibilityConfig.contains(it)) }
        assertTrue(strings.contains("TraceVeil demo — does nothing"))
    }

    @Test
    fun `app is unmistakably labelled as a test`() {
        assertTrue(strings.contains("<string name=\"app_name\">TEST Challan (TraceVeil demo)</string>"))
        assertTrue(strings.contains("TEST APP for TraceVeil — does nothing harmful, sends no data"))
    }
}
