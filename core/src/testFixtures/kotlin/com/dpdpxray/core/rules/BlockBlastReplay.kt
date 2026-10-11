package com.dpdpxray.core.rules

import com.dpdpxray.core.manifest.SdkManifestCatalog
import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.model.StartMode

/**
 * Test data shaped like a real-app Quick check on a phone (Block Blast!, `com.block.juggle`): the app was restarted
 * with its data kept, no consent screen appeared, 136 lookups were captured, and the findings were U1 (seven ad and
 * attribution SDKs), C12 (AppLovin and Vungle looked up in the background) and C13 (Firebase collection on by default
 * in the manifest). Built from that run's findings, not from its capture file: each tracker host is a name inside its
 * entity's catalogued domain, and the app's own servers are placeholders under `.example`.
 */
object BlockBlastReplay {
    private const val S = 1_000_000_000L
    const val PACKAGE = "com.block.juggle"
    const val LABEL = "Block Blast!"

    val U1_ENTITIES = setOf(
        "AppsFlyer", "AppLovin", "Unity Ads", "Vungle / Liftoff", "Pangle (TikTok for Business)", "Google Ads / AdMob", "InMobi",
    )

    /** Lookups while the game was on screen, by entity (the catalogue's domain for each). */
    private val foreground = listOf(
        "launches.appsflyersdk.com", "inapps.appsflyersdk.com", "conversions.appsflyer.com",
        "ms.applovin.com", "d.applovin.com", "rt.applovin.com",
        "config.unityads.unity3d.com", "auction.unityads.unity3d.com",
        "config.ads.vungle.com",
        "api16-access-sg.pangle.io",
        "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
        "sdk.inmobi.com",
    )

    /** The game's own servers and content hosts (placeholders), which carry most of the 136 lookups. */
    private val own = listOf("api.blockblast.example", "cdn.blockblast.example", "config.blockblast.example", "img.blockblast.example")

    /** After Home was pressed and the game was confirmed off screen. */
    private val background = listOf("ms.applovin.com", "events.ads.vungle.com")

    /** Firebase Analytics bundled with no collection switch declared (the vendor's own merged manifest entries). */
    private val manifest: AppManifestFacts = SdkManifestCatalog.loadDefault().facts(
        "8.4.0", 8040, emptyMap<String, Any?>(),
        mapOf(
            AppManifestFacts.PART_SERVICES to listOf(
                "com.google.android.gms.measurement.AppMeasurementService",
                "com.google.android.gms.measurement.AppMeasurementJobService",
            ),
            AppManifestFacts.PART_RECEIVERS to listOf("com.google.android.gms.measurement.AppMeasurementReceiver"),
            AppManifestFacts.PART_PROVIDERS to emptyList(),
        ),
    )

    const val LOOKUPS = 136

    fun sessions(): List<AuditSession> {
        val bgAt = 40 * S
        val front = (foreground + own).let { base ->
            // Repeated lookups across the 40 s window, as a running game makes them, up to 136 in all with the background ones.
            (0 until LOOKUPS - background.size).map { i -> NetEvent(S / 2 + i * (bgAt - S) / (LOOKUPS - background.size), base[i % base.size]) }
        }
        val behind = background.mapIndexed { i, h -> NetEvent(bgAt + (i + 2) * S, h) }
        return listOf(
            AuditSession(
                id = "blockblast-REJECT", targetPackage = PACKAGE, targetLabel = LABEL, plan = AuditPlan.REJECT,
                startedAtNanos = 0, endedAtNanos = 55 * S, startedAtEpochMs = 1_791_700_000_000,
                netEvents = front + behind, freshState = false, backgroundStartedNanos = bgAt, permissionDialogsWatched = true,
                manifestFacts = manifest, deviceModel = "vivo I2501", androidVersion = "16",
                notes = listOf(
                    "Restarted the app before this plan (data kept).",
                    "App data was not cleared (reset not approved) — results reflect the app's current state.",
                    "No consent screen detected within 20 s.",
                ),
                conditions = PlanConditions(
                    startMode = StartMode.RESTARTED, runningBeforeStart = true, privateDnsAtStart = false, privateDnsAtEnd = false,
                    otherVpnAtEnd = false, networkAtStart = true, networkAtEnd = true, phoneLocked = false,
                ),
            ),
        )
    }
}
