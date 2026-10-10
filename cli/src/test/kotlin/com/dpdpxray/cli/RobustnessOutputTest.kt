package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.cli.CliHarness.samples
import com.dpdpxray.cli.CliHarness.write
import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The robustness interval in `traceveil-ci check`: one text line and a "robustness" object, computed from the current result. */
class RobustnessOutputTest {
    @get:Rule val temp = TemporaryFolder()

    private val dir: File by lazy { samples(temp.newFolder("bundles")) }

    @Test
    fun `leaky sample prints its range and the band that stays`() {
        val r = run(dir, "check", SampleBundles.LEAKY)
        assertTrue(r.out, r.out.contains("Range:    AI and documentation readings (INFERRED: C10) can move this between 17 and 25 · the band stays below 60\n"))
        val fixed = run(dir, "check", SampleBundles.FIXED)
        assertTrue(fixed.out, fixed.out.contains("Range:    fixed: no INFERRED finding carries points, so AI and documentation readings cannot move this score\n"))
    }

    @Test
    fun `json carries the robustness object next to the current result`() {
        val r = run(dir, "check", SampleBundles.LEAKY, SampleBundles.FIXED, "--format", "json")
        val (l, f) = Json.parseToJsonElement(r.out).jsonObject["bundles"]!!.jsonArray.map { it.jsonObject }
        val lr = l["robustness"]!!.jsonObject
        assertEquals("21", lr["score"]!!.jsonPrimitive.content)
        assertEquals("17", lr["worstCase"]!!.jsonPrimitive.content)
        assertEquals("25", lr["bestCase"]!!.jsonPrimitive.content)
        assertEquals("AT_RISK", lr["worstBand"]!!.jsonPrimitive.content)
        assertEquals("AT_RISK", lr["bestBand"]!!.jsonPrimitive.content)
        assertEquals("true", lr["bandStable"]!!.jsonPrimitive.content)
        assertEquals("true", lr["movable"]!!.jsonPrimitive.content)
        assertEquals(listOf("C10"), lr["inferred"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(lr["meaning"]!!.jsonPrimitive.content.contains("never changes the score"))
        val fr = f["robustness"]!!.jsonObject
        assertEquals(listOf("100", "100", "false"), listOf("worstCase", "bestCase", "movable").map { fr[it]!!.jsonPrimitive.content })
    }

    @Test
    fun `a band that could change names both ends, and an inconclusive result has no range`() {
        val kidsScreen = ScreenAudit(
            isConsentScreen = true, preTicked = false, rejectOption = RejectOption.VISIBLE, purposesCount = 1, separateChoices = true,
            itemisedData = true, purposeStated = true, withdrawalInfo = true, languageOptions = listOf("English", "Hindi"),
            childAudienceSignals = true, source = AuditSource.MERGED,
            observedFields = setOf("preTicked", "rejectOption", "separateChoices", "itemisedData", "purposeStated", "withdrawalInfo", "languageOptions"),
        )
        val kids = SampleBundles.fixed().let { b ->
            // A tracker looked up only after Accept, and a screen review that reads a child audience: C9 INFERRED alone.
            b.copy(sessions = b.sessions.map { s ->
                val after = if (s.plan == AuditPlan.ACCEPT) listOf(NetEvent(s.netEvents.last().tNanos + 1_000_000_000L, "graph.facebook.com")) else emptyList()
                s.copy(screenAudit = kidsScreen, netEvents = s.netEvents + after)
            })
        }
        write(dir, "kids.json", kids)
        val k = run(dir, "check", "kids.json")
        assertTrue(k.out, k.out.contains("Score:    85/100 · 85–100 band"))
        assertTrue(k.out, k.out.contains("Range:    AI and documentation readings (INFERRED: C9) can move this between 70 and 100 · the band could change (worst case: 60–84 band; best case: 85–100 band)\n"))

        val quiet = SampleBundles.fixed().let { b -> b.copy(sessions = b.sessions.map { it.copy(netEvents = emptyList()) }) }
        write(dir, "quiet.json", quiet)
        assertTrue(run(dir, "check", "quiet.json").out.contains("Range:    not established (the result is inconclusive)\n"))
        val json = Json.parseToJsonElement(run(dir, "check", "quiet.json", "--format", "json").out).jsonObject
        assertEquals(JsonNull, json["bundles"]!!.jsonArray[0].jsonObject["robustness"])
    }
}
