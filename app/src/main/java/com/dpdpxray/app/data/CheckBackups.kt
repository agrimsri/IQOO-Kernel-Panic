package com.dpdpxray.app.data

import com.dpdpxray.app.report.AuditJsonExport
import com.dpdpxray.core.datasafety.PlayLabelRecord
import com.dpdpxray.core.export.AuditBackups
import com.dpdpxray.core.export.AuditBundleV1
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BackupWritten
import com.dpdpxray.core.export.RestorePlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.report.AuditJson
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** What a restore did: [restored] audits saved, [failed] that could not be written to storage, and the plan behind it. */
data class RestoreOutcome(val plan: RestorePlan, val restored: Int, val failed: Int) {
    val message: String
        get() = AuditBackups.summary(plan, restored) + if (failed > 0) " $failed could not be saved on this phone." else ""
}

/**
 * "Back up my checks" and "Restore from a backup" on the phone. The file format, its integrity hash and the merge
 * rules live in core ([AuditBackups]); this maps saved audits to audit exports and back. Offline: the backup is written
 * only to the file the person picks, and TraceVeil never uploads it.
 */
object CheckBackups {
    /** One line shown next to both actions. */
    const val PRIVACY_LINE = "The file stays wherever you save it. TraceVeil never uploads it."

    /** What a backup leaves out, said once where the actions are. */
    const val SCOPE_LINE = "It holds every saved check's evidence and results. Consent-screen screenshots are not included."

    /**
     * Streams every saved audit into [out], newest first, one audit in memory at a time. Audits whose files cannot be
     * read are left out and counted in [BackupResult.unreadable].
     */
    fun write(repo: AuditRepository, ids: List<String>, out: OutputStream, versionName: String, createdAt: String): BackupResult {
        var unreadable = 0
        val audits = iterator {
            for (id in ids) {
                val bundle = repo.loadNow(id)
                if (bundle == null) unreadable++ else yield(AuditJsonExport.build(bundle, versionName, exportedAt = createdAt))
            }
        }
        val written = AuditBackups.write(out, audits, createdAt, versionName)
        return BackupResult(written, unreadable)
    }

    /** Saves each planned audit with a report built by [markdown]; an audit that fails to save is counted, not thrown. */
    suspend fun restore(
        repo: AuditRepository,
        plan: RestorePlan,
        markdown: (AuditMeta, List<AuditSession>, AuditResult) -> String,
    ): RestoreOutcome {
        var restored = 0
        var failed = 0
        for (bundle in plan.toRestore) {
            val ok = runCatching {
                val meta = metaOf(bundle)
                repo.save(meta, bundle.sessions, bundle.result, markdown(meta, bundle.sessions, bundle.result), readingsOf(bundle))
                labelOf(bundle)?.let { repo.savePlayLabel(meta.id, it) }
            }.isSuccess
            if (ok) restored++ else failed++
        }
        return RestoreOutcome(plan, restored, failed)
    }

    /** The audit's own saved meta when it reads as this app's [AuditMeta]; otherwise rebuilt from the bundle itself. */
    fun metaOf(bundle: AuditBundleV1): AuditMeta {
        runCatching { AuditJson.json.decodeFromJsonElement(AuditMeta.serializer(), bundle.meta) }.getOrNull()?.let { return it }
        val m = AuditBundles.metaOf(bundle)
        return AuditMeta(
            id = requireNotNull(m.id) { "audit without an id" },
            targetPackage = m.targetPackage ?: bundle.app.packageName,
            targetLabel = m.targetLabel ?: bundle.app.label,
            createdAtMs = m.createdAtMs ?: bundle.sessions.minOfOrNull { it.startedAtEpochMs } ?: 0L,
            score = bundle.result.score,
            band = bundle.result.band,
            findingCount = bundle.result.findings.size,
            plans = m.plans.ifEmpty { bundle.sessions.map { it.plan.label }.distinct() },
            sample = m.sample,
            timingProfile = m.timingProfile,
            inconclusiveReason = m.inconclusiveReason,
            targetKind = m.targetKind ?: bundle.target?.kind,
        )
    }

    /** The Play label attached to a backed-up check, or null when none was attached. */
    fun labelOf(bundle: AuditBundleV1): PlayLabelRecord? = bundle.playLabel?.let { PlayLabelRecord(it.text, it.source, it.addedAtMs) }

    /** The saved per-plan upload readings; none (not recorded) when absent or unreadable, never invented. */
    fun readingsOf(bundle: AuditBundleV1): List<SessionNetworkReadings> = bundle.networkReadings?.let { arr ->
        runCatching { AuditJson.json.decodeFromJsonElement(ListSerializer(SessionNetworkReadings.serializer()), arr) }.getOrNull()
    }.orEmpty()

    /** Reads all of [input] if it holds at most [max] bytes; null when it holds more. */
    fun readBounded(input: InputStream, max: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

data class BackupResult(val written: BackupWritten, val unreadable: Int) {
    val message: String
        get() {
            val n = written.count
            val checks = if (n == 1) "1 check" else "$n checks"
            return "Backed up $checks to the file you chose." + if (unreadable > 0) " $unreadable could not be read and were left out." else ""
        }
}
