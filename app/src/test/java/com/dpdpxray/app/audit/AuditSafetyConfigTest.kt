package com.dpdpxray.app.audit

import com.dpdpxray.core.model.AuditPlan
import org.junit.Assert.*
import org.junit.Test

class AuditSafetyConfigTest {
    @Test fun `audit config never implicitly chooses consent for a real app`() {
        val config = AuditConfig("com.real.app", "Real app", listOf(AuditPlan.ACCEPT))
        assertFalse(config.autoDrive)
    }

    @Test fun `audit config never implicitly erases selected app data`() {
        val config = AuditConfig("com.real.app", "Real app", listOf(AuditPlan.ACCEPT))
        assertFalse(config.resetAppData)
    }
}
