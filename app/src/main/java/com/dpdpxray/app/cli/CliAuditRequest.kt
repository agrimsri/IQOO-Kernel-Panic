package com.dpdpxray.app.cli

import com.dpdpxray.core.model.AuditPlan

/** A validated audit request from the debug-only CLI trigger. Inert in release builds: nothing in main registers a receiver. */
data class CliAuditRequest(
    val requestId: String,
    val targetPackage: String,
    val plans: List<AuditPlan>,
    val autoDrive: Boolean,
    val resetAppData: Boolean,
) {
    sealed interface Parsed {
        data class Ok(val request: CliAuditRequest) : Parsed
        data class Rejected(val reason: String) : Parsed
    }

    companion object {
        const val ACTION = "com.dpdpxray.app.debug.RUN_AUDIT"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_PLANS = "plans"
        const val EXTRA_AUTO_DRIVE = "auto_drive"
        const val EXTRA_RESET = "reset"

        const val RESULT_ACCEPTED = 1
        const val RESULT_REJECTED = 2

        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val DEFAULT_PLANS = listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT)

        fun isValidId(id: String?) = id != null && ID.matches(id)

        /** Reset and agent taps are opt-in: only the exact string "true" turns them on. */
        fun parse(requestId: String?, pkg: String?, plans: String?, autoDrive: String?, reset: String?): Parsed {
            if (!isValidId(requestId)) return Parsed.Rejected("request_id must be 1-64 letters, digits, '-' or '_'")
            if (pkg == null || !PACKAGE.matches(pkg)) return Parsed.Rejected("package is missing or not a valid application id")
            val parsedPlans = if (plans.isNullOrBlank()) DEFAULT_PLANS else {
                val names = plans.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                val mapped = names.map { n -> AuditPlan.entries.firstOrNull { it.name == n } ?: return Parsed.Rejected("unknown plan: ${n.lowercase()}") }
                if (mapped.isEmpty()) return Parsed.Rejected("plans is empty")
                if (mapped.toSet().size != mapped.size) return Parsed.Rejected("duplicate plan in plans")
                mapped
            }
            return Parsed.Ok(CliAuditRequest(requestId!!, pkg, parsedPlans, autoDrive == "true", reset == "true"))
        }
    }
}
