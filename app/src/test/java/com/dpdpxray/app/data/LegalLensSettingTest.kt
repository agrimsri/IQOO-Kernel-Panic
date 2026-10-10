package com.dpdpxray.app.data

import com.dpdpxray.app.report.AuditJsonExport
import com.dpdpxray.app.report.HtmlExporter
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.law.LegalProfile
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Settings → Legal lens: automatic from the device region, a pinned choice, and the lens in every export. */
class LegalLensSettingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `automatic follows the device region, else the global baseline`() {
        assertEquals(LensChoice(LegalProfile.INDIA, true, "IN", null), LegalLensSetting.resolve("", "IN"))
        assertEquals(LegalProfile.EU, LegalLensSetting.resolve("", "fr").profile)
        assertEquals(LegalProfile.UK, LegalLensSetting.resolve("", "GB").profile)
        assertEquals(LegalProfile.GLOBAL_BASELINE, LegalLensSetting.resolve("", "").profile)
        assertEquals(LegalProfile.GLOBAL_BASELINE, LegalLensSetting.resolve("", null).profile)
        val us = LegalLensSetting.resolve("", "US")
        assertEquals(LegalProfile.GLOBAL_BASELINE, us.profile)
        assertTrue(us.hint!!.contains("COPPA"))
        assertEquals("Automatic: India (device region IN)", LegalLensSetting.resolve("", "IN").describe())
        assertEquals("Automatic: Global baseline (device region unknown)", LegalLensSetting.resolve("", "").describe())
    }

    @Test
    fun `a chosen lens wins over the region and an unknown stored value falls back to automatic`() {
        val eu = LegalLensSetting.resolve("EU", "IN")
        assertEquals(LegalProfile.EU, eu.profile)
        assertFalse(eu.automatic)
        assertNull(eu.hint)
        assertEquals("EU / EEA (chosen in Settings)", eu.describe())
        assertEquals(LegalProfile.US_CALIFORNIA, LegalLensSetting.resolve("US_CALIFORNIA", "US").profile)
        val unknown = LegalLensSetting.resolve("MARS", "BR")
        assertTrue(unknown.automatic)
        assertEquals(LegalProfile.BRAZIL, unknown.profile)
    }

    @Test
    fun `the lens setting survives the settings codec and defaults to automatic`() {
        val map = mutableMapOf<String, Any>()
        val kv = object : SettingsKv, SettingsSink {
            override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
            override fun getString(key: String, default: String) = map[key] as? String ?: default
            @Suppress("UNCHECKED_CAST")
            override fun getStringSet(key: String) = map[key] as? Set<String> ?: emptySet()
            override fun putBoolean(key: String, value: Boolean) { map[key] = value }
            override fun putString(key: String, value: String) { map[key] = value }
            override fun putStringSet(key: String, value: Set<String>) { map[key] = value }
        }
        assertEquals(LegalLensSetting.AUTOMATIC, SettingsCodec.read(kv).legalProfile)
        SettingsCodec.write(XraySettings(legalProfile = "SINGAPORE"), kv)
        assertEquals("SINGAPORE", map["legal_profile"])
        assertEquals("SINGAPORE", SettingsCodec.read(kv).legalProfile)
    }

    @Test
    fun `exports carry the lens id and the lens's own law`() {
        val sessions = SampleAudit.sessions(1_791_590_915_883L)
        val catalog = TrackerCatalog.loadDefault()
        val result = RulesEngine(catalog).evaluate(sessions)
        val meta = AuditMeta("audit-1", sessions.first().targetPackage, sessions.first().targetLabel, 1L, result.score, result.band,
            result.findings.size, sessions.map { it.plan.label })
        val bundle = AuditBundle(meta, sessions, result, temp.root, emptyList())

        val json = AuditBundles.encode(AuditJsonExport.build(bundle, "1.1.0", legalProfile = LegalProfile.EU.name))
        assertTrue(json.contains("\"legalProfile\": \"EU\""))
        assertEquals("EU", AuditBundles.decode(AuditJsonExport.write(bundle, "1.1.0", "EU").readText()).legalProfile)
        assertFalse(AuditBundles.encode(AuditJsonExport.build(bundle, "1.1.0")).contains("legalProfile"))

        val citations = CitationService(DpdpCorpus.loadDefault())
        val fixes = FixCatalog.loadDefault().fixesFor(result, catalog)
        val html = HtmlExporter.build(result, sessions, null, true, fixes, citations, catalog, profile = LegalProfile.UK)
        assertTrue(html.contains("data-lens=\"UK\""))
        assertFalse(html.contains("crore"))
        val md = MarkdownReport.build(result, sessions, fixes, citations, LegalProfile.US_CALIFORNIA)
        assertTrue(md.contains("(`US_CALIFORNIA`)"))
        assertFalse(md.contains("₹"))
    }
}
