package com.dpdpxray.app.data

import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.update.AppVersion
import com.dpdpxray.core.update.CheckedApp
import com.dpdpxray.core.update.UpdateWatch

/**
 * Saved audits as Update Watch input: the latest real (non-sample) audit of each app, with the version recorded at
 * that audit. New audits carry the version in [AuditMeta]; for older ones it is read from that audit's sessions
 * (manifest facts, recorded since check C13), and only for the latest audit of each app.
 */
internal object CheckedApps {
    fun version(meta: AuditMeta): AppVersion? = AppVersion.of(meta.targetVersionName, meta.targetVersionCode)

    fun of(meta: AuditMeta): CheckedApp = CheckedApp(
        packageName = meta.targetPackage, label = meta.targetLabel, auditId = meta.id, auditedAtMs = meta.createdAtMs,
        version = version(meta), score = meta.score, band = meta.band, sample = meta.sample,
    )

    /** [sessionsOf] loads one audit's sessions by id (null when unreadable); it is called only when the meta has no version. */
    fun latest(metas: List<AuditMeta>, sessionsOf: (String) -> List<AuditSession>?): List<CheckedApp> =
        UpdateWatch.latestChecks(metas.map(::of)).values.map { app ->
            if (app.version != null) app else app.copy(version = sessionsOf(app.auditId)?.let(AppVersion::ofSessions))
        }
}
