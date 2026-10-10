package com.dpdpxray.app.voice.assistant

import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.audit.AuditProgress

/**
 * Starts a voice-requested check through the screen's own start ([startAudit], the app picker's path), but only when
 * no other check is running. The orchestrator silently ignores a second start, so without this the reply would say
 * the check started and the end-of-check follow-up would read out another app's result.
 */
internal object VoiceAuditStart {
    fun start(
        config: AuditConfig,
        progress: () -> AuditProgress,
        startAudit: (AuditConfig) -> Boolean,
        failure: () -> String?,
    ): UiOutcome {
        (progress() as? AuditProgress.Running)?.let { return UiOutcome.CheckRunning(it.config.targetLabel) }
        if (!startAudit(config)) return UiOutcome.Failed(failure())
        // Another caller may have started a check between the look above and this start; the orchestrator keeps that one.
        val now = progress()
        if (now is AuditProgress.Running && now.config !== config) return UiOutcome.CheckRunning(now.config.targetLabel)
        return UiOutcome.Done
    }
}
