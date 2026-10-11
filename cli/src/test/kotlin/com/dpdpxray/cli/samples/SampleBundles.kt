package com.dpdpxray.cli.samples

import com.dpdpxray.core.attribution.SdkAttribution
import com.dpdpxray.core.export.AuditBundleV1
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleApp
import com.dpdpxray.core.export.BundleMeta
import com.dpdpxray.core.export.QuarantineBundleV1
import com.dpdpxray.core.export.SdkAttributionExport
import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.quarantine.AddressScope
import com.dpdpxray.core.quarantine.AttemptProtocol
import com.dpdpxray.core.quarantine.PacketHeader
import com.dpdpxray.core.quarantine.QuarantineRecorder
import com.dpdpxray.core.quarantine.QuarantineReports
import com.dpdpxray.core.quarantine.QuarantineStopReason
import com.dpdpxray.core.quarantine.SensitiveSettings
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Synthetic sample bundles for docs/samples (the CI docs and the web console use the same files). Every bundle is test
 * data copied from existing core test fixtures, marked synthetic in its "note" and in meta.sample; none is a device
 * observation. Results are computed by the current rules engine and the SDK groups (sdkAttribution, as in a phone
 * export) by core SdkAttribution, never typed in. Regenerate with
 * `./gradlew :cli:writeSamples`; SampleBundlesTest fails when the committed files drift from these fixtures.
 */
object SampleBundles {
    const val EXPORTED_AT = "2026-10-10T00:00:00Z"
    const val TOOL_VERSION = "1.1.0-samples"
    private const val AUDITED_AT = 1_791_590_400_000L // 10 Oct 2026 00:00 UTC
    private const val S = 1_000_000_000L

    const val LEAKY = "leakyshop-leaky_sample.json"
    const val FIXED = "leakyshop-fixed_sample.json"
    const val KEPT_DATA = "instagram-kept-data_sample.json"
    const val QUARANTINE = "riskdemo-quarantine_sample.json"

    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private val attribution = SdkAttribution(TrackerCatalog.loadDefault(), FixCatalog.loadDefault())

    private fun net(t: Double, host: String, cname: List<String> = emptyList()) =
        NetEvent(tNanos = (t * S).toLong(), hostname = host, cnameChain = cname)

    // --- LeakyShop: copies of the device-shaped sessions in core DemoScoreRegressionTest, after an approved reset ---

    private val deviceScreen = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN,
        source = AuditSource.DETERMINISTIC, observedFields = setOf("preTicked", "rejectOption"),
    )
    private val shown = ConsentEvent((2.1 * S).toLong(), ConsentKind.SCREEN_APPEARED)
    private val base = AuditSession(
        id = "", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop (sample)", plan = AuditPlan.SILENT,
        startedAtEpochMs = AUDITED_AT, screenAudit = deviceScreen, freshState = true,
    )

    val leakySessions: List<AuditSession> = listOf(
        base.copy(
            id = "sample-c", plan = AuditPlan.SILENT, endedAtNanos = 30 * S, consentEvents = listOf(shown),
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
                net(0.9, "firebase-settings.crashlytics.com"), net(1.3, "launches.appsflyersdk.com"), net(6.0, "app-measurement.com"),
            ),
        ),
        base.copy(
            id = "sample-b", plan = AuditPlan.REJECT, endedAtNanos = 35 * S,
            consentEvents = listOf(shown, ConsentEvent(6 * S, ConsentKind.SETTINGS, "Manage"), ConsentEvent((8.3 * S).toLong(), ConsentKind.REJECT, "Reject all")),
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.8, "graph.facebook.com"),
                net(9.4, "graph.facebook.com"), net(11.2, "app-measurement.com"), net(12.0, "launches.appsflyersdk.com"),
            ),
        ),
        base.copy(
            id = "sample-a", plan = AuditPlan.ACCEPT, endedAtNanos = 30 * S,
            consentEvents = listOf(shown, ConsentEvent((4.9 * S).toLong(), ConsentKind.ACCEPT, "Accept & Continue")),
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
                net(5.2, "cdn.leakyshop.example"), net(5.6, "googleads.g.doubleclick.net"), net(6.1, "in1.clevertap-prod.com"),
            ),
        ),
    )

    /** The fixed build: first-party lookups only, the pre-ticked box cleared and Reject visible. */
    val fixedSessions: List<AuditSession> = leakySessions.map { s ->
        s.copy(
            id = s.id.replace("sample-", "sample-fixed-"), targetPackage = "com.leakyshop.demo.fixed", targetLabel = "LeakyShop Fixed (sample)",
            screenAudit = deviceScreen.copy(preTicked = false, rejectOption = RejectOption.VISIBLE),
            netEvents = listOf(net(0.4, "api.leakyshop.example"), net(9.0, "api.leakyshop.example")),
        )
    }

    // --- Instagram-like kept-data run: the lookups of core ContextRulesTest (G1) -----------------------------------

    val keptDataSessions: List<AuditSession> = listOf(
        AuditSession(
            id = "sample-kept-c", targetPackage = "com.instagram.android", targetLabel = "Instagram (synthetic sample)",
            plan = AuditPlan.SILENT, startedAtEpochMs = AUDITED_AT, startedAtNanos = 0, endedAtNanos = 30 * S,
            privateDnsActive = false, freshState = false,
            notes = listOf("App data was not cleared (reset not approved or Shizuku not authorised) — results reflect the app's current state."),
            netEvents = listOf(
                net(1.0, "graph.facebook.com"),
                net(2.0, "i.instagram.com"),
                net(3.0, "scontent.cdninstagram.com", listOf("scontent.cdninstagram.com", "scontent.xx.fbcdn.net")),
                net(4.0, "static.xx.fbcdn.net"),
            ),
        ),
    )

    private const val SYNTHETIC = "SYNTHETIC SAMPLE: test data, not a device observation. Generated by ./gradlew :cli:writeSamples from "

    fun leaky(): AuditBundleV1 = audit(
        "sample-leakyshop-leaky", leakySessions,
        SYNTHETIC + "a copy of the device-shaped LeakyShop leaky audit in core DemoScoreRegressionTest " +
            "(three plans after an approved fresh reset).",
    )

    fun fixed(): AuditBundleV1 = audit(
        "sample-leakyshop-fixed", fixedSessions,
        SYNTHETIC + "the same plans for the LeakyShop fixed build (first-party lookups only, no pre-ticked box, " +
            "Reject visible), as in core DemoScoreRegressionTest.",
    )

    fun keptData(): AuditBundleV1 = audit(
        "sample-instagram-kept-data", keptDataSessions,
        SYNTHETIC + "the Instagram-like lookups in core ContextRulesTest (G1): one plan on the app's kept data with no " +
            "consent screen, so consent history is unknown (U1) and Meta servers are same-company collection (S1). " +
            "Not a statement about the real app.",
    )

    private fun audit(id: String, sessions: List<AuditSession>, note: String): AuditBundleV1 {
        val result = engine.evaluate(sessions)
        val first = sessions.first()
        val meta = BundleMeta(
            id = id, targetPackage = first.targetPackage, targetLabel = first.targetLabel, createdAtMs = AUDITED_AT,
            score = result.score, band = result.band, findingCount = result.findings.size,
            plans = sessions.map { it.plan.label }, sample = true, timingProfile = null,
        )
        return AuditBundles.audit(
            app = BundleApp(first.targetPackage, first.targetLabel),
            meta = AuditBundles.json.encodeToJsonElement(BundleMeta.serializer(), meta).jsonObject,
            sessions = sessions, result = result, networkReadings = null, toolVersion = TOOL_VERSION,
            exportedAt = EXPORTED_AT, note = note,
            // As the phone's Report-screen export does: derived from these sessions and findings, no new evidence.
            sdkAttribution = SdkAttributionExport(groups = attribution.groups(result, sessions)),
        )
    }

    // --- Quarantine: the recorder calls of core QuarantineReportTest ------------------------------------------------

    fun quarantine(): QuarantineBundleV1 {
        val resolvers = setOf("10.111.0.53", "fd00:111::53")
        val r = QuarantineRecorder(resolvers)
        r.recordTargetOnScreen(1_200_000_000)
        r.recordDns(1_500_000_000, "panel.example.com", 1, "10.111.0.53")
        r.recordDns(1_600_000_000, "panel.example.com", 1, "10.111.0.53")
        r.recordPacket(
            2_000_000_000,
            PacketHeader(
                ipVersion = 4, destination = "203.0.113.9", scope = AddressScope.PUBLIC, protocolNumber = 6,
                protocol = AttemptProtocol.TCP_SYN, destinationPort = 443, icmpType = null, fragment = false, networkControl = false,
            ),
        )
        r.recordPermissionDialog(3_000_000_000, "com.google.android.permissioncontroller", "Allow Risk Demo to send and view SMS messages?", true)
        r.recordSettingsScreen(4_000_000_000, SensitiveSettings.DISPLAY_OVER_OTHER_APPS, "Display over other apps")
        val report = QuarantineReports.build(
            id = "quarantine-sample", targetPackage = "com.traceveil.riskdemo", targetLabel = "Risk Demo (TEST app, sample)",
            startedAtEpochMs = AUDITED_AT, startedAtNanos = 1_000_000_000, endedAtNanos = 61_000_000_000,
            plannedDurationMs = 60_000, stopReason = QuarantineStopReason.TIMER, resolverAddresses = resolvers,
            observations = r.snapshot(),
        )
        return AuditBundles.quarantine(
            report, exportedAt = EXPORTED_AT,
            note = SYNTHETIC + "the recorder calls in core QuarantineReportTest (documentation-range address 203.0.113.9).",
        )
    }

    /** File name to JSON text, exactly as committed under docs/samples. */
    fun files(): Map<String, String> = linkedMapOf(
        LEAKY to AuditBundles.encode(leaky()) + "\n",
        FIXED to AuditBundles.encode(fixed()) + "\n",
        KEPT_DATA to AuditBundles.encode(keptData()) + "\n",
        QUARANTINE to AuditBundles.encodeQuarantine(quarantine()) + "\n",
    )

    fun write(dir: File) {
        dir.mkdirs()
        files().forEach { (name, text) -> File(dir, name).writeText(text, Charsets.UTF_8) }
    }
}
