package com.dpdpxray.app.apkguard

import com.dpdpxray.core.apk.ApkSummary

data class InstallAlert(val title: String, val text: String, val hindi: String, val spokenEn: String, val spokenHi: String)

/** When a new-install alert is shown and what it says. It names the app and asks for a check; it never judges it. */
object InstallAlertPolicy {
    private const val PACKAGE_ADDED = "android.intent.action.PACKAGE_ADDED"
    private const val MAX_LABEL = 60

    fun shouldAlert(action: String?, replacing: Boolean, packageName: String?, ownPackage: String): Boolean =
        action == PACKAGE_ADDED && !replacing && !packageName.isNullOrBlank() && packageName != ownPackage

    fun alert(label: String?, summary: ApkSummary, packageName: String = ""): InstallAlert {
        val name = (label?.trim()?.takeIf { it.isNotEmpty() } ?: packageName).let { if (it.length > MAX_LABEL) it.take(MAX_LABEL - 1) + "…" else it }
        val headline = summary.headline.en.let { if (it.endsWith(".")) it else "$it." }
        val headlineHi = summary.headline.hi.let { if (it.endsWith("।")) it else "$it।" }
        val review = summary.reviewCarefully
        return InstallAlert(
            title = "New app installed: $name — check it before you open it",
            text = "$headline Tap to see what it can access.",
            hindi = "नया ऐप इंस्टॉल हुआ: $name — खोलने से पहले जाँच लें। $headlineHi",
            spokenEn = "New app installed: $name. Check it in TraceVeil before you open it." +
                if (review > 0) " TraceVeil found $review ${if (review == 1) "thing" else "things"} to review carefully." else "",
            spokenHi = "नया ऐप इंस्टॉल हुआ: $name. खोलने से पहले TraceVeil में जाँच लें।" +
                if (review > 0) " ध्यान से जाँचने लायक $review ${if (review == 1) "बात मिली" else "बातें मिलीं"}।" else "",
        )
    }
}
