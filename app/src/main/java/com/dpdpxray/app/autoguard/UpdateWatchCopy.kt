package com.dpdpxray.app.autoguard

import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.update.AppVersion
import com.dpdpxray.core.update.UpdateKind
import com.dpdpxray.core.update.UpdatedApp

/** Update Watch text shown inside TraceVeil (Auto-guard setting and the "Updated since your last check" card). */
object UpdateWatchCopy {
    val switchTitle = Bi("Alert when a checked app updates", "जाँचा गया ऐप अपडेट हो तो सूचना दें")
    val switchDetail = Bi(
        "When an app you checked gets a new version, one notification shows the old and new version and your last result. " +
            "Tapping it opens the check for that app; nothing starts until you tap Start.",
        "जिस ऐप की आपने जाँच की है उसका नया वर्ज़न आने पर एक सूचना पुराना और नया वर्ज़न और आपका पिछला नतीजा दिखाती है। " +
            "टैप करने पर उस ऐप की जाँच खुलती है; Start दबाने तक कुछ शुरू नहीं होता।",
    )

    fun cardTitle(count: Int) = "Updated since your last check ($count)"

    const val CARD_NOTE = "These apps have a different version from the one you checked. Your last result describes the old version; " +
        "a new check shows what changed. The old check stays saved."

    const val RECHECK = "Re-check"

    fun versionLine(item: UpdatedApp): String = when (item.kind) {
        UpdateKind.UPDATED -> item.versionChange
        UpdateKind.DOWNGRADED -> "${item.versionChange} (older version)"
        UpdateKind.VERSION_NOT_RECORDED -> "Now ${AppVersion.label(item.newVersion)} · version at your check not recorded"
    }

    fun lastCheckLine(item: UpdatedApp) = "Last check: ${UpdateAlertPolicy.lastCheck(item.lastScore, item.lastBand)}"

    /** The score in the chip; "?" when the last check was inconclusive. */
    fun scoreChip(item: UpdatedApp): String =
        if (item.lastScore == null || item.lastBand == ScoreBand.INCONCLUSIVE) "?" else item.lastScore.toString()
}
