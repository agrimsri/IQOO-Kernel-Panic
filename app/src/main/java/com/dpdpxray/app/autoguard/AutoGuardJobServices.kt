package com.dpdpxray.app.autoguard

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.util.Log
import com.dpdpxray.app.apkguard.ApkAlertSpeaker
import com.dpdpxray.app.apkguard.InstallAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal const val AUTOGUARD_LOG = "TraceVeilAutoGuard"

/**
 * Runs the background new-app check ([NewAppCheck]) for job ids [AutoGuardJobs.NEW_APPS_PERIODIC] and
 * [AutoGuardJobs.NEW_APPS_NOW]. Android starts it even when TraceVeil is closed. If the person switched spoken alerts
 * on, the job stays alive until the offline voice has finished (at most [SPEECH_TIMEOUT_MS]).
 */
class NewAppCheckJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try {
                val outcome = NewAppCheck.run(applicationContext)
                Log.i(AUTOGUARD_LOG, "new-app check job=${params.jobId} read=${outcome?.read ?: "switched off"} alerted=${outcome?.alerted?.size ?: 0}")
                outcome?.speak?.let { speakAndWait(applicationContext, it) }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Runs the weekly phone checkup ([PhoneCheckupRun]) for job id [AutoGuardJobs.WEEKLY_CHECKUP]. */
class PhoneCheckupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try {
                val result = PhoneCheckupRun.weekly(applicationContext)
                Log.i(AUTOGUARD_LOG, "phone checkup job=${params.jobId} checked=${result?.appsChecked ?: "switched off"} toReview=${result?.appsToReview ?: 0}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private const val SPEECH_TIMEOUT_MS = 20_000L

/** Speaks [alert] with an installed offline voice and waits until it has finished, failed or timed out. */
internal suspend fun speakAndWait(context: Context, alert: InstallAlert) {
    withTimeoutOrNull(SPEECH_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            ApkAlertSpeaker.speak(context, alert.spokenEn, alert.spokenHi) { if (cont.isActive) cont.resume(Unit) }
        }
    }
}
