package com.dpdpxray.app.data

import android.content.Context
import com.dpdpxray.core.datasafety.PlayLabelRecord
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.report.AuditJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class AuditMeta(
    val id: String,
    val targetPackage: String,
    val targetLabel: String,
    val createdAtMs: Long,
    val score: Int?,
    val band: ScoreBand,
    val findingCount: Int,
    val plans: List<String>,
    val sample: Boolean = false,
    /** Null means this older audit did not record its timing profile. */
    val timingProfile: String? = null,
    /** Why an inconclusive result has no score (core Inconclusive.Reason name); null when conclusive or not recorded. */
    val inconclusiveReason: String? = null,
    /**
     * The audited app's versionName and versionCode, read from its installed manifest when the audit started (the same
     * values the sessions' manifest facts hold). Both null = not recorded (audits saved before these fields existed,
     * samples, or an unreadable manifest); [CheckedApps] then falls back to the sessions. Written only when recorded,
     * so meta files and exported bundles without a version stay exactly as before.
     */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER)
    val targetVersionName: String? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER)
    val targetVersionCode: Long? = null,
    /**
     * The check's own options, kept so "Check again" can repeat it in one tap: whether the agent tapped the consent choice
     * and whether Google Play services lookups were included. null = not recorded (older audits); written only when set.
     */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER)
    val autoDrive: Boolean? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER)
    val includeGms: Boolean? = null,
)

data class AuditBundle(
    val meta: AuditMeta, val sessions: List<AuditSession>, val result: AuditResult, val dir: File,
    val networkReadings: List<SessionNetworkReadings> = emptyList(),
    /** The Play Data safety text attached to this check ("Play label vs what we saw"); null = none attached. */
    val playLabel: PlayLabelRecord? = null,
)

/** Stores each audit as plain JSON + Markdown under filesDir/audits/<id>/ (nothing leaves the device). */
class AuditRepository(private val context: Context) {
    private val root = File(context.filesDir, "audits").apply { mkdirs() }

    private val _audits = MutableStateFlow<List<AuditMeta>>(emptyList())
    val audits: StateFlow<List<AuditMeta>> = _audits.asStateFlow()

    fun newAuditDir(id: String): File = File(root, id).apply { mkdirs() }

    suspend fun refresh() = withContext(Dispatchers.IO) { _audits.value = listNow() }

    /** Every saved audit's meta, newest first, read from disk now (blocking; for callers already off the main thread). */
    fun listNow(): List<AuditMeta> = root.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { dir -> runCatching { AuditJson.json.decodeFromString<AuditMeta>(File(dir, META).readText()) }.getOrNull() }
        .sortedByDescending { it.createdAtMs }

    /** One saved audit's sessions (blocking); null when unreadable. */
    fun sessionsNow(id: String): List<com.dpdpxray.core.model.AuditSession>? =
        runCatching { AuditJson.decodeSessions(File(File(root, id), SESSIONS).readText()) }.getOrNull()

    suspend fun save(
        meta: AuditMeta, sessions: List<AuditSession>, result: AuditResult, markdown: String,
        networkReadings: List<SessionNetworkReadings> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        val dir = newAuditDir(meta.id)
        File(dir, SESSIONS).writeText(AuditJson.encodeSessions(sessions))
        File(dir, RESULT).writeText(AuditJson.encodeResult(result))
        File(dir, REPORT_MD).writeText(markdown)
        AuditNetworkReadings.save(dir, networkReadings)
        File(dir, META).writeText(AuditJson.json.encodeToString(AuditMeta.serializer(), meta))
        refresh()
    }

    suspend fun load(id: String): AuditBundle? = withContext(Dispatchers.IO) { loadNow(id) }

    /** Blocking [load], for callers already on an IO thread (the backup writer reads one audit at a time). */
    fun loadNow(id: String): AuditBundle? {
        val dir = File(root, id)
        return runCatching {
            AuditBundle(
                meta = AuditJson.json.decodeFromString(AuditMeta.serializer(), File(dir, META).readText()),
                sessions = AuditJson.decodeSessions(File(dir, SESSIONS).readText()),
                result = AuditJson.json.decodeFromString(AuditResult.serializer(), File(dir, RESULT).readText()),
                dir = dir,
                networkReadings = AuditNetworkReadings.load(dir),
                playLabel = PlayLabelFiles.load(dir),
            )
        }.getOrNull()
    }

    /** Attaches (or replaces) the Play label of a saved check. Blocking; call off the main thread. */
    fun savePlayLabel(id: String, record: PlayLabelRecord) = PlayLabelFiles.save(File(root, id), record)

    /** Removes the Play label of a saved check. Blocking; call off the main thread. */
    fun deletePlayLabel(id: String): Boolean = PlayLabelFiles.delete(File(root, id))

    fun markdownFile(id: String) = File(File(root, id), REPORT_MD)

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        refresh()
    }

    companion object {
        private const val META = "meta.json"
        private const val SESSIONS = "sessions.json"
        private const val RESULT = "result.json"
        const val REPORT_MD = "report.md"
    }
}
