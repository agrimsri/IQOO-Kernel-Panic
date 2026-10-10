package com.dpdpxray.app.audit

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentKind

internal object ConsentPlanRetry {
    suspend fun <T> capture(plan: AuditPlan, packageName: String, resetAllowed: () -> Boolean,
        sessionOf: (T) -> AuditSession, attempt: suspend (Int) -> T): List<T> {
        val first = attempt(0)
        val session = sessionOf(first)
        val missedScreen = session.consentEvents.none {
            it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK
        }
        val resetFailed = session.notes.any {
            it.contains("Could not clear app data", ignoreCase = true) || it.contains("not cleared", ignoreCase = true)
        }
        val controlledDemo = packageName == "com.leakyshop.demo" || packageName == "com.leakyshop.demo.fixed"
        return if (plan != AuditPlan.SILENT && controlledDemo && missedScreen && !resetFailed && resetAllowed()) {
            listOf(first, attempt(1))
        } else listOf(first)
    }
}
