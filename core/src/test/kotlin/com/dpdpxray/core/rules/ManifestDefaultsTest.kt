package com.dpdpxray.core.rules

import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.manifest.FlagState
import com.dpdpxray.core.manifest.SdkManifestCatalog
import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C13: SDK collection switches read from the installed manifest. The component and meta-data names are the ones the
 * vendors' own library manifests merge into an app (cited in sdk/manifest_flags.json). Test data, not device reads.
 */
class ManifestDefaultsTest {
    private val s = 1_000_000_000L
    private val sdk = SdkManifestCatalog.loadDefault()
    private val catalog = TrackerCatalog.loadDefault()
    private val engine = RulesEngine(catalog)

    private val firebaseServices = listOf(
        "com.google.firebase.components.ComponentDiscoveryService",
        "com.google.firebase.components:com.google.firebase.crashlytics.CrashlyticsRegistrar",
        "com.google.firebase.components:com.google.firebase.crashlytics.FirebaseCrashlyticsKtxRegistrar",
        "com.google.android.gms.measurement.AppMeasurementService",
        "com.google.android.gms.measurement.AppMeasurementJobService",
        "com.shop.sync.SyncService",
    )
    private val receivers = listOf("com.google.android.gms.measurement.AppMeasurementReceiver", "com.facebook.CurrentAccessTokenExpirationBroadcastReceiver")
    private val providers = listOf("com.facebook.internal.FacebookInitProvider", "androidx.startup.InitializationProvider")

    /** What a typical shop app with Firebase Analytics, Crashlytics and the Facebook SDK and no switches declares. */
    private val untouchedMeta: Map<String, Any?> = mapOf(
        "com.facebook.sdk.ApplicationId" to "1234567890",
        "com.facebook.sdk.ClientToken" to "client-token-secret",
        "com.google.android.geo.API_KEY" to "AIza-secret",
        "com.google.android.gms.version" to 12451000,
    )

    private fun facts(
        meta: Map<String, Any?>? = untouchedMeta,
        services: List<String>? = firebaseServices,
        recv: List<String>? = receivers,
        prov: List<String>? = providers,
    ) = sdk.facts(
        "4.2.0", 420, meta,
        mapOf(AppManifestFacts.PART_SERVICES to services, AppManifestFacts.PART_RECEIVERS to recv, AppManifestFacts.PART_PROVIDERS to prov),
    )

    private fun session(facts: AppManifestFacts?, pkg: String = "com.shop.app", events: List<NetEvent> = listOf(NetEvent(1 * s, "api.shop.app"), NetEvent(2 * s, "api.shop.app"))) =
        AuditSession(
            id = "c", targetPackage = pkg, targetLabel = "Shop", plan = AuditPlan.SILENT, endedAtNanos = 30 * s, freshState = true,
            netEvents = events, consentEvents = listOf(ConsentEvent(1 * s, ConsentKind.SCREEN_APPEARED)), manifestFacts = facts,
        )

    @Test
    fun `only allow-listed names are kept, never keys or tokens`() {
        val f = facts(meta = untouchedMeta + ("firebase_analytics_collection_enabled" to false))
        assertEquals(mapOf("firebase_analytics_collection_enabled" to "false"), f.collectionFlags)
        assertFalse(f.toString().contains("secret"))
        assertEquals(
            listOf(
                "com.facebook.CurrentAccessTokenExpirationBroadcastReceiver",
                "com.facebook.internal.FacebookInitProvider",
                "com.google.android.gms.measurement.AppMeasurementJobService",
                "com.google.android.gms.measurement.AppMeasurementReceiver",
                "com.google.android.gms.measurement.AppMeasurementService",
                "com.google.firebase.components:com.google.firebase.crashlytics.CrashlyticsRegistrar",
                "com.google.firebase.components:com.google.firebase.crashlytics.FirebaseCrashlyticsKtxRegistrar",
            ),
            f.sdkComponents,
        )
        assertEquals(listOf("meta-data", "services", "receivers", "providers"), f.partsRead)
    }

    @Test
    fun `bundled SDKs with no switches declared give C13 low inferred with the vendor fixes`() {
        val r = engine.evaluate(listOf(session(facts())))
        val c13 = r.findings.single { it.checkId == CheckId.C13 }
        assertEquals(Severity.LOW, c13.severity)
        assertEquals(EvidenceType.INFERRED, c13.evidenceType)
        assertEquals(PenaltyBand.NONE, c13.penaltyBand)
        assertEquals(listOf("s6(1)"), c13.dpdpRefs)
        assertEquals(listOf("Google Analytics for Firebase", "Firebase Crashlytics", "Meta (Facebook SDK)"), c13.entities)
        val fa = c13.evidence.first()
        assertTrue(fa, fa.startsWith("Google Analytics for Firebase: bundled (installed manifest declares com.google.android.gms.measurement.AppMeasurementJobService"))
        assertTrue(fa, fa.contains("firebase_analytics_collection_enabled not declared, so Analytics collection is on from launch"))
        assertTrue(fa, fa.contains("google_analytics_adid_collection_enabled not declared"))
        assertTrue(fa, fa.contains("version 4.2.0, code 420"))
        assertTrue(fa, fa.contains("https://firebase.google.com/docs/analytics/android/configure-data-collection"))
        assertTrue(c13.evidence[2].contains("com.facebook.sdk.AutoLogAppEventsEnabled not declared, so app installs and app launches are logged automatically"))
        // Low and inferred: half of 3 points.
        assertEquals(99, r.score)
        val fixes = FixCatalog.loadDefault().fixesFor(r, catalog).map { it.id }
        assertEquals(listOf("firebase-analytics", "crashlytics", "facebook"), fixes)
    }

    @Test
    fun `switches declared off give no C13`() {
        val off = untouchedMeta + mapOf(
            "firebase_analytics_collection_enabled" to false,
            "firebase_crashlytics_collection_enabled" to false,
            "com.facebook.sdk.AutoInitEnabled" to false,
        )
        assertNull(engine.evaluate(listOf(session(facts(meta = off)))).findings.firstOrNull { it.checkId == CheckId.C13 })
    }

    @Test
    fun `one SDK left on is reported alone, and an explicit true counts as on`() {
        val meta = untouchedMeta + mapOf(
            "firebase_analytics_collection_deactivated" to true,
            "firebase_crashlytics_collection_enabled" to true,
            "com.facebook.sdk.AutoLogAppEventsEnabled" to false,
        )
        val c13 = engine.evaluate(listOf(session(facts(meta = meta)))).findings.single { it.checkId == CheckId.C13 }
        assertEquals(listOf("Firebase Crashlytics"), c13.entities)
        assertTrue(c13.evidence.single().contains("firebase_crashlytics_collection_enabled declared to keep it on"))
    }

    @Test
    fun `a consent-mode default that denies analytics storage is not called on by default`() {
        val meta = untouchedMeta + ("google_analytics_default_allow_analytics_storage" to false)
        val c13 = engine.evaluate(listOf(session(facts(meta = meta)))).findings.single { it.checkId == CheckId.C13 }
        assertFalse(c13.entities.contains("Google Analytics for Firebase"))
    }

    @Test
    fun `an unreadable value leaves that SDK not established`() {
        val st = sdk.evaluate(facts(meta = untouchedMeta + ("firebase_crashlytics_collection_enabled" to "\${crashlyticsOn}")))
            .single { it.entry.sdkKey == "crashlytics" }
        assertEquals(FlagState.UNREADABLE, st.gates.single().second)
        assertNull(st.onByDefault)
    }

    @Test
    fun `tracker hosts looked up without the SDK in the manifest are not C13 (the LeakyShop case)`() {
        // LeakyShop resolves Firebase and Facebook hosts but bundles no SDK: its manifest has no SDK components.
        val leaky = facts(meta = emptyMap(), services = emptyList(), recv = emptyList(), prov = emptyList())
        val events = listOf(NetEvent((0.6 * s).toLong(), "app-measurement.com"), NetEvent((0.7 * s).toLong(), "graph.facebook.com"))
        val r = engine.evaluate(listOf(session(leaky, "com.leakyshop.demo", events)))
        assertTrue(r.findings.any { it.checkId == CheckId.C1 })
        assertNull(r.findings.firstOrNull { it.checkId == CheckId.C13 })
    }

    @Test
    fun `a host lookup is cited as context when the SDK is bundled`() {
        val events = listOf(NetEvent((0.6 * s).toLong(), "app-measurement.com"), NetEvent(2 * s, "api.shop.app"))
        val c13 = engine.evaluate(listOf(session(facts(), events = events))).findings.single { it.checkId == CheckId.C13 }
        assertTrue(c13.evidence.any { it.startsWith("Its hosts were also looked up in this audit: app-measurement.com (Google Analytics for Firebase)") })
    }

    @Test
    fun `manifest not read, or meta-data or components not read, is not established`() {
        assertNull(engine.evaluate(listOf(session(null))).findings.firstOrNull { it.checkId == CheckId.C13 })
        assertNull(engine.evaluate(listOf(session(facts(meta = null)))).findings.firstOrNull { it.checkId == CheckId.C13 })
        assertNull(engine.evaluate(listOf(session(facts(services = null, recv = null, prov = null)))).findings.firstOrNull { it.checkId == CheckId.C13 })
    }

    @Test
    fun `an SDK of the app's own publisher is first-party collection, not C13`() {
        val google = facts(services = firebaseServices, recv = listOf("com.google.android.gms.measurement.AppMeasurementReceiver"), prov = emptyList())
        val r = engine.evaluate(listOf(session(google, pkg = "com.google.android.youtube")))
        assertNull(r.findings.firstOrNull { it.checkId == CheckId.C13 })
    }

    @Test
    fun `every catalogue entry names a tracker entity, a fix template for C13 and https sources`() {
        val fixes = FixCatalog.loadDefault()
        val result = com.dpdpxray.core.model.AuditResult(emptyList(), 100, com.dpdpxray.core.model.ScoreBand.READY)
        assertTrue(fixes.fixesFor(result, catalog).isEmpty())
        for (e in sdk.entries) {
            assertEquals(e.sdkKey, catalog.entryFor(e.entity)?.sdkKey)
            assertTrue(e.components.isNotEmpty() || e.componentPrefixes.isNotEmpty())
            assertTrue(e.gates.isNotEmpty())
            (listOf(e.source, e.componentSource) + (e.gates + e.identifiers).map { it.source }).forEach { assertTrue(it, it.startsWith("https://")) }
        }
    }
}
