package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.cli.CliHarness.samples
import com.dpdpxray.cli.CliHarness.write
import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.model.AuditPlan
import kotlinx.serialization.json.Json
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

/** `traceveil-ci diff old.json new.json`: a release-to-release regression check. */
class DiffCommandTest {
    @get:Rule val temp = TemporaryFolder()

    private val dir: File by lazy { samples(temp.newFolder("bundles")) }

    @Test
    fun `fixing the leaky build resolves every finding`() {
        val r = run(dir, "diff", SampleBundles.LEAKY, SampleBundles.FIXED)
        assertEquals(r.toString(), Exit.PASS, r.exit)
        assertTrue(r.out, r.out.contains("Score: 21/100 · below 60 → 100/100 · 85–100 band (+79)"))
        assertTrue(r.out, r.out.contains("Resolved (5)"))
        assertTrue(r.out, r.out.contains("  C2     Critical  OBSERVED  Trackers contacted after the user refused"))
        assertTrue(r.out, r.out.contains("New (0)\n  none"))
        assertTrue(r.out, r.out.contains("Unchanged (0)\n  none"))
        assertTrue(r.out, r.out.contains("Not established in the new capture (0)\n  none"))
        assertTrue(r.out, r.out.contains("Warning: the bundles are for different packages (com.leakyshop.demo → com.leakyshop.demo.fixed)"))
        assertTrue(r.out, r.out.contains("Result: no regression"))
    }

    @Test
    fun `going back to the leaky build is a regression`() {
        val r = run(dir, "diff", SampleBundles.FIXED, SampleBundles.LEAKY)
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("New (5)"))
        assertTrue(r.out, r.out.contains("Result: REGRESSION (exit 1)"))
        assertTrue(r.out, r.out.contains("  - new finding C2 Trackers contacted after the user refused (Critical)"))
        assertTrue(r.out, r.out.contains("  - score fell from 100 to 21"))
    }

    @Test
    fun `same build twice is unchanged and names evidence changes`() {
        val same = run(dir, "diff", SampleBundles.LEAKY, SampleBundles.LEAKY)
        assertEquals(Exit.PASS, same.exit)
        assertTrue(same.out, same.out.contains("Unchanged (5)"))
        assertTrue(same.out, same.out.contains("(+0)"))
        assertFalse(same.out, same.out.contains("different packages"))

        val quieterReject = SampleBundles.leaky().let { b ->
            b.copy(sessions = b.sessions.map { s ->
                if (s.plan == AuditPlan.REJECT) s.copy(netEvents = s.netEvents.filterNot { it.hostname == "launches.appsflyersdk.com" }) else s
            })
        }
        write(dir, "quieter.json", quieterReject)
        val r = run(dir, "diff", SampleBundles.LEAKY, "quieter.json")
        assertEquals(r.toString(), Exit.PASS, r.exit)
        assertTrue(r.out, Regex("C2 .*Trackers contacted after the user refused  \\[no longer AppsFlyer]").containsMatchIn(r.out))
    }

    @Test
    fun `new info-only findings are not a regression`() {
        val r = run(dir, "diff", SampleBundles.FIXED, SampleBundles.KEPT_DATA)
        assertEquals(r.toString(), Exit.PASS, r.exit)
        assertTrue(r.out, r.out.contains("New (2)"))
        assertTrue(r.out, r.out.contains("(+0)"))
    }

    @Test
    fun `an inconclusive new capture cannot show there was no regression`() {
        val quiet = SampleBundles.fixed().let { b -> b.copy(sessions = b.sessions.map { it.copy(netEvents = emptyList()) }) }
        write(dir, "quiet.json", quiet)
        val r = run(dir, "diff", SampleBundles.FIXED, "quiet.json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Score: 100/100 · 85–100 band → Inconclusive (score not established)\n"))
        assertTrue(r.out, r.out.contains("the new result is inconclusive"))
    }

    @Test
    fun `two inconclusive captures are still a regression`() {
        val quiet = SampleBundles.fixed().let { b -> b.copy(sessions = b.sessions.map { it.copy(netEvents = emptyList()) }) }
        write(dir, "quiet.json", quiet)
        write(dir, "quiet2.json", quiet)
        val r = run(dir, "diff", "quiet.json", "quiet2.json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Score: Inconclusive (score not established) → Inconclusive (score not established)"))
        assertTrue(r.out, r.out.contains("  - the new result is inconclusive"))
    }

    @Test
    fun `a finding the new capture could not observe is not resolved`() {
        val noReject = SampleBundles.leaky().let { b -> b.copy(sessions = b.sessions.filter { it.plan != AuditPlan.REJECT }) }
        write(dir, "no-reject.json", noReject)
        val r = run(dir, "diff", SampleBundles.LEAKY, "no-reject.json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Resolved (0)\n  none"))
        assertTrue(r.out, r.out.contains("Not established in the new capture (1)"))
        assertTrue(r.out, r.out.contains("  C2     Critical  OBSERVED  Trackers contacted after the user refused  [NOT ESTABLISHED: no Reject choice was recorded"))
        assertTrue(r.out, r.out.contains("  - C2 NOT ESTABLISHED in the new capture (no Reject choice was recorded"))

        val json = Json.parseToJsonElement(run(dir, "diff", SampleBundles.LEAKY, "no-reject.json", "--format", "json").out).jsonObject
        assertTrue(json["resolved"]!!.jsonArray.isEmpty())
        assertEquals("C2", json["notEstablished"]!!.jsonArray.single().jsonObject["checkId"]!!.jsonPrimitive.content)
        assertEquals("true", json["regression"]!!.jsonPrimitive.content)
    }

    @Test
    fun `json diff lists resolved, added and unchanged findings`() {
        val r = run(dir, "diff", SampleBundles.LEAKY, SampleBundles.FIXED, "--format", "json")
        assertEquals(Exit.PASS, r.exit)
        val root = Json.parseToJsonElement(r.out).jsonObject
        assertEquals("traceveil.ci.diff.v1", root["schema"]!!.jsonPrimitive.content)
        assertEquals(listOf("C2", "C1", "C3", "C4", "C10"), root["resolved"]!!.jsonArray.map { it.jsonObject["checkId"]!!.jsonPrimitive.content })
        assertTrue(root["added"]!!.jsonArray.isEmpty())
        assertTrue(root["unchanged"]!!.jsonArray.isEmpty())
        assertEquals("79", root["scoreDelta"]!!.jsonPrimitive.content)
        assertEquals("false", root["regression"]!!.jsonPrimitive.content)
        assertEquals("21", root["old"]!!.jsonObject["score"]!!.jsonPrimitive.content)
    }
}
