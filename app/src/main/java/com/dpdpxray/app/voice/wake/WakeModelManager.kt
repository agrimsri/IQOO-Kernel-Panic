package com.dpdpxray.app.voice.wake

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds, downloads and verifies the wake-word model under `getExternalFilesDir("wake")`.
 *
 * Nothing is fetched until the user taps Download. The phone's DownloadManager fetches the official archive (it shows
 * its own progress notification); the archive is then checked against the pinned size, SHA-256 and MD5 before it is
 * unpacked. A developer can instead `adb push` the official zip (verified the same way) or an unpacked folder
 * (usable, but reported as not verified). See docs/HEY_TRACEVEIL.md.
 */
class WakeModelManager private constructor(private val app: Context) {
    val spec = WakeModelSpec.SMALL_EN_US
    private val prefs = app.getSharedPreferences("wake_model", Context.MODE_PRIVATE)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installing = AtomicBoolean(false)
    private val downloads: DownloadManager? get() = app.getSystemService(DownloadManager::class.java)

    private val _status = MutableStateFlow<WakeModelStatus>(WakeModelStatus.Checking)
    val status: StateFlow<WakeModelStatus> = _status.asStateFlow()

    /** Null when the phone's app-specific storage is unavailable (for example, removed or unmounted). */
    val wakeDir: File? get() = app.getExternalFilesDir("wake")
    val modelDir: File? get() = wakeDir?.let { File(it, spec.name) }

    fun isReady(): Boolean = modelDir?.let { WakeModelFiles.missingFiles(it, spec).isEmpty() } ?: false

    /** Re-reads the folder and any download in progress; installs a finished download or an adb-pushed archive. */
    fun refresh() {
        if (installing.get()) return
        val dir = wakeDir ?: return run { _status.value = WakeModelStatus.Missing("App storage is not available right now.") }
        val model = File(dir, spec.name)
        if (WakeModelFiles.missingFiles(model, spec).isEmpty()) {
            _status.value = WakeModelStatus.Ready(WakeModelFiles.isVerified(model, spec))
            return
        }
        val id = prefs.getLong(KEY_DOWNLOAD, NO_DOWNLOAD)
        if (id != NO_DOWNLOAD) return queryDownload(id, dir)
        val pushed = File(dir, spec.zipName)
        if (pushed.isFile) return install(pushed, dir, downloadId = null)
        val previous = _status.value
        _status.value = if (previous is WakeModelStatus.Missing) previous else WakeModelStatus.Missing()
    }

    /** Only from the explicit Download button. Returns a problem to show, or null when the download was queued. */
    fun startDownload(): String? {
        val dir = wakeDir ?: return "App storage is not available right now."
        if (prefs.getLong(KEY_DOWNLOAD, NO_DOWNLOAD) != NO_DOWNLOAD) return null
        val dm = downloads ?: return "This phone's download service is not available."
        File(dir, spec.zipName).delete()
        val request = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("TraceVeil wake-word model")
            .setDescription("${spec.name} from alphacephei.com (about 40 MB)")
            .setDestinationInExternalFilesDir(app, "wake", spec.zipName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = runCatching { dm.enqueue(request) }.getOrElse { return "The download could not be queued: ${it.message}" }
        prefs.edit { putLong(KEY_DOWNLOAD, id) }
        _status.value = WakeModelStatus.Downloading(null)
        return null
    }

    fun cancelDownload() {
        val id = prefs.getLong(KEY_DOWNLOAD, NO_DOWNLOAD)
        if (id != NO_DOWNLOAD) runCatching { downloads?.remove(id) }
        forgetDownload()
        _status.value = WakeModelStatus.Missing("The download was cancelled.")
    }

    /** Frees the space; refused while listening. */
    fun deleteModel() {
        if (WakeRuntime.state.value.active) return
        val dir = wakeDir ?: return
        io.launch {
            File(dir, spec.name).deleteRecursively()
            File(dir, spec.zipName).delete()
            _status.value = WakeModelStatus.Missing()
        }
    }

    private fun queryDownload(id: Long, dir: File) {
        val dm = downloads ?: return run { forgetDownload(); _status.value = WakeModelStatus.Missing("This phone's download service is not available.") }
        val cursor = runCatching { dm.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
        if (cursor == null) {
            forgetDownload()
            _status.value = WakeModelStatus.Missing()
            return
        }
        cursor.use { c ->
            if (!c.moveToFirst()) {
                forgetDownload()
                _status.value = WakeModelStatus.Missing("The download was removed.")
                return
            }
            val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            when (state) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.let { Uri.parse(it).path }?.let(::File)
                    val inside = local != null && local.canonicalPath.startsWith(dir.canonicalPath + File.separator)
                    install(if (inside) local!! else File(dir, spec.zipName), dir, id)
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    runCatching { dm.remove(id) }
                    forgetDownload()
                    _status.value = WakeModelStatus.Missing("The download failed (download service reason $reason). Check the connection and try again.")
                }
                else -> _status.value = WakeModelStatus.Downloading(
                    percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null,
                    paused = state == DownloadManager.STATUS_PAUSED,
                )
            }
        }
    }

    private fun install(zip: File, dir: File, downloadId: Long?) {
        if (!installing.compareAndSet(false, true)) return
        _status.value = WakeModelStatus.Verifying
        io.launch {
            try {
                val result = runCatching { WakeModelFiles.install(zip, dir, spec) }
                    .getOrElse { InstallResult.Rejected("The model could not be checked (${it.message}).") }
                if (downloadId != null) {
                    // The archive is already deleted (unpacked or rejected); this only clears the download record.
                    runCatching { downloads?.remove(downloadId) }
                    forgetDownload()
                }
                _status.value = when (result) {
                    InstallResult.Installed -> WakeModelStatus.Ready(verified = true)
                    is InstallResult.Rejected -> WakeModelStatus.Missing(result.reason)
                }
            } finally {
                installing.set(false)
            }
        }
    }

    private fun forgetDownload() = prefs.edit { remove(KEY_DOWNLOAD) }

    companion object {
        private const val KEY_DOWNLOAD = "download_id"
        private const val NO_DOWNLOAD = -1L

        @Volatile private var instance: WakeModelManager? = null

        fun get(context: Context): WakeModelManager =
            instance ?: synchronized(this) { instance ?: WakeModelManager(context.applicationContext).also { instance = it } }
    }
}
