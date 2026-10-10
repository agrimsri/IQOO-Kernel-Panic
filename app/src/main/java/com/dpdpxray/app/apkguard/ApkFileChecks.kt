package com.dpdpxray.app.apkguard

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.UUID

/**
 * Starts reading an APK file as soon as it arrives, while the sending app's read grant is certainly valid, and keeps
 * the last few results in memory only. Nothing about the file is saved; after the process ends the check is gone.
 */
object ApkFileChecks {
    private const val KEEP = 6
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checks = LinkedHashMap<String, Deferred<ArchiveRead>>()

    fun start(context: Context, uri: Uri): String {
        val app = context.applicationContext
        val key = UUID.randomUUID().toString()
        val job = scope.async { ApkInspector.readArchive(app, uri) }
        synchronized(checks) {
            checks[key] = job
            while (checks.size > KEEP) checks.remove(checks.keys.first())
        }
        return key
    }

    /** Null when this key is unknown, for example after TraceVeil was closed by Android. */
    suspend fun await(key: String): ArchiveRead? = synchronized(checks) { checks[key] }?.await()
}
