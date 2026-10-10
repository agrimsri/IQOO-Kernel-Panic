package com.dpdpxray.cli.samples

import com.dpdpxray.core.attribution.SdkAttribution
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.SdkAttributionExport
import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The committed docs/samples bundles: in step with the fixtures, synthetic-labelled and scored by the rules engine. */
class SampleBundlesTest {
    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private val dir = File(System.getProperty("traceveil.samplesDir") ?: "../docs/samples")

    private fun summary(sessions: List<com.dpdpxray.core.model.AuditSession>) =
        engine.evaluate(sessions).let { Triple(it.score, it.band, it.findings.map { f -> f.checkId }.toSet()) }

    @Test
    fun `committed sample files match the fixtures (run gradlew cli writeSamples after changing them)`() {
        for ((name, expected) in SampleBundles.files()) {
            val file = File(dir, name)
            assertTrue("missing ${file.path}: run ./gradlew :cli:writeSamples", file.isFile)
            assertEquals("${file.path} is stale: run ./gradlew :cli:writeSamples", expected, file.readText().replace("\r\n", "\n"))
        }
    }

    @Test
    fun `leakyshop samples keep the regression scores 21 and 100`() {
        assertEquals(
            Triple(21, ScoreBand.AT_RISK, setOf(CheckId.C1, CheckId.C2, CheckId.C3, CheckId.C4, CheckId.C10)),
            summary(SampleBundles.leaky().sessions),
        )
        assertEquals(Triple(100, ScoreBand.READY, emptySet<CheckId>()), summary(SampleBundles.fixed().sessions))
    }

    @Test
    fun `kept-data sample shows only consent-history-unknown and same-company findings`() {
        assertEquals(Triple(100, ScoreBand.READY, setOf(CheckId.U1, CheckId.S1)), summary(SampleBundles.keptData().sessions))
    }

    @Test
    fun `audit samples carry the SDK groups the core derives from their own sessions and findings, like a phone export`() {
        val attribution = SdkAttribution(TrackerCatalog.loadDefault(), FixCatalog.loadDefault())
        for (b in listOf(SampleBundles.leaky(), SampleBundles.fixed(), SampleBundles.keptData())) {
            assertEquals(SdkAttributionExport(groups = attribution.groups(b.result, b.sessions)), b.sdkAttribution)
        }
        val meta = SampleBundles.leaky().sdkAttribution!!.groups.first { it.name == "Meta (Facebook SDK)" }
        assertTrue(meta.checks.toString(), meta.checks.containsAll(listOf(CheckId.C1, CheckId.C2)))
        assertEquals(listOf("facebook"), meta.fixIds)
        assertTrue(meta.hosts.single().relations.contains(SdkAttribution.AFTER_REJECT))
        val fixedGroups = SampleBundles.fixed().sdkAttribution!!.groups
        assertEquals("only the app's own host, which the catalogue does not list", listOf(SdkAttribution.UNKNOWN_GROUP), fixedGroups.map { it.name })
    }

    @Test
    fun `every sample is marked synthetic and stores the result its sessions score`() {
        for (b in listOf(SampleBundles.leaky(), SampleBundles.fixed(), SampleBundles.keptData())) {
            assertTrue(b.note!!.startsWith("SYNTHETIC SAMPLE"))
            assertEquals(true, AuditBundles.metaOf(b).sample)
            assertEquals(engine.evaluate(b.sessions), b.result)
            assertEquals(null, b.networkReadings)
            assertEquals(b, AuditBundles.decode(AuditBundles.encode(b)))
        }
        val q = SampleBundles.quarantine()
        assertTrue(q.note!!.startsWith("SYNTHETIC SAMPLE"))
        assertEquals(q, AuditBundles.decodeQuarantine(AuditBundles.encodeQuarantine(q)))
        assertTrue(q.report.summary.dnsBypassSignal)
    }
}
