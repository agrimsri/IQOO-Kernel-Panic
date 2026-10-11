package com.dpdpxray.app.data

import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.app.audit.AuditPlanCoverage
import com.dpdpxray.app.audit.AuditTimingPolicy
import com.dpdpxray.core.update.AppVersion

sealed interface AuditComparisonResult {
    data class Available(
        val previousId: String, val previousTimeMs: Long, val scoreDelta: Int, val noLongerRecorded: Set<CheckId>, val newlyRecorded: Set<CheckId>,
        /** Checks recorded in both audits. */
        val stillRecorded: Set<CheckId> = emptySet(),
        val previousScore: Int? = null,
        val currentScore: Int? = null,
    ) : AuditComparisonResult
    data class Unavailable(val reason: String) : AuditComparisonResult
}

/** The audited app's version differed between the previous audit ([from]) and this one ([to]); both were recorded. */
data class VersionUpdate(val from: AppVersion, val to: AppVersion)

internal object AuditComparison {
    fun previousId(current: AuditMeta, history: List<AuditMeta>): String? = history
        .filter { it.id != current.id && it.createdAtMs < current.createdAtMs &&
            it.targetPackage == current.targetPackage && it.sample == current.sample }
        .maxWithOrNull(compareBy<AuditMeta> { it.createdAtMs }.thenBy { it.id })?.id

    fun compare(current: AuditBundle, previous: AuditBundle?): AuditComparisonResult {
        fun unavailable(reason: String) = AuditComparisonResult.Unavailable(reason)
        val old = previous ?: return unavailable("No earlier saved audit of this app in the same sample/live mode is available.")
        if (old.meta.id == current.meta.id || old.meta.createdAtMs >= current.meta.createdAtMs)
            return unavailable("The comparison needs an earlier audit.")
        if (old.meta.targetPackage != current.meta.targetPackage || old.meta.sample != current.meta.sample)
            return unavailable("Different packages or sample/live modes cannot be compared automatically.")
        if (old.meta.plans.isEmpty() || old.meta.plans.toSet() != current.meta.plans.toSet())
            return unavailable("The audit plans changed. Run the same plans before comparing scores.")
        if (!current.meta.sample || old.meta.timingProfile != null || current.meta.timingProfile != null) {
            val known = setOf(AuditTimingPolicy.NORMAL, AuditTimingPolicy.FAST_PREVIEW)
            if (old.meta.timingProfile !in known || current.meta.timingProfile !in known)
                return unavailable("Capture timing was not recorded or is unknown. A score change cannot establish an improvement.")
            if (old.meta.timingProfile != current.meta.timingProfile)
                return unavailable("Capture timing profiles changed. Fast Preview and normal audits cannot establish a comparable score change.")
        }
        if (AuditPlanCoverage.gaps(old.sessions).isNotEmpty() || AuditPlanCoverage.gaps(current.sessions).isNotEmpty())
            return unavailable("An intended consent choice was not completed. Repeat the same plans before comparing scores.")
        val before = old.result.score
        val after = current.result.score
        if (before == null || after == null || before !in 0..100 || after !in 0..100 ||
            old.result.band == ScoreBand.INCONCLUSIVE || current.result.band == ScoreBand.INCONCLUSIVE)
            return unavailable("An inconclusive or unavailable score cannot establish a score change.")
        val oldChecks = old.result.findings.map { it.checkId }.toSet()
        val newChecks = current.result.findings.map { it.checkId }.toSet()
        return AuditComparisonResult.Available(old.meta.id, old.meta.createdAtMs, after - before,
            oldChecks - newChecks, newChecks - oldChecks, oldChecks intersect newChecks, before, after)
    }

    /** The version each audit recorded: the meta (new audits), else the sessions' manifest facts (older audits). */
    fun version(bundle: AuditBundle): AppVersion? = CheckedApps.version(bundle.meta) ?: AppVersion.ofSessions(bundle.sessions)

    /**
     * Non-null when both audits recorded a version code and the codes differ: the app was updated (or downgraded)
     * between the two checks. Unrecorded versions are never called an update.
     */
    fun update(current: AuditBundle, previous: AuditBundle?): VersionUpdate? {
        val old = previous?.let(::version) ?: return null
        val now = version(current) ?: return null
        if (old.code == null || now.code == null || old.code == now.code) return null
        return VersionUpdate(old, now)
    }
}
