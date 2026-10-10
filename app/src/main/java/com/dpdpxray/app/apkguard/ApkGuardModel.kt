package com.dpdpxray.app.apkguard

import com.dpdpxray.core.apk.ApkGuardReport
import com.dpdpxray.core.apk.ApkModelSignal
import com.dpdpxray.core.ml.PermissionRiskModel

/**
 * The bundled permission-profile model for APK Guard results. It is read from the app's resources once, on first use,
 * which happens inside the result screen's IO load, never on the main thread. If it cannot be read the result simply
 * has no model card; the rule signals never depend on it. TrackerLens (the hostname model) is not shown anywhere.
 */
object ApkGuardModel {
    private val model: PermissionRiskModel? by lazy { runCatching { PermissionRiskModel.loadDefault() }.getOrNull() }

    /** The loaded model, or null when it could not be read. */
    fun permissionRisk(): PermissionRiskModel? = model

    /** [report] plus one INFERRED "Permission profile model" card; unchanged if the model is unavailable. */
    fun withModelCard(report: ApkGuardReport): ApkGuardReport = model?.let { ApkModelSignal.attach(report, it) } ?: report
}
