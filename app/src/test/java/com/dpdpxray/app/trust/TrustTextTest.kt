package com.dpdpxray.app.trust

import com.dpdpxray.app.ai.ResultSource
import com.dpdpxray.app.capture.DnsForwarder
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.rules.ScoreCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Each sentence on "Why you can trust TraceVeil", checked against the code, manifest and rules it describes. */
class TrustTextTest {
    private val own = "com.dpdpxray.app"
    private val android = "http://schemas.android.com/apk/res/android"
    private val manifest = run {
        val file = File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }
    private val declared: List<String> = manifest.getElementsByTagName("uses-permission").let { n ->
        (0 until n.length).map { (n.item(it) as Element).getAttributeNS(android, "name") }
    }

    // ---- permissions ----------------------------------------------------------------------------------------------

    @Test
    fun `every permission in the manifest has a plain-language purpose`() {
        assertTrue(declared.isNotEmpty())
        declared.forEach { assertTrue("no purpose for $it", TrustText.purpose(it, own) != null) }
    }

    @Test
    fun `permissions the libraries add are described too`() {
        // Seen in the merged manifest of this build: AndroidX core and the Shizuku API add these.
        listOf("$own.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "moe.shizuku.manager.permission.API_V23").forEach {
            assertTrue(it, TrustText.purpose(it, own) != null)
        }
        // The AndroidX one is per package, so another package name is not described by accident.
        assertNull(TrustText.purpose("com.other.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", own))
    }

    @Test
    fun `an unknown permission is still listed, says its purpose is not described, and comes last`() {
        val lines = TrustText.permissionLines(
            listOf(HeldPermission("com.example.SOMETHING_NEW", true), HeldPermission("android.permission.INTERNET", true),
                HeldPermission("android.permission.RECORD_AUDIO", false), HeldPermission("android.permission.POST_NOTIFICATIONS", null)),
            own,
        )
        assertEquals(4, lines.size)
        val unknown = lines.last()
        assertEquals("com.example.SOMETHING_NEW", unknown.name)
        assertEquals("Purpose not described", unknown.purpose)
        assertEquals("SOMETHING_NEW", unknown.title)
        assertFalse(unknown.described)
        assertEquals(listOf("Granted", "Not granted", "Not reported"), lines.take(3).map { it.status })
    }

    @Test
    fun `the microphone purpose names both places it is used, and nothing else`() {
        val mic = TrustText.purpose("android.permission.RECORD_AUDIO", own)!!.second
        assertTrue(mic, mic.contains("Ask this audit") && mic.contains("Hey TraceVeil"))
        assertTrue(mic, mic.startsWith("Only when"))
    }

    // ---- what the map and this screen promise about the manifest ---------------------------------------------------

    @Test
    fun `TraceVeil holds no location permission, as the map says`() {
        assertTrue(declared.none { it.contains("LOCATION") })
    }

    @Test
    fun `Android backup is off in the manifest, matching the live reading`() {
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
        assertTrue(TrustText.staysOnPhone(false, false).first { it.title == "Android backup" }.line.startsWith("Off"))
        assertTrue(TrustText.staysOnPhone(false, null).first { it.title == "Android backup" }.line.contains("Not reported"))
    }

    // ---- stays on the phone ---------------------------------------------------------------------------------------

    @Test
    fun `online voice is described as off or on exactly as the live setting says`() {
        val off = TrustText.staysOnPhone(onlineVoiceOn = false, backupAllowed = false, onlineVoiceAvailable = true).first { it.title.startsWith("Online voice") }
        assertTrue(off.line, off.line.startsWith("Off"))
        val on = TrustText.staysOnPhone(onlineVoiceOn = true, backupAllowed = false, onlineVoiceAvailable = true).first { it.title.startsWith("Online voice") }
        assertTrue(on.line, on.line.startsWith("On") && on.line.contains("Sarvam AI") && on.line.contains("not your audio"))
        val path = TrustText.networkPaths(onlineVoiceOn = false, fallbackResolvers = DnsForwarder.FALLBACKS, onlineVoiceAvailable = true).first { it.sourceFile.endsWith("SarvamTts.kt") }
        assertTrue(path.line, path.line.contains("sends nothing"))
    }

    // ---- builds with and without the optional online voice ----------------------------------------------------------

    @Test
    fun `a build with the online voice lists it as a network path, in what stays on the phone and under Internet`() {
        val paths = TrustText.networkPaths(false, DnsForwarder.FALLBACKS, onlineVoiceAvailable = true).map { it.sourceFile }
        assertTrue(paths.toString(), paths.contains("voice/SarvamTts.kt"))
        assertTrue(TrustText.staysOnPhone(false, false, onlineVoiceAvailable = true).any { it.title == "Online voice (Sarvam AI)" })
        assertTrue(TrustText.purpose("android.permission.INTERNET", own, onlineVoiceAvailable = true)!!.second.contains("online voice"))
    }

    @Test
    fun `a build without the online voice never mentions it on the trust screen`() {
        listOf(true, false).forEach { on ->
            val paths = TrustText.networkPaths(on, DnsForwarder.FALLBACKS, onlineVoiceAvailable = false)
            assertTrue(paths.none { it.sourceFile.endsWith("SarvamTts.kt") })
            val words = (
                paths.flatMap { listOf(it.title, it.line) } +
                    TrustText.staysOnPhone(on, false, onlineVoiceAvailable = false).flatMap { listOf(it.title, it.line, it.detail.orEmpty()) } +
                    TrustText.permissionLines(listOf(HeldPermission("android.permission.INTERNET", true)), own, onlineVoiceAvailable = false).map { it.purpose }
                ).joinToString(" ").lowercase()
            assertFalse(words, words.contains("sarvam") || words.contains("online voice"))
        }
        // Nothing else changes: the other paths and claims are the same in both builds.
        assertEquals(
            TrustText.networkPaths(false, DnsForwarder.FALLBACKS, onlineVoiceAvailable = true).filterNot { it.sourceFile == "voice/SarvamTts.kt" },
            TrustText.networkPaths(false, DnsForwarder.FALLBACKS, onlineVoiceAvailable = false),
        )
        assertEquals(
            TrustText.staysOnPhone(false, false, onlineVoiceAvailable = true).filterNot { it.title.startsWith("Online voice") },
            TrustText.staysOnPhone(false, false, onlineVoiceAvailable = false),
        )
        val internet = TrustText.purpose("android.permission.INTERNET", own, onlineVoiceAvailable = false)!!.second
        assertTrue(internet, internet.contains("DNS lookups") && internet.contains("wake-word model download"))
    }

    @Test
    fun `the relay line names the relay's real fallback resolvers`() {
        val relay = TrustText.networkPaths(false, DnsForwarder.FALLBACKS).first()
        assertEquals("capture/DnsForwarder.kt", relay.sourceFile)
        assertTrue(relay.line, relay.line.contains("then to 8.8.8.8 or 1.1.1.1 if they fail"))
    }

    // ---- how findings are made -------------------------------------------------------------------------------------

    @Test
    fun `the finding labels on this screen are the labels results actually use`() {
        val text = TrustText.findings.joinToString("\n") { "${it.title} ${it.line} ${it.detail.orEmpty()}" }
        listOf(ResultSource.OBSERVED, ResultSource.AI_INFERRED, ResultSource.INFERRED, ResultSource.FALLBACK).forEach {
            assertTrue(it.label, text.contains("${it.label}"))
        }
        assertTrue(text.contains(ScoreBand.READY.label) && text.contains(ScoreBand.INCONCLUSIVE.label))
    }

    @Test
    fun `inferred findings really count half in the score`() {
        fun finding(type: EvidenceType) = Finding(CheckId.C3, Severity.HIGH, type, "t", "e", emptyList(), listOf(AuditPlan.ACCEPT), emptyList(), PenaltyBand.NONE)
        assertEquals(85, ScoreCalculator.compute(listOf(finding(EvidenceType.OBSERVED))).first)
        assertEquals(93, ScoreCalculator.compute(listOf(finding(EvidenceType.INFERRED))).first) // 100 - 7.5, rounded
        assertTrue(TrustText.findings.any { it.detail.orEmpty().contains("counts half in the score") })
    }

    @Test
    fun `too little evidence gives Inconclusive, as the screen says`() {
        val v0 = Finding(CheckId.V0, Severity.INFO, EvidenceType.OBSERVED, "t", "e", emptyList(), listOf(AuditPlan.ACCEPT), emptyList(), PenaltyBand.NONE)
        assertEquals(null to ScoreBand.INCONCLUSIVE, ScoreCalculator.compute(listOf(v0)))
    }

    // ---- formatting ------------------------------------------------------------------------------------------------

    @Test
    fun `byte counts are formatted and an unreported counter is never shown as zero`() {
        assertEquals("Not reported", TrustText.formatBytes(null))
        assertEquals("Not reported", TrustText.formatBytes(-1))
        assertEquals("0 B", TrustText.formatBytes(0))
        assertEquals("1023 B", TrustText.formatBytes(1023))
        assertEquals("1.5 KB", TrustText.formatBytes(1536))
        assertEquals("5.0 MB", TrustText.formatBytes(5L * 1024 * 1024))
        assertEquals("2.0 GB", TrustText.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `uptime reads naturally`() {
        assertEquals("0 min", TrustText.formatUptime(59_000))
        assertEquals("12 min", TrustText.formatUptime(12 * 60_000L))
        assertEquals("3 h 5 min", TrustText.formatUptime((3 * 60 + 5) * 60_000L))
        assertEquals("2 d 4 h", TrustText.formatUptime(((2 * 24 + 4) * 60 + 30) * 60_000L))
    }

    @Test
    fun `hashes are lower-case hex like sha256sum and apksigner print`() {
        assertEquals("00ff10ab", TrustText.hex(byteArrayOf(0, -1, 16, -85)))
    }

    @Test
    fun `the debug-key note appears only for Android's shared debug certificate`() {
        assertTrue(TrustText.isDebugCertificate("C=US,O=Android,CN=Android Debug"))
        assertFalse(TrustText.isDebugCertificate("CN=Kernel Panic,O=TraceVeil"))
        assertFalse(TrustText.isDebugCertificate(null))
    }

    // ---- navigation -------------------------------------------------------------------------------------------------

    @Test
    fun `the route is exactly trust and the self-check opens APK Guard for TraceVeil`() {
        assertEquals("trust", TrustRoutes.ROUTE)
        assertEquals("apkguard/app/com.dpdpxray.app", TrustRoutes.selfCheck(own))
    }
}
