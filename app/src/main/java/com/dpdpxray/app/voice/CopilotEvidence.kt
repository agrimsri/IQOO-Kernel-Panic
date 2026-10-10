package com.dpdpxray.app.voice

import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.audit.AuditPlanCoverage
import com.dpdpxray.core.copilot.AuditEvidence
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession

/** The only audit data the copilot sees: the saved findings, the plans that ran, and whether it is a sample. */
fun copilotEvidence(meta: AuditMeta, sessions: List<AuditSession>, result: AuditResult) = AuditEvidence(
    label = meta.targetLabel,
    sample = meta.sample,
    result = result,
    plans = sessions.map { it.plan }.toSet(),
    completedPlans = AuditPlanCoverage.completedPlans(sessions),
)
