package com.dpdpxray.app.quarantine

import com.dpdpxray.core.quarantine.QuarantineReport
import com.dpdpxray.core.quarantine.QuarantineReports
import java.io.File

/** Saves each quarantine run as `<root>/<id>.json` (the app uses filesDir/quarantine). Nothing leaves the phone. */
class QuarantineStore(private val root: File) {

    /** Writes atomically (temp file then rename). Returns false if the id is unsafe or the write failed. */
    fun save(report: QuarantineReport): Boolean {
        if (!validId(report.id)) return false
        return runCatching {
            root.mkdirs()
            val tmp = File(root, "${report.id}.json.tmp")
            tmp.writeText(QuarantineReports.encode(report))
            val target = fileFor(report.id)
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "rename failed" }
            }
        }.isSuccess
    }

    fun load(id: String): QuarantineReport? {
        if (!validId(id)) return null
        val f = fileFor(id)
        if (!f.isFile) return null
        return runCatching { QuarantineReports.decode(f.readText()) }.getOrNull()
    }

    /** The most recent readable report for [targetPackage], or null. Corrupt files are skipped. */
    fun latestFor(targetPackage: String): QuarantineReport? =
        root.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .asSequence()
            .mapNotNull { f -> runCatching { QuarantineReports.decode(f.readText()) }.getOrNull() }
            .firstOrNull { it.targetPackage == targetPackage }

    fun fileFor(id: String): File = File(root, "$id.json")

    companion object {
        const val DIR = "quarantine"
        fun validId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,80}"))
    }
}
