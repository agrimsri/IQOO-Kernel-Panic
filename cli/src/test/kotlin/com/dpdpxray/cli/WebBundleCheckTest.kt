package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleApp
import com.dpdpxray.core.export.BundleTarget
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import com.dpdpxray.core.web.RawWebRequest
import com.dpdpxray.core.web.WebAudit
import com.dpdpxray.core.web.WebPlanCapture
import com.dpdpxray.core.web.WebSessions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `traceveil-ci check` reads a website check's bundle (target.kind = "web") and re-evaluates it with the same rules. */
class WebBundleCheckTest {
    @get:Rule val temp = TemporaryFolder()
    private val s = 1_000_000_000L

    private fun bundleFile(): File {
        val catalog = TrackerCatalog.loadDefault()
        val capture = WebPlanCapture(
            id = "web-1-REJECT", plan = AuditPlan.REJECT, url = "https://news.example/", startedAtNanos = 0, endedAtNanos = 20 * s,
            startedAtEpochMs = 1_791_000_000_000,
            requests = listOf(
                RawWebRequest(s / 10, "https://news.example/", mainFrame = true),
                RawWebRequest(s / 2, "https://www.googletagmanager.com/gtag/js?id=G-TEST"),
                RawWebRequest(6 * s, "https://securepubads.g.doubleclick.net/pagead/ads"),
            ),
            consentEvents = listOf(ConsentEvent(1 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.REJECT, "Reject All")),
            profileCleared = true,
        )
        val sessions = listOf(WebSessions(catalog).build(capture))
        val result = RulesEngine(catalog).evaluate(sessions)
        val bundle = AuditBundles.audit(
            app = BundleApp(WebAudit.packageFor("news.example"), "https://news.example"),
            meta = JsonObject(mapOf("id" to JsonPrimitive("web-1"), "targetKind" to JsonPrimitive("web"))),
            sessions = sessions, result = result, networkReadings = null, toolVersion = "test", exportedAt = "2026-10-11T08:00:00Z",
            target = BundleTarget(WebAudit.KIND, "https://news.example/"),
        )
        return File(temp.newFolder("web"), "TraceVeil-web-1.json").apply { writeText(AuditBundles.encode(bundle)) }
    }

    @Test
    fun `a website bundle is checked with the website rules, and today's result matches the stored one`() {
        val file = bundleFile()
        val r = run(file.parentFile, "check", file.name)
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("C2 Tracker hosts contacted after the user refused"))
        assertTrue(r.out, r.out.contains("same as stored in the bundle"))
        assertTrue(r.out, r.out.contains("first evidence: GET"))
        assertTrue(r.out, r.out.contains(Wording.WEB_CAVEAT))
        assertTrue(r.err, r.err.isEmpty())
    }
}
