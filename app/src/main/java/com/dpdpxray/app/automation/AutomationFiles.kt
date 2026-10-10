package com.dpdpxray.app.automation

import android.content.Context
import com.dpdpxray.core.automation.AutomationContract
import com.dpdpxray.core.automation.AutomationStatus
import com.dpdpxray.core.automation.AutomationStatuses
import java.io.File
import java.io.IOException

/**
 * Writes runner files under getExternalFilesDir(null)/automation/<runId>/ so `adb shell cat` and `adb pull` can read
 * them. The run id is validated before it becomes a folder name, and each file is replaced whole (write a temporary
 * file, then rename), so the laptop never reads half a status.
 */
class AutomationFiles(private val root: File) {
    fun runDir(runId: String): File {
        require(AutomationContract.isValidRunId(runId)) { "invalid run id" }
        return File(root, runId)
    }

    fun exists(runId: String): Boolean = runDir(runId).exists()

    fun writeStatus(status: AutomationStatus) {
        replace(File(runDir(status.runId), AutomationContract.STATUS_FILE), AutomationStatuses.encode(status))
    }

    /** Returns the exact bytes written, which are what the run's bundle signature covers. */
    fun writeBundle(runId: String, text: String): ByteArray = replace(File(runDir(runId), AutomationContract.BUNDLE_FILE), text)

    /** Keeps the [keep] most recent run folders plus [current]; other run folders are deleted. Nothing else is touched. */
    fun prune(keep: Int, current: String) {
        val runs = root.listFiles { f -> f.isDirectory && AutomationContract.isValidRunId(f.name) && f.name != current }.orEmpty()
        runs.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.deleteRecursively() }
    }

    private fun replace(target: File, text: String): ByteArray {
        val dir = target.parentFile ?: throw IOException("no folder for ${target.name}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.name}")
        val tmp = File(dir, target.name + ".tmp")
        val bytes = (if (text.endsWith("\n")) text else text + "\n").toByteArray(Charsets.UTF_8)
        tmp.writeBytes(bytes)
        // rename replaces atomically on Android; the delete fallback is for file systems that refuse to replace.
        if (!tmp.renameTo(target) && !(target.delete() && tmp.renameTo(target))) {
            tmp.delete()
            throw IOException("could not write ${target.name}")
        }
        return bytes
    }

    companion object {
        /** Null when shared storage is unavailable. */
        fun forContext(context: Context): AutomationFiles? =
            context.getExternalFilesDir(null)?.let { AutomationFiles(File(it, AutomationContract.RUNS_DIR)) }
    }
}
