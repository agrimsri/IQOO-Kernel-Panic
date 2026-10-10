package com.dpdpxray.app.audit

import com.dpdpxray.core.model.AuditPlan
import kotlinx.coroutines.delay

/**
 * [backgroundMs]: after the observation window the app is sent to the background (Home) and capture continues this
 * long (checks C12). 0 = no background step. It is added after the foreground windows, never taken from them.
 */
internal data class AuditTiming(val waitConsentMs: Long, val dwellMs: Long, val observeMs: Long, val silentMs: Long, val backgroundMs: Long = 0) {
    suspend fun observe(plan: AuditPlan) = delay(if (plan == AuditPlan.SILENT) silentMs else observeMs)
}

/** Per-run timings: a settings toggle cannot silently shorten Full evidence. */
internal object AuditTimingPolicy {
    const val NORMAL = "normal"
    const val FAST_PREVIEW = "fast_preview"

    fun profile(config: AuditConfig): String = if (config.fastPreview) FAST_PREVIEW else NORMAL
    fun forAudit(config: AuditConfig): AuditTiming = if (config.fastPreview)
        AuditTiming(12_000, 1_500, 8_000, 12_000)
    else AuditTiming(20_000, 2_500, 15_000, 25_000, backgroundMs = NORMAL_BACKGROUND_MS)

    /** Background observation per plan in a normal audit: about 30 s more for the three-plan Full audit. */
    const val NORMAL_BACKGROUND_MS = 10_000L

    fun coverageHint(profile: String?): String = when (profile) {
        FAST_PREVIEW -> "Fast Preview: single Accept-plan check with an 8-second observation window. Short capture may miss delayed traffic; Silent and Reject behaviour was not tested. Not a full audit or safety certificate."
        NORMAL -> "Normal capture: 15-second observation after each planned choice; 25 seconds for a Silent plan when included. Longer observation still does not establish complete traffic visibility."
        else -> "Capture timing profile not recorded. Do not assume this older audit used normal timing."
    }
}
