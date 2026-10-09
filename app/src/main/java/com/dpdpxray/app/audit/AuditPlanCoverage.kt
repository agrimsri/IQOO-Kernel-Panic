package com.dpdpxray.app.audit

import com.dpdpxray.core.model.*

internal object AuditPlanCoverage {
    private fun choice(session: AuditSession) = session.consentEvents.sortedBy { it.tNanos }
        .firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }?.kind

    private fun complete(attempts: List<AuditSession>): Boolean = when (attempts.first().plan) {
        AuditPlan.SILENT -> true
        AuditPlan.ACCEPT -> attempts.any { choice(it) == ConsentKind.ACCEPT }
        AuditPlan.REJECT -> attempts.any { choice(it) == ConsentKind.REJECT }
    }

    fun completedPlans(sessions: List<AuditSession>): Set<AuditPlan> = sessions.groupBy { it.plan }
        .filterValues { attempts -> attempts.groupBy { it.targetPackage }.values.all(::complete) }.keys

    fun gaps(sessions: List<AuditSession>): List<String> = sessions.groupBy { it.targetPackage to it.plan }
        .values.filterNot(::complete).map { attempts ->
            when (attempts.first().plan) {
                AuditPlan.REJECT -> "Plan B never reached refusal; C2 not tested. The score covers only the evidence recorded, not a completed refusal test."
                AuditPlan.ACCEPT -> "Plan A never reached Accept; the planned consent choice was not completed."
                AuditPlan.SILENT -> error("Silent has no required choice")
            }
        }.distinct()

    fun freshStateHint(sessions: List<AuditSession>): String? = if (sessions.any { session ->
        session.netEvents.isEmpty() && session.consentEvents.isEmpty() && session.notes.any {
            it.contains("not cleared", ignoreCase = true) || it.contains("Could not clear app data", ignoreCase = true)
        }
    }) "The app may have already opened or retained a consent choice. Without a fresh-state reset, launch-time DNS lookups and its consent dialog may not repeat. This is a possible cause, not proof of prior use." else null
}
