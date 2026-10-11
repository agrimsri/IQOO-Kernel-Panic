package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.cli.CliHarness.samples
import com.dpdpxray.cli.CliHarness.write
import com.dpdpxray.cli.CliHarness.writeText
import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.export.AuditBundleV1
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleApp
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.ConsentHistory
import com.dpdpxray.core.model.HistoryBounds
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.rules.BlockBlastReplay
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `traceveil-ci check` on a kept-data capture whose consent history is unknown: the result is a range, not
 * "inconclusive"; the person's answer stored in the bundle is re-applied and printed; and a Ready gate passes only when
 * the worst end of the range is in the top band.
 */
class ConsentHistoryCheckTest {
    @get:Rule val temp = TemporaryFolder()

    private val dir: File by lazy { samples(temp.newFolder("bundles")) }
    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private fun has(text: String, regex: String) = Regex(regex).containsMatchIn(text)

    private fun bundle(history: ConsentHistory = ConsentHistory.UNKNOWN): AuditBundleV1 {
        val sessions = BlockBlastReplay.sessions()
        return AuditBundles.audit(
            app = BundleApp(BlockBlastReplay.PACKAGE, BlockBlastReplay.LABEL), meta = JsonObject(emptyMap()), sessions = sessions,
            result = engine.evaluate(sessions, history), networkReadings = null, toolVersion = "test", exportedAt = "2026-10-11T08:00:00Z",
        )
    }

    private val relaxed = """{"failOn": ["C2"], "requireFreshState": false, "requirePlans": [], "allowNotEstablished": true}"""

    @Test fun `a consent-history range is not inconclusive and names both ends`() {
        write(dir, "range.json", bundle())
        writeText(dir, "relaxed.json", relaxed)
        val r = run(dir, "check", "range.json", "--policy", "relaxed.json")
        assertEquals(r.toString(), Exit.PASS, r.exit)
        assertTrue(r.out, r.out.contains("Score:    65–99 depending on consent history · 60–84 band either way (re-evaluated; same as stored in the bundle)"))
        assertTrue(r.out, r.out.contains("History:  consent history unknown: the app kept its data and showed no consent screen; " +
            "never agreed → 65 (60–84 band), agreed earlier → 99 (below the 85–100 band: it rests on the person's unchecked answer about consent history)"))
        assertTrue(r.out, has(r.out, "PASS\\s+allowInconclusive\\s+result is bounded by consent history, not inconclusive"))
        assertFalse(r.out, r.out.contains("Inconclusive (score not established)"))
    }

    @Test fun `a Ready gate fails a range whose best band is not the top band, and passes a top-band result`() {
        write(dir, "range.json", bundle())
        writeText(dir, "ready.json", relaxed.replace("}", ", \"requireReady\": true}"))
        val r = run(dir, "check", "range.json", "--policy", "ready.json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, has(r.out, "FAIL\\s+requireReady\\s+at best the 60–84 band \\(65–99 depending on consent history\\)"))

        val fixed = run(dir, "check", SampleBundles.FIXED, "--policy", "ready.json")
        assertTrue(fixed.out, has(fixed.out, "PASS\\s+requireReady\\s+100/100 is in the 85–100 band"))
        assertTrue(fixed.out, has(run(dir, "check", SampleBundles.FIXED).out, "OFF\\s+requireReady\\s+not required"))
    }

    @Test fun `the Ready gate passes a range only when its worst end is in the top band`() {
        val p = Policy(requireReady = true)
        fun outcome(b: HistoryBounds) = ReadyGate.outcome(AuditResult(emptyList(), null, ScoreBand.INCONCLUSIVE, b), p)
        assertEquals(RuleStatus.PASS, outcome(HistoryBounds(90, ScoreBand.READY, 100, ScoreBand.READY)).status)
        assertEquals(RuleStatus.FAIL, outcome(HistoryBounds(66, ScoreBand.NEEDS_WORK, 100, ScoreBand.READY)).status)
        assertEquals(RuleStatus.FAIL, outcome(HistoryBounds(30, ScoreBand.AT_RISK, 70, ScoreBand.NEEDS_WORK)).status)
        assertEquals(RuleStatus.FAIL, ReadyGate.outcome(AuditResult(emptyList(), null, ScoreBand.INCONCLUSIVE), p).status)
    }

    @Test fun `minScore passes a range only when even the never-agreed end meets it`() {
        write(dir, "range.json", bundle())
        writeText(dir, "min60.json", relaxed.replace("}", ", \"minScore\": 60}"))
        writeText(dir, "min85.json", relaxed.replace("}", ", \"minScore\": 85}"))
        val low = run(dir, "check", "range.json", "--policy", "min60.json")
        assertTrue(low.out, has(low.out, "PASS\\s+minScore\\s+even if the person never agreed, 65 is at least 60"))
        val high = run(dir, "check", "range.json", "--policy", "min85.json")
        assertEquals(high.toString(), Exit.VIOLATION, high.exit)
        assertTrue(high.out, has(high.out, "FAIL\\s+minScore\\s+the range 65–99 reaches 85 only if the person agreed earlier"))
    }

    @Test fun `an answer stored in the bundle is re-applied and printed`() {
        write(dir, "never.json", bundle(ConsentHistory.DECLARED_NEVER))
        writeText(dir, "relaxed.json", relaxed)
        val r = run(dir, "check", "never.json", "--policy", "relaxed.json")
        assertTrue(r.out, r.out.contains("Score:    65/100 · 60–84 band · based on the person's answer about consent history (re-evaluated; same as stored in the bundle)"))
        assertTrue(r.out, r.out.contains("History:  Consent history: declared by the person: never agreed (TraceVeil cannot check it)"))
        assertTrue(r.out, r.out.contains("C1     High      OBSERVED  Tracker hosts looked up although you say you never agreed [based on the person's answer]"))

        write(dir, "agreed.json", bundle(ConsentHistory.DECLARED_AGREED))
        val a = run(dir, "check", "agreed.json", "--policy", "relaxed.json")
        assertTrue(a.out, a.out.contains("Score:    99/100 · below the 85–100 band: it rests on the person's unchecked answer about consent history"))
        assertTrue(a.out, a.out.contains("Consent history: declared by the person: agreed earlier"))
    }

    @Test fun `the JSON report carries the bounds and the answer`() {
        write(dir, "range.json", bundle())
        val o = Json.parseToJsonElement(run(dir, "check", "range.json", "--format", "json").out).jsonObject
        val current = o["bundles"]!!.jsonArray.single().jsonObject["currentResult"]!!.jsonObject
        val b = current["historyBounds"]!!.jsonObject
        assertEquals(65, b["lowScore"]!!.jsonPrimitive.int)
        assertEquals(99, b["highScore"]!!.jsonPrimitive.int)
        assertEquals("NEEDS_WORK", b["bestBand"]!!.jsonPrimitive.content)
        assertEquals("UNKNOWN", b["answer"]!!.jsonPrimitive.content)
        assertEquals("Consent history: unknown (not answered)", b["consentHistory"]!!.jsonPrimitive.content)
    }
}
