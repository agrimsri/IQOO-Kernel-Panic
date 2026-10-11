package com.dpdpxray.core.update

import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ScoreBand

/**
 * An app version as Android reports it: [code] (versionCode) orders builds; [name] (versionName) is what people read.
 * Either may be missing; a version with neither is "not recorded" and is represented by null, never by a made-up value.
 */
data class AppVersion(val name: String?, val code: Long?) {
    /** "1.1", or "code 7" when the app has no version name. */
    fun label(): String = name?.takeIf { it.isNotBlank() } ?: code?.let { "code $it" } ?: NOT_RECORDED

    companion object {
        const val NOT_RECORDED = "version not recorded"

        /** null when Android reported neither a name nor a code. A blank name counts as no name. */
        fun of(name: String?, code: Long?): AppVersion? {
            val n = name?.trim()?.takeIf { it.isNotEmpty() }
            return if (n == null && code == null) null else AppVersion(n, code)
        }

        fun label(version: AppVersion?): String = version?.label() ?: NOT_RECORDED

        /**
         * "1.0 → 1.1". When both builds carry the same name the codes are added, so two different builds never read
         * as the same version: "1.0 (code 1) → 1.0 (code 2)".
         */
        fun change(old: AppVersion?, new: AppVersion?): String {
            val sameName = old?.name != null && old.name == new?.name && old.code != new.code
            fun text(v: AppVersion?) = if (sameName && v?.code != null) "${label(v)} (code ${v.code})" else label(v)
            return "${text(old)} → ${text(new)}"
        }

        /**
         * The audited app's version, read from its installed manifest when the audit started (check C13 stores it in each
         * session). null when no session recorded one (older audits, unreadable manifest, samples without manifest facts).
         */
        fun ofSessions(sessions: List<AuditSession>): AppVersion? =
            sessions.firstNotNullOfOrNull { s -> s.manifestFacts?.let { of(it.versionName, it.versionCode) } }
    }
}

/** One saved audit of an app, reduced to what the update watch needs. */
data class CheckedApp(
    val packageName: String,
    val label: String,
    val auditId: String,
    val auditedAtMs: Long,
    /** The version recorded at that audit; null = not recorded. */
    val version: AppVersion?,
    val score: Int?,
    val band: ScoreBand,
    val sample: Boolean,
)

/** What Android reports about the app installed now. [lastUpdateTimeMs] is PackageInfo.lastUpdateTime; null = unknown. */
data class InstalledApp(val packageName: String, val version: AppVersion?, val lastUpdateTimeMs: Long?)

enum class UpdateKind {
    /** A higher version code is installed than the one checked. */
    UPDATED,

    /** A lower version code is installed than the one checked. */
    DOWNGRADED,

    /** The checked version was not recorded, but Android says the app was installed or updated after that check. */
    VERSION_NOT_RECORDED,
}

/** "Updated since your last check": an app the person checked whose installed version is no longer the checked one. */
data class UpdatedApp(
    val packageName: String,
    val label: String,
    val kind: UpdateKind,
    /** null when the checked version was not recorded ([UpdateKind.VERSION_NOT_RECORDED]). */
    val oldVersion: AppVersion?,
    val newVersion: AppVersion?,
    val lastAuditId: String,
    val lastAuditedAtMs: Long,
    val lastScore: Int?,
    val lastBand: ScoreBand,
) {
    /** "1.0 → 1.1", or "version not recorded → 1.1". */
    val versionChange: String get() = AppVersion.change(oldVersion, newVersion)
}

/**
 * Pure rules for "Update Watch". TraceVeil compares the version recorded at the latest real (non-sample) audit of each
 * app with the version installed now. It only reports that the version changed; what changed in the app's behaviour is
 * known only after a new check, compared with the old one. Nothing here starts a check.
 */
object UpdateWatch {
    /** The latest non-sample audit per package (newest time, then the larger id, so the choice is stable). */
    fun latestChecks(checked: List<CheckedApp>): Map<String, CheckedApp> = checked.asSequence()
        .filter { !it.sample && it.packageName.isNotBlank() }
        .groupBy { it.packageName }
        .mapValues { (_, audits) -> audits.maxWith(compareBy<CheckedApp> { it.auditedAtMs }.thenBy { it.auditId }) }

    /** One app: its latest check against what is installed now; null when there is nothing to report. */
    fun evaluate(last: CheckedApp, now: InstalledApp?): UpdatedApp? {
        val installed = now?.version ?: return null // uninstalled, or Android reported no version
        val checkedCode = last.version?.code
        val kind = when {
            checkedCode == null -> {
                // Unknown version at the check: only Android's own update time can say the app changed after it.
                val updatedAt = now.lastUpdateTimeMs ?: return null
                if (updatedAt > last.auditedAtMs) UpdateKind.VERSION_NOT_RECORDED else return null
            }
            installed.code == null -> return null
            installed.code > checkedCode -> UpdateKind.UPDATED
            installed.code < checkedCode -> UpdateKind.DOWNGRADED
            else -> return null // same build
        }
        return UpdatedApp(
            packageName = last.packageName, label = last.label, kind = kind,
            oldVersion = last.version.takeIf { kind != UpdateKind.VERSION_NOT_RECORDED }, newVersion = installed,
            lastAuditId = last.auditId, lastAuditedAtMs = last.auditedAtMs, lastScore = last.score, lastBand = last.band,
        )
    }

    /** Every checked app whose installed version changed since its latest check, most recently checked first. */
    fun updatedSinceLastCheck(checked: List<CheckedApp>, installed: Map<String, InstalledApp>): List<UpdatedApp> =
        latestChecks(checked).values
            .mapNotNull { evaluate(it, installed[it.packageName]) }
            .sortedWith(compareByDescending<UpdatedApp> { it.lastAuditedAtMs }.thenBy { it.packageName })
}
