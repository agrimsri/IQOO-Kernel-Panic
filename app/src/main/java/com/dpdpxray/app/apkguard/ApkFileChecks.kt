package com.dpdpxray.app.apkguard

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.util.UUID

/**
 * Starts reading an APK file as soon as it arrives, while the sending app's read grant is certainly valid, and keeps
 * the last few results in memory only. The exact copy that was read is kept in TraceVeil's private cache so that
 * "Install anyway" installs those bytes and no others; it is deleted when the person taps "Don't install", after the
 * install, when the check drops out of memory, or after [ApkInspector.HELD_COPY_MS] at the latest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object ApkFileChecks {
    private const val KEEP = 6
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checks = LinkedHashMap<String, Deferred<ArchiveRead>>()
    private val heldFiles = HashMap<String, File>()

    fun start(context: Context, uri: Uri): String {
        val app = context.applicationContext
        val key = UUID.randomUUID().toString()
        val hold = File(ApkInspector.heldDir(app), "$key.apk")
        val job = scope.async { ApkInspector.readArchive(app, uri, hold) }
        remember(key, job, hold)
        return key
    }

    /** A check that failed before any file could be read (for example a file:// URI); its result screen shows [reason]. */
    fun failed(reason: String): String {
        val key = UUID.randomUUID().toString()
        remember(key, CompletableDeferred(ArchiveRead.Failed(reason)), null)
        return key
    }

    private fun remember(key: String, job: Deferred<ArchiveRead>, hold: File?) {
        val dropped = synchronized(checks) {
            checks[key] = job
            if (hold != null) heldFiles[key] = hold
            buildList {
                while (checks.size > KEEP) {
                    val oldest = checks.keys.first()
                    checks.remove(oldest)
                    heldFiles.remove(oldest)?.let(::add)
                }
            }
        }
        // A dropped check is no longer reachable from any screen, so its kept copy goes too.
        dropped.forEach { it.delete() }
    }

    /** Null when this key is unknown, for example after TraceVeil was closed by Android. */
    suspend fun await(key: String): ArchiveRead? = synchronized(checks) { checks[key] }?.await()

    /** The kept copy of a finished, successful check, with the hash and package it was checked as; null otherwise. */
    fun heldCopy(key: String): HeldCopy? {
        val ok = completed(key) as? ArchiveRead.Ok ?: return null
        val file = ok.held?.takeIf { it.isFile } ?: return null
        val sha = ok.app.fileSha256 ?: return null
        return HeldCopy(file, sha, ok.app.facts.packageName)
    }

    /** Deletes the kept copy of this check ("Don't install", or after the install). The result stays readable. */
    fun release(key: String) {
        (completed(key) as? ArchiveRead.Ok)?.held?.delete()
        synchronized(checks) { heldFiles.remove(key) }
    }

    private fun completed(key: String): ArchiveRead? {
        val job = synchronized(checks) { checks[key] } ?: return null
        return if (job.isCompleted) runCatching { job.getCompleted() }.getOrNull() else null
    }
}
