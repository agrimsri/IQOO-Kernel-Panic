package com.dpdpxray.app.autoguard

import com.dpdpxray.app.apkguard.InstallAlert
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.update.AppVersion
import com.dpdpxray.core.update.UpdateKind
import com.dpdpxray.core.update.UpdatedApp

/**
 * What an Update Watch notification says. It reports that the version changed and what the last check found, and offers
 * a re-check. It never says what changed inside the app: that is known only after a new check is compared with the old.
 */
object UpdateAlertPolicy {
    private const val MAX_LABEL = 60

    fun alert(item: UpdatedApp): InstallAlert {
        val name = displayName(item.label, item.packageName)
        val change = item.versionChange
        val now = AppVersion.label(item.newVersion)
        val title = when (item.kind) {
            UpdateKind.UPDATED -> "$name updated ($change)"
            UpdateKind.DOWNGRADED -> "$name changed to an older version ($change)"
            UpdateKind.VERSION_NOT_RECORDED -> "$name updated (now $now)"
        }
        val titleHi = when (item.kind) {
            UpdateKind.UPDATED -> "$name अपडेट हुआ ($change)"
            UpdateKind.DOWNGRADED -> "$name पुराने वर्ज़न पर गया ($change)"
            UpdateKind.VERSION_NOT_RECORDED -> "$name अपडेट हुआ (अब $now)"
        }
        val unknownThen = item.kind == UpdateKind.VERSION_NOT_RECORDED
        val last = lastCheck(item.lastScore, item.lastBand).let { if (it == INCONCLUSIVE) "inconclusive" else it }
        val lastHi = lastCheckHi(item.lastScore, item.lastBand)
        val scored = item.lastScore != null && item.lastBand != ScoreBand.INCONCLUSIVE
        return InstallAlert(
            title = title,
            text = "Last check: $last" + (if (unknownThen) " (version not recorded then)" else "") + ". Tap to re-check consent.",
            hindi = "$titleHi। पिछली जाँच: $lastHi" + (if (unknownThen) " (तब का वर्ज़न दर्ज नहीं था)" else "") + "। सहमति दोबारा जाँचने के लिए टैप करें।",
            spokenEn = "$name was updated. " +
                (if (scored) "Its last check scored ${item.lastScore}, ${item.lastBand.label.lowercase()}. " else "Its last check was inconclusive. ") +
                "Open TraceVeil to check it again.",
            spokenHi = "$name अपडेट हुआ। " +
                (if (scored) "पिछली जाँच में ${item.lastScore} अंक, ${bandHi(item.lastBand)}। " else "पिछली जाँच अनिर्णायक थी। ") +
                "दोबारा जाँचने के लिए TraceVeil खोलें।",
        )
    }

    private const val INCONCLUSIVE = "Inconclusive"

    /** "8 · At risk", or "Inconclusive" when the last check had no score. Also used by the in-app card. */
    fun lastCheck(score: Int?, band: ScoreBand): String =
        if (score == null || band == ScoreBand.INCONCLUSIVE) INCONCLUSIVE else "$score · ${band.label}"

    private fun lastCheckHi(score: Int?, band: ScoreBand): String =
        if (score == null || band == ScoreBand.INCONCLUSIVE) bandHi(ScoreBand.INCONCLUSIVE) else "$score · ${bandHi(band)}"

    private fun bandHi(band: ScoreBand) = when (band) {
        ScoreBand.READY -> "तैयार"
        ScoreBand.NEEDS_WORK -> "सुधार की ज़रूरत"
        ScoreBand.AT_RISK -> "जोखिम में"
        ScoreBand.INCONCLUSIVE -> "अनिर्णायक"
    }

    private fun displayName(label: String?, packageName: String): String =
        (label?.trim()?.takeIf { it.isNotEmpty() } ?: packageName).let { if (it.length > MAX_LABEL) it.take(MAX_LABEL - 1) + "…" else it }
}

/**
 * Which updates were already alerted: one entry per (package, installed versionCode) with the time it was alerted,
 * shared by the background check and the live receiver, so one update is alerted once per check it is compared with.
 * Checking the app again (a newer audit) makes a later install of that version news again. The [LIMIT] most recent
 * entries are kept.
 */
object UpdateAlertLedger {
    const val LIMIT = 300

    data class Key(val packageName: String, val versionCode: Long)

    /** null when Android reported no version code for the installed app; such an item is listed in the app, never alerted. */
    fun key(item: UpdatedApp): Key? = item.newVersion?.code?.let { Key(item.packageName, it) }

    /** [lastCheckedAtMs]: when the audit this update is compared with was made (0 = ignore). */
    fun isNew(ledger: Map<Key, Long>, key: Key, lastCheckedAtMs: Long = 0): Boolean = ledger[key]?.let { it < lastCheckedAtMs } ?: true

    /** [ledger] with [key] recorded at [at] (any increasing number; the oldest entries are dropped first). */
    fun record(ledger: Map<Key, Long>, key: Key, at: Long): Map<Key, Long> {
        val all = ledger + (key to at)
        if (all.size <= LIMIT) return all
        return all.entries.sortedByDescending { it.value }.take(LIMIT).associate { it.key to it.value }
    }

    /** Stored as "package<TAB>versionCode<TAB>recordedAt". */
    fun encode(ledger: Map<Key, Long>): Set<String> = ledger.map { (k, at) -> "${k.packageName}\t${k.versionCode}\t$at" }.toSet()

    /** Malformed entries are dropped, never guessed. */
    fun decode(stored: Set<String>?): Map<Key, Long> = stored.orEmpty().mapNotNull { line ->
        val parts = line.split('\t')
        val pkg = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val code = parts.getOrNull(1)?.toLongOrNull()
        val at = parts.getOrNull(2)?.toLongOrNull()
        if (parts.size != 3 || pkg == null || code == null || at == null) null else Key(pkg, code) to at
    }.toMap()
}

/** When an update is alerted. */
object UpdateAlertPlan {
    private const val PACKAGE_ADDED = "android.intent.action.PACKAGE_ADDED"

    /** [post]: alert now. [seed]: record as seen without alerting (old news on a first run). */
    data class Plan(val post: List<UpdatedApp>, val seed: List<UpdatedApp>)

    /**
     * [items] are the checked apps whose installed version differs from their latest check. The first check ever, or
     * the first after the new-app check was switched (back) on ([NewAppWatch.Read.Baseline]), and the first run of
     * Update Watch on this phone ([initialised] false) only record what is already updated: those are old news (the
     * in-app card still lists them). After a same-boot read only packages Android reported as changed are alerted;
     * after a restart every item not yet alerted is. Each (package, version code) is alerted once.
     */
    fun plan(read: NewAppWatch.Read, items: List<UpdatedApp>, changedNames: Collection<String>, ledger: Map<UpdateAlertLedger.Key, Long>, initialised: Boolean): Plan {
        val fresh = items.filter { item -> UpdateAlertLedger.key(item)?.let { UpdateAlertLedger.isNew(ledger, it, item.lastAuditedAtMs) } == true }
        if (!initialised || read is NewAppWatch.Read.Baseline) return Plan(emptyList(), fresh)
        val changed = changedNames.toSet()
        val post = if (read is NewAppWatch.Read.Since) fresh.filter { it.packageName in changed } else fresh
        return Plan(post, emptyList())
    }

    /** The live receiver: an update (PACKAGE_ADDED while replacing) of an app other than TraceVeil. */
    fun liveUpdate(action: String?, replacing: Boolean, packageName: String?, ownPackage: String): Boolean =
        action == PACKAGE_ADDED && replacing && !packageName.isNullOrBlank() && packageName != ownPackage
}
