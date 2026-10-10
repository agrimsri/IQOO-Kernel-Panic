package com.dpdpxray.app.report

import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.data.SessionNetworkReadings
import com.dpdpxray.core.export.AuditBundleV1
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleApp
import com.dpdpxray.core.export.SdkAttributionExport
import com.dpdpxray.core.quarantine.QuarantineReport
import com.dpdpxray.core.report.AuditJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * "Export audit data (JSON)": the saved audit as a traceveil.audit.v1 bundle for TraceVeil CI (`traceveil-ci check`)
 * and the web console. Written next to the audit on the phone; it leaves the phone only if the person saves it to
 * Downloads or shares it.
 */
object AuditJsonExport {
    const val MIME = "application/json"
    const val SAMPLE_NOTE = "Synthetic sample data from TraceVeil's built-in sample audit; not a device observation."

    /**
     * [legalProfile] is the lens selected in Settings (a LegalProfile name); it is recorded, never applied to the evidence.
     * [sdkAttribution] is the optional "Which SDK caused this?" grouping, derived from the same sessions and result.
     */
    fun build(
        bundle: AuditBundle, versionName: String, exportedAt: String = AuditBundles.now(), legalProfile: String? = null,
        sdkAttribution: SdkAttributionExport? = null,
    ): AuditBundleV1 =
        AuditBundles.audit(
            app = BundleApp(bundle.meta.targetPackage, bundle.meta.targetLabel),
            meta = AuditJson.json.encodeToJsonElement(AuditMeta.serializer(), bundle.meta).jsonObject,
            sessions = bundle.sessions,
            result = bundle.result,
            // No saved readings means "not recorded" (older audits, samples), so the export says null, not [].
            networkReadings = bundle.networkReadings.takeIf { it.isNotEmpty() }
                ?.let { AuditJson.json.encodeToJsonElement(ListSerializer(SessionNetworkReadings.serializer()), it).jsonArray },
            toolVersion = versionName,
            exportedAt = exportedAt,
            note = SAMPLE_NOTE.takeIf { bundle.meta.sample },
            legalProfile = legalProfile,
            sdkAttribution = sdkAttribution,
        )

    /** Audit ids already say what they are ("audit-<ms>", "sample-<ms>"): TraceVeil-audit-1791595268053.json. */
    fun fileName(meta: AuditMeta) = "TraceVeil-${safe(meta.id)}.json"

    fun write(bundle: AuditBundle, versionName: String, legalProfile: String? = null, sdkAttribution: SdkAttributionExport? = null): File =
        File(bundle.dir, fileName(bundle.meta)).apply {
            writeText(AuditBundles.encode(build(bundle, versionName, legalProfile = legalProfile, sdkAttribution = sdkAttribution)), Charsets.UTF_8)
        }
}

/** "Export report (JSON)" on the quarantine behaviour report: the saved report wrapped as traceveil.quarantine.v1. */
object QuarantineJsonExport {
    /** Run ids are "quarantine-<ms>": TraceVeil-quarantine-1791600000000.json. */
    fun fileName(report: QuarantineReport) = "TraceVeil-${safe(report.id)}.json"

    /** Writes into [dir] (the app uses cacheDir/exports, which the share sheet can read). */
    fun write(dir: File, report: QuarantineReport): File {
        dir.mkdirs()
        return File(dir, fileName(report)).apply { writeText(AuditBundles.encodeQuarantine(AuditBundles.quarantine(report)), Charsets.UTF_8) }
    }
}

private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "export" }
