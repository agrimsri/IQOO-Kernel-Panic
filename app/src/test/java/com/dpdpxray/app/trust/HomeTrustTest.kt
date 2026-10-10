package com.dpdpxray.app.trust

import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.GuideLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The "Why you can trust TraceVeil" card on both Home screens. Each fact it shows is tied here to the code it describes,
 * so a change that makes a fact untrue fails this test (as NetworkCodeScanTest does for the trust screen).
 */
class HomeTrustTest {
    private val own = "com.dpdpxray.app"
    private fun dir(vararg c: String) = c.map(::File).first { it.isDirectory }
    private val src = dir("src/main/java/com/dpdpxray/app", "app/src/main/java/com/dpdpxray/app")
    private fun source(path: String) = File(src, path).readText().replace("\r\n", "\n")
    private fun hasHindi(b: Bi) = b.hi.any { it in 'ऀ'..'ॿ' }

    @Test
    fun `four facts, in English and Hindi, all holding by default`() {
        val facts = HomeTrust.facts(onlineVoiceOn = false, backupAllowed = false)
        assertEquals(
            listOf("No account, no TraceVeil server", "VPN sees only the app you check", "Reads the screen only during a check", "Your results stay on this phone"),
            facts.map { it.title.en },
        )
        assertTrue(facts.all { it.holds })
        facts.forEach { f -> listOf(f.short, f.title, f.detail).forEach { assertTrue(it.en, it.en.isNotBlank() && hasHindi(it)) } }
        // Folded chips stay short enough for two per row on a phone.
        assertEquals(listOf("No account", "VPN: only the checked app", "Screen: only during checks", "Results stay here"), facts.map { it.short.en })
        assertTrue(facts.all { it.short.en.length <= 28 })
        assertEquals("TraceVeil पर भरोसा क्यों करें", HomeTrust.title.hi)
        assertEquals(TrustText.TITLE, HomeTrust.title.en)
    }

    @Test
    fun `the results fact is replaced by the exception when data can leave the phone`() {
        val voice = HomeTrust.facts(onlineVoiceOn = true, backupAllowed = false).last()
        assertFalse(voice.holds)
        assertTrue(voice.title.en, voice.title.en.contains("Online voice is on"))
        assertTrue(HomeTrust.facts(onlineVoiceOn = true, backupAllowed = true).last().title.en.contains("Online voice is on"))
        assertTrue(voice.detail.en.contains("Sarvam AI"))
        val backup = HomeTrust.facts(onlineVoiceOn = false, backupAllowed = true).last()
        assertFalse(backup.holds)
        assertTrue(backup.title.en.contains("Android backup"))
        // Not reported by Android: the claim is not made.
        val unknown = HomeTrust.facts(onlineVoiceOn = false, backupAllowed = null)
        assertTrue(unknown.none { it.title.en == "Your results stay on this phone" })
        assertEquals(4, unknown.size)
    }

    @Test
    fun `nothing on the card claims safety or a verdict`() {
        val all = (HomeTrust.facts(false, false) + HomeTrust.facts(true, true) + HomeTrust.facts(false, true) + HomeTrust.facts(false, null))
            .flatMap { listOf(it.short, it.title, it.detail) } +
            listOf(HomeTrust.title, HomeTrust.seeWhy, HomeTrust.permissionsLine(2, 4, 0), HomeTrust.permissionsLine(1, 4, 1))
        val en = all.joinToString(" ") { it.en }.lowercase()
        listOf("safe", "secure", "certified", "compliant", "guarantee", "malicious").forEach { assertFalse(it, en.contains(it)) }
        assertFalse(all.joinToString(" ") { it.hi }.contains("सुरक्षित"))
    }

    // ---- each fact against the code ---------------------------------------------------------------------------------

    @Test
    fun `VPN fact - the check VPN routes only allowed apps`() {
        val vpn = source("capture/XrayVpnService.kt")
        assertTrue(vpn.contains("builder.addAllowedApplication(pkg)"))
        assertFalse(vpn.contains("addDisallowedApplication"))
        // Google Play services joins only when the person includes it.
        assertTrue(vpn.contains("if (includeGms) runCatching { builder.addAllowedApplication(GMS_PACKAGE) }"))
    }

    @Test
    fun `screen fact - the watcher ignores every event unless a check or quarantine run is going`() {
        val svc = source("consent/ConsentAccessibilityService.kt")
        val body = svc.substringAfter("override fun onAccessibilityEvent(event: AccessibilityEvent) {").substringBefore("\n    }\n")
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("if (QuarantineBus.watchedPackage != null) quarantineWatcher.onEvent(event)", lines[0])
        assertEquals("val armed = ConsentBus.armedPackage.value ?: run { consentVisible = false; return }", lines[1])
    }

    @Test
    fun `results fact - audits live in private storage and Android backup is off`() {
        assertTrue(source("data/AuditRepository.kt").contains("File(context.filesDir, \"audits\")"))
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val app = doc.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS("http://schemas.android.com/apk/res/android", "allowBackup"))
    }

    @Test
    fun `account fact - no sign-in and no analytics, as the trust screen says`() {
        assertTrue(TrustText.staysOnPhone(false, false).any { it.title == "No account" })
        assertTrue(TrustText.NETWORK_NOTHING_ELSE.contains("no analytics, advertising or crash-reporting library"))
    }

    // ---- live permission line ---------------------------------------------------------------------------------------

    @Test
    fun `permission line counts the four user-visible permissions and never turns unknown into off`() {
        val held = listOf(
            HeldPermission("android.permission.INTERNET", true),
            HeldPermission("android.permission.RECORD_AUDIO", false),
            HeldPermission("android.permission.POST_NOTIFICATIONS", true),
        )
        val s = HomeTrust.permissionSummary(held, vpnAllowed = true, watcherOn = false)
        assertEquals(listOf("Microphone", "Notifications", "Check VPN", "Consent watcher"), s.items.map { it.label.en })
        assertEquals(listOf(false, true, true, false), s.items.map { it.on })
        assertEquals(2, s.on)
        assertEquals(4, s.total)
        assertEquals("2 of 4 permissions on", s.line.en)
        assertEquals("4 में से 2 अनुमतियाँ चालू", s.line.hi)

        val unknown = HomeTrust.permissionSummary(listOf(HeldPermission("android.permission.RECORD_AUDIO", null)), vpnAllowed = false, watcherOn = true)
        // Notifications not requested on this install: left out, not counted as off.
        assertEquals(listOf("Microphone", "Check VPN", "Consent watcher"), unknown.items.map { it.label.en })
        assertEquals("1 of 3 permissions on · 1 not reported", unknown.line.en)
        assertTrue(hasHindi(unknown.line))
    }

    @Test
    fun `the card opens the existing trust screen route`() {
        assertEquals("trust", TrustRoutes.ROUTE)
        assertEquals("see why", HomeTrust.seeWhy.en)
        assertTrue(own.isNotEmpty())
    }
}
