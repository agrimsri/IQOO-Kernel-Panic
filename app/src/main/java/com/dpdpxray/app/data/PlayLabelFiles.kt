package com.dpdpxray.app.data

import com.dpdpxray.core.datasafety.PlayLabelRecord
import com.dpdpxray.core.report.AuditJson
import java.io.File

/**
 * The Play Data safety text a person attached to a saved check, stored as play_label.json in the audit's own folder (it
 * never leaves the phone unless the person exports the check). Optional: audits without the file have no label attached.
 */
object PlayLabelFiles {
    const val FILE = "play_label.json"

    fun save(dir: File, record: PlayLabelRecord) {
        dir.mkdirs()
        File(dir, FILE).writeText(AuditJson.json.encodeToString(PlayLabelRecord.serializer(), record), Charsets.UTF_8)
    }

    /** The attached label, or null when none is attached or the file cannot be read (the audit itself still opens). */
    fun load(dir: File): PlayLabelRecord? = File(dir, FILE).takeIf { it.isFile }?.let { f ->
        runCatching { AuditJson.json.decodeFromString(PlayLabelRecord.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun delete(dir: File): Boolean = File(dir, FILE).delete()
}
