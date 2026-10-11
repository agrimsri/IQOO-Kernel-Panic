package com.dpdpxray.core.rules.science

import com.dpdpxray.core.manifest.SdkManifestCatalog
import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.netcapture.FlowProtocol
import com.dpdpxray.core.netcapture.FlowRecord
import com.dpdpxray.core.netcapture.HttpExchange
import com.dpdpxray.core.netcapture.Redaction
import com.dpdpxray.core.netcapture.TlsStatus
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.rules.ScoreCalculator
import com.dpdpxray.core.screen.Fixtures
import com.dpdpxray.core.trackers.TrackerCatalog
import com.dpdpxray.core.web.WebCookie
import com.dpdpxray.core.web.WebCookieBasis
import com.dpdpxray.core.web.WebEvidence
import com.dpdpxray.core.web.WebRequest
import com.dpdpxray.core.web.WebResourceType
import com.dpdpxray.core.web.WebSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Keeps CheckVariants honest against the real rules engine, in both directions:
 *
 * - every variant in the table is reachable: a small audit drives the engine to emit exactly that check, severity and
 *   evidence label;
 * - the engine emits nothing outside the table and each check at most once per audit: random audits (seeded) through
 *   the engine, with their score equal to ScoreCalculator.compute on their findings.
 *
 * The exhaustive and sensitivity tests enumerate this table, so this test is what ties them to the shipped engine.
 */
class CheckVariantWitnessTest {
    private val s = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()
    private val engine = RulesEngine(catalog.withFirstParty(setOf("shop.test")))
    private val sdk = SdkManifestCatalog.loadDefault()

    private val shown = ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED)
    private val accept = listOf(shown, ConsentEvent(5 * s, ConsentKind.ACCEPT, "Accept"))
    private val reject = listOf(shown, ConsentEvent(5 * s, ConsentKind.REJECT, "Reject all"))
    private val firstParty = listOf(NetEvent(1 * s, "api.shop.app"), NetEvent(2 * s, "cdn.shop.app"))
    private fun net(t: Double, host: String) = NetEvent((t * s).toLong(), host)
    private val aliased = NetEvent(1 * s, "metrics.shop.test", cnameChain = listOf("metrics.shop.test", "region1.app-measurement.com"))

    private val fields = setOf("preTicked", "rejectOption", "separateChoices", "itemisedData", "purposeStated", "withdrawalInfo", "languageOptions")
    private val clean = ScreenAudit(
        isConsentScreen = true, preTicked = false, rejectOption = RejectOption.VISIBLE, purposesCount = 1, separateChoices = true,
        itemisedData = true, purposeStated = true, withdrawalInfo = true, languageOptions = listOf("English", "Hindi"),
        childAudienceSignals = false, source = AuditSource.MERGED, observedFields = fields,
    )

    private val firebaseFacts: AppManifestFacts = sdk.facts(
        "1.0", 1, mapOf("com.google.android.gms.version" to 12451000),
        mapOf(
            AppManifestFacts.PART_SERVICES to listOf(
                "com.google.android.gms.measurement.AppMeasurementService", "com.google.android.gms.measurement.AppMeasurementJobService",
            ),
            AppManifestFacts.PART_RECEIVERS to listOf("com.google.android.gms.measurement.AppMeasurementReceiver"),
            AppManifestFacts.PART_PROVIDERS to emptyList(),
        ),
    )

    private fun session(
        events: List<NetEvent> = firstParty,
        consent: List<ConsentEvent> = accept,
        screen: ScreenAudit? = null,
        fresh: Boolean? = true,
        pkg: String = "com.shop.app",
        childFacing: Boolean = false,
        privateDns: Boolean? = false,
        background: Long? = null,
        dialogsWatched: Boolean? = null,
        facts: AppManifestFacts? = null,
        plan: AuditPlan = AuditPlan.ACCEPT,
        flows: List<FlowRecord> = emptyList(),
    ) = AuditSession(
        id = plan.name, targetPackage = pkg, targetLabel = "ShopNow", plan = plan, childFacing = childFacing, endedAtNanos = 40 * s,
        netEvents = events, consentEvents = consent, screenAudit = screen, freshState = fresh, privateDnsActive = privateDns,
        backgroundStartedNanos = background, permissionDialogsWatched = dialogsWatched, manifestFacts = facts,
        flows = flows, captureDepth = if (flows.isEmpty()) null else "full-relay",
    )

    /** A full-relay plain-HTTP request to api.shop.app whose query is redacted before it is stored, as the tracker does. */
    private fun cleartext(target: String): List<FlowRecord> {
        val r = Redaction.target(target)
        val get = HttpExchange(6 * s, "GET", "api.shop.app", r.text, sensitive = r.found)
        return listOf(FlowRecord(1, FlowProtocol.TCP, "203.0.113.20", 80, 6 * s, sni = "api.shop.app", tls = TlsStatus.CLEARTEXT, http = listOf(get)))
    }

    /** The clean screen with one field changed; [observed] says whether that field was confirmed from the tree. */
    private fun screen(field: String, observed: Boolean, change: ScreenAudit.() -> ScreenAudit) =
        clean.change().copy(observedFields = if (observed) fields else fields - field)

    private fun dialog(t: Double, text: String) = ConsentEvent((t * s).toLong(), ConsentKind.PERMISSION_DIALOG, text)

    /** One audit per variant, built so the engine gives that variant. */
    private fun witness(v: Variant): AuditSession = when (v.check) {
        CheckId.C1 -> session(events = firstParty + net(1.5, "app-measurement.com"))
        CheckId.NC -> session(events = firstParty + net(1.5, "graph.facebook.com"), consent = emptyList(), plan = AuditPlan.SILENT)
        CheckId.C2 -> session(events = firstParty + net(8.0, "graph.facebook.com"), consent = reject, plan = AuditPlan.REJECT)
        CheckId.C3 -> session(screen = screen("preTicked", v.observed) { copy(preTicked = true) })
        CheckId.C4 -> session(screen = screen("rejectOption", v.observed) { copy(rejectOption = RejectOption.NONE) })
        CheckId.C5 -> session(screen = screen("separateChoices", v.observed) { copy(purposesCount = 2, separateChoices = false) })
        CheckId.C6 -> session(screen = screen("itemisedData", v.observed) { copy(itemisedData = false) })
        CheckId.C7 -> session(screen = screen("withdrawalInfo", v.observed) { copy(withdrawalInfo = false) })
        CheckId.C8 -> session(screen = screen("languageOptions", v.observed) { copy(languageOptions = listOf("English")) })
        CheckId.C9 -> if (v.observed) session(events = firstParty + net(8.0, "graph.facebook.com"), childFacing = true)
        else session(events = firstParty + net(8.0, "graph.facebook.com"), screen = clean.copy(childAudienceSignals = true))
        CheckId.C10 -> session(events = firstParty + net(1.5, "app-measurement.com"))
        CheckId.C13 -> session(facts = firebaseFacts)
        CheckId.C14 -> session(
            consent = listOf(dialog(1.0, if (v.severity.weight == 0) "Allow ShopNow to send you notifications?" else "Allow ShopNow to access this device's location?")) + accept,
            dialogsWatched = true,
        )
        CheckId.V0 -> session(events = listOf(net(1.0, "api.shop.app")))
        CheckId.U1 -> session(events = firstParty + if (v.withEntities) listOf(net(1.5, "graph.facebook.com")) else emptyList(), consent = emptyList(), fresh = false, plan = AuditPlan.SILENT)
        CheckId.S1 -> session(events = firstParty + net(8.0, "graph.facebook.com"), pkg = "com.instagram.android")
        CheckId.C11 -> session(events = firstParty + aliased, consent = reject, plan = AuditPlan.REJECT)
        CheckId.C12 -> session(events = firstParty + net(25.0, "graph.facebook.com"), background = 20 * s)
        CheckId.C15 -> session(consent = listOf(shown.copy(tree = Fixtures.leakyDialog), ConsentEvent(5 * s, ConsentKind.ACCEPT)))
        CheckId.V1 -> session(privateDns = true)
        CheckId.C16 -> session(flows = cleartext(if (v.observed) "/u?email=asha@example.in" else "/u?first_name=Asha"))
        CheckId.W1 -> webSession(
            WebCookie("_ga", listOf("shop.test"), "shop.test", false, 30, "a1b2c3d4e5f6", 3 * s, WebSnapshot.BANNER,
                trackerEntity = "Google Analytics", trackerBasis = WebCookieBasis.DOCUMENTED_NAME),
            accept, AuditPlan.ACCEPT,
        )
        CheckId.W2 -> webSession(
            WebCookie("_ga", listOf("shop.test"), "shop.test", false, 30, "a1b2c3d4e5f6", 7 * s, WebSnapshot.END, absentSinceNanos = 5 * s,
                trackerEntity = "Google Analytics", trackerBasis = WebCookieBasis.DOCUMENTED_NAME),
            reject, AuditPlan.REJECT,
        )
    }

    /** A website plan (TraceVeil's browser) with two first-party requests and one cookie. */
    private fun webSession(cookie: WebCookie, consent: List<ConsentEvent>, plan: AuditPlan) = AuditSession(
        id = plan.name, targetPackage = "web:shop.test", targetLabel = "https://shop.test", plan = plan, endedAtNanos = 40 * s,
        netEvents = listOf(NetEvent(1 * s, "shop.test"), NetEvent(2 * s, "cdn.shop.test")), consentEvents = consent, freshState = true,
        web = WebEvidence(
            url = "https://shop.test/", pageHost = "shop.test",
            requests = listOf(
                WebRequest(1 * s, "https://shop.test/", "shop.test", type = WebResourceType.DOCUMENT, mainFrame = true),
                WebRequest(2 * s, "https://cdn.shop.test/app.js", "cdn.shop.test", type = WebResourceType.SCRIPT),
            ),
            cookies = listOf(cookie),
        ),
    )

    private val Variant.observed get() = evidence == com.dpdpxray.core.model.EvidenceType.OBSERVED

    private fun matches(f: Finding, v: Variant) = f.checkId == v.check && f.severity == v.severity && f.evidenceType == v.evidence &&
        (f.checkId != CheckId.U1 || f.entities.isNotEmpty() == v.withEntities)

    @Test
    fun `the table covers every check the engine knows`() {
        assertEquals(CheckId.entries.toSet(), CheckVariants.table.keys)
        assertEquals(33, CheckVariants.all.size)
        assertEquals(
            listOf(CheckId.C1, CheckId.NC, CheckId.C2, CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8, CheckId.C9, CheckId.C10, CheckId.C13, CheckId.C14, CheckId.C16),
            CheckVariants.scored,
        )
    }

    @Test
    fun `every variant in the table is produced by the real engine`() {
        for (v in CheckVariants.all) {
            val r = engine.evaluate(listOf(witness(v)))
            assertTrue("${v.code} not produced; got ${r.findings.map { "${it.checkId}:${it.severity}/${it.evidenceType}" }}", r.findings.any { matches(it, v) })
        }
    }

    @Test
    fun `random audits through the engine emit only table variants, each check at most once`() {
        val seed = ScorePropertiesTest.seed
        val rnd = Random(seed + 77)
        val hit = mutableSetOf<String>()
        val n = 3_000
        repeat(n) { i ->
            val sessions = List(rnd.nextInt(1, 4)) { randomSession(rnd, it) }
            val r: AuditResult = engine.evaluate(sessions)
            val where = "seed=$seed audit#$i ${r.findings.map { "${it.checkId}:${it.severity}/${it.evidenceType}" }}"
            assertEquals(where, r.findings.size, r.findings.map { it.checkId }.distinct().size)
            for (f in r.findings) {
                val v = CheckVariants.table.getValue(f.checkId).firstOrNull { matches(f, it) }
                assertTrue("$where: ${f.checkId} ${f.severity}/${f.evidenceType} is not in the table", v != null)
                hit += v!!.code
            }
            assertEquals(where, ScoreCalculator.compute(r.findings), r.score to r.band)
            ScoreCalculator.robustness(r.findings)?.let { rob -> assertTrue(where, rob.worstCase <= rob.score && rob.score <= rob.bestCase) }
        }
        println("CheckVariantWitnessTest fuzz seed=$seed: $n random audits, ${hit.size} of ${CheckVariants.all.size} variants seen")
        ScienceReport.write("witness.json", ScienceReport.json(mapOf("seed" to seed, "audits" to n, "variantsSeen" to hit.sorted(), "variantsTotal" to CheckVariants.all.size)))
        assertTrue("fuzz reached only ${hit.size} variants: $hit", hit.size >= 24)
    }

    private val hosts = listOf(
        "api.shop.app", "cdn.shop.app", "app-measurement.com", "graph.facebook.com", "launches.appsflyersdk.com",
        "googleads.g.doubleclick.net", "in1.clevertap-prod.com", "gateway.facebook.com", "firebase-settings.crashlytics.com",
    )

    private fun <T> Random.pick(vararg xs: T): T = xs[nextInt(xs.size)]

    private fun randomSession(rnd: Random, i: Int): AuditSession {
        val events = List(rnd.nextInt(0, 9)) {
            val t = (rnd.nextDouble(0.1, 35.0) * s).toLong()
            if (rnd.nextInt(10) == 0) aliased.copy(tNanos = t) else NetEvent(t, hosts.random(rnd))
        }.sortedBy { it.tNanos }
        val consent = buildList {
            if (rnd.nextInt(5) == 0) add(dialog(rnd.nextDouble(0.5, 3.0), rnd.pick("Allow ShopNow to send you notifications?", "Allow ShopNow to access this device's location?")))
            when (rnd.nextInt(5)) {
                0 -> {}
                1 -> add(shown)
                2 -> addAll(accept)
                3 -> addAll(reject)
                else -> { add(shown.copy(tree = rnd.pick(Fixtures.leakyDialog, Fixtures.fixedDialog))); add(ConsentEvent(5 * s, rnd.pick(ConsentKind.ACCEPT, ConsentKind.REJECT))) }
            }
        }.sortedBy { it.tNanos }
        val screen = if (rnd.nextInt(3) == 0) null else ScreenAudit(
            isConsentScreen = rnd.nextInt(6) != 0,
            preTicked = rnd.pick(null, true, false),
            rejectOption = RejectOption.entries.random(rnd),
            purposesCount = rnd.pick(null, 1, 2, 3),
            separateChoices = rnd.pick(null, true, false),
            itemisedData = rnd.pick(null, true, false),
            purposeStated = rnd.pick(null, true, false),
            withdrawalInfo = rnd.pick(null, true, false),
            languageOptions = rnd.pick(null, listOf("English"), listOf("English", "Hindi")),
            childAudienceSignals = rnd.pick(null, true, false, false),
            observedFields = fields.filter { rnd.nextBoolean() }.toSet(),
            confidence = 0.8,
        )
        return session(
            events = events, consent = consent, screen = screen, fresh = rnd.pick(true, false, null),
            pkg = rnd.pick("com.shop.app", "com.shop.app", "com.instagram.android"), childFacing = rnd.nextInt(8) == 0,
            privateDns = rnd.pick(null, false, false, true), background = if (rnd.nextInt(4) == 0) 20 * s else null,
            dialogsWatched = rnd.pick(null, true), facts = if (rnd.nextInt(5) == 0) firebaseFacts else null,
            plan = AuditPlan.entries[i % AuditPlan.entries.size],
        )
    }
}
