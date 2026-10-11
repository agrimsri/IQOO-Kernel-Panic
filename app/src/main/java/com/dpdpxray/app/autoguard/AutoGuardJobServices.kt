package com.dpdpxray.app.autoguard

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.util.Log
import com.dpdpxray.app.apkguard.ApkAlertSpeaker
import com.dpdpxray.app.apkguard.InstallAlert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

internal const val AUTOGUARD_LOG = "TraceVeilAutoGuard"

/**
 * Runs the background new-app check ([NewAppCheck]) for job ids [AutoGuardJobs.NEW_APPS_PERIODIC] and
 * [AutoGuardJobs.NEW_APPS_NOW]. Android starts it even when TraceVeil is closed. If the person switched spoken alerts
 * on, the job stays alive until the offline voice has finished (at most [SPEECH_TIMEOUT_MS]).
 */
class NewAppCheckJobService : AutoGuardJobService() {
    override suspend fun work(jobId: Int) {
        val outcome = NewAppCheck.run(applicationContext, fromJob = true)
        Log.i(AUTOGUARD_LOG, "new-app check job=$jobId read=${outcome?.read ?: "switched off"} alerted=${outcome?.alerted?.size ?: 0} updated=${outcome?.updated?.size ?: 0}")
        outcome?.speak?.let { speakAndWait(applicationContext, it) }
    }
}

/** Runs the weekly phone checkup ([PhoneCheckupRun]) for job id [AutoGuardJobs.WEEKLY_CHECKUP]. */
class PhoneCheckupJobService : AutoGuardJobService() {
    override suspend fun work(jobId: Int) {
        val result = PhoneCheckupRun.weekly(applicationContext)
        Log.i(AUTOGUARD_LOG, "phone checkup job=$jobId checked=${result?.appsChecked ?: "switched off or unreadable"} toReview=${result?.appsToReview ?: 0}")
    }
}

/**
 * Runs [work] off the main thread and tells Android when it is done. Each job id has its own coroutine, so Android
 * stopping one job (for example the 15-minute one) never cuts short another that happens to run at the same time.
 * Only package counts are logged, never app names.
 */
abstract class AutoGuardJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()

    protected abstract suspend fun work(jobId: Int)

    override fun onStartJob(params: JobParameters): Boolean {
        // Registered before it starts, so a very fast job cannot finish before it is tracked.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                work(params.jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed check must not crash TraceVeil in the background; the next run reads the same window again.
                Log.w(AUTOGUARD_LOG, "job ${params.jobId} failed: ${e.javaClass.simpleName}")
            } finally {
                running.remove(params.jobId, coroutineContext.job)
                jobFinished(params, false)
            }
        }
        running[params.jobId] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
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
