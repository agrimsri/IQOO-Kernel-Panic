package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.cli.CliHarness.write
import com.dpdpxray.cli.CliHarness.writeText
import com.dpdpxray.core.export.AuditBundleV1
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleApp
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.rules.QuietBackgroundAppReplay
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

/** `traceveil-ci check` on a background watch: it states the watch length and that no consent screen was driven. */
class WatchBundleCheckTest {
    @get:Rule val temp = TemporaryFolder()

    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    private fun bundle(s: AuditSession): AuditBundleV1 = AuditBundles.audit(
        app = BundleApp(s.targetPackage, s.targetLabel), meta = JsonObject(emptyMap()), sessions = listOf(s),
        result = engine.evaluate(listOf(s)), networkReadings = null, toolVersion = "test", exportedAt = "2026-10-11T08:00:00Z",
    )

    private val relaxed = """{"failOn": ["C2"], "requireFreshState": false, "requirePlans": [], "allowNotEstablished": true}"""

    @Test fun `a watch bundle says it was a 10-minute background watch, in text and JSON`() {
        val dir = temp.newFolder("watch")
        write(dir, "watch.json", bundle(QuietBackgroundAppReplay.backgroundWatch()))
        writeText(dir, "relaxed.json", relaxed)
        val r = run(dir, "check", "watch.json", "--policy", "relaxed.json")
        assertTrue(r.out, r.out.contains("Watch:    background watch of 10 minutes: what the app sends while it runs in the background; " +
            "the app was not opened, restarted or tapped, so its consent screen was not tested"))
        assertTrue(r.out, r.out.contains("C12"))
        assertFalse(r.out, r.out.contains("Inconclusive (score not established)"))
        val json = Json.parseToJsonElement(run(dir, "check", "watch.json", "--policy", "relaxed.json", "--format", "json").out).jsonObject
        val w = json["bundles"]!!.jsonArray[0].jsonObject["backgroundWatch"]!!.jsonObject
        assertEquals(10, w["plannedMinutes"]!!.jsonPrimitive.int)
        assertEquals("false", w["consentScreenDriven"]!!.jsonPrimitive.content)
    }

    @Test fun `a quiet watch names what it observed and offers a longer watch`() {
        val dir = temp.newFolder("quiet")
        write(dir, "quiet.json", bundle(QuietBackgroundAppReplay.quietWatch()))
        val r = run(dir, "check", "quiet.json")
        assertTrue(r.out, r.out.contains("result is inconclusive (V0: almost no network use in a background watch of 10 minutes; " +
            "likely cause: the app made almost no connections while it was watched in the background)"))
        assertTrue(r.out, r.out.contains("watch it in the background for 30 minutes (TraceVeil does not open, restart or tap it; its data stays)"))
    }

    @Test fun `an ordinary bundle has no watch line or field`() {
        val dir = temp.newFolder("plain")
        write(dir, "plain.json", bundle(QuietBackgroundAppReplay.quickCheck()))
        val r = run(dir, "check", "plain.json")
        assertFalse(r.out, r.out.contains("Watch:"))
        val json = Json.parseToJsonElement(run(dir, "check", "plain.json", "--format", "json").out).jsonObject
        assertFalse("backgroundWatch" in json["bundles"]!!.jsonArray[0].jsonObject)
    }
}
