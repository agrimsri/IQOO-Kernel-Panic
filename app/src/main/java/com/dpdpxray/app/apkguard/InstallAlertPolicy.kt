package com.dpdpxray.app.apkguard

import com.dpdpxray.core.apk.ApkSummary
import com.dpdpxray.core.apk.InstallHighlights

data class InstallAlert(val title: String, val text: String, val hindi: String, val spokenEn: String, val spokenHi: String)

/** When a new-install alert is shown and what it says. It names the app and asks for a check; it never judges it. */
object InstallAlertPolicy {
    private const val PACKAGE_ADDED = "android.intent.action.PACKAGE_ADDED"
    private const val MAX_LABEL = 60

    fun shouldAlert(action: String?, replacing: Boolean, packageName: String?, ownPackage: String): Boolean =
        action == PACKAGE_ADDED && !replacing && !packageName.isNullOrBlank() && packageName != ownPackage

    /** The action button on a new-install alert. It opens the consent-check sheet for the app; nothing starts by itself. */
    const val CONSENT_CHECK_ACTION = "Run a consent check"

    /**
     * One new app's alert. [highlights] is the automatic static check (APK Guard rules plus the code scan): the title
     * counts what deserves attention, the text names the most important capabilities and the tracking SDKs found in the
     * code. Without [highlights] the text is the APK Guard headline.
     */
    fun alert(label: String?, summary: ApkSummary, packageName: String = "", highlights: InstallHighlights? = null): InstallAlert {
        val name = displayName(label, packageName)
        val review = summary.reviewCarefully
        val worth = summary.worthKnowing
        val countEn = when {
            review > 0 -> "$review ${if (review == 1) "thing" else "things"} to review carefully"
            worth > 0 -> "$worth ${if (worth == 1) "thing" else "things"} worth knowing"
            else -> null
        }
        val countHi = when {
            review > 0 -> "ध्यान से जाँचने लायक $review ${if (review == 1) "बात" else "बातें"}"
            worth > 0 -> "जानने लायक $worth ${if (worth == 1) "बात" else "बातें"}"
            else -> null
        }
        val factsEn = highlights?.let { h -> h.capabilities.map { it.en } + listOfNotNull(sdkPhraseEn(h.trackingSdks)) }.orEmpty()
        val factsHi = highlights?.let { h -> h.capabilities.map { it.hi } + listOfNotNull(sdkPhraseHi(h.trackingSdks)) }.orEmpty()
        val headline = summary.headline.en.let { if (it.endsWith(".")) it else "$it." }
        val headlineHi = summary.headline.hi.let { if (it.endsWith("।")) it else "$it।" }
        val text = if (factsEn.isEmpty()) "$headline Tap for details."
        else factsEn.joinToString("; ").replaceFirstChar { it.uppercase() } + ". Tap for details."
        val hindi = "नया ऐप: $name · " + (countHi ?: "खोलने से पहले जाँच लें") +
            if (factsHi.isEmpty()) " $headlineHi" else " (${factsHi.joinToString("; ")})।"
        return InstallAlert(
            title = "New app: $name · ${countEn ?: "check it before you open it"}",
            text = text,
            hindi = hindi,
            spokenEn = "New app installed: $name. Check it in TraceVeil before you open it." +
                if (review > 0) " TraceVeil found $review ${if (review == 1) "thing" else "things"} to review carefully." else "",
            spokenHi = "नया ऐप इंस्टॉल हुआ: $name. खोलने से पहले TraceVeil में जाँच लें।" +
                if (review > 0) " ध्यान से जाँचने लायक $review ${if (review == 1) "बात मिली" else "बातें मिलीं"}।" else "",
        )
    }

    /** "5 tracking SDKs in its code"; nothing when the code was not read or none of the bundled signatures matched. */
    private fun sdkPhraseEn(sdks: List<String>?): String? = sdks?.size?.takeIf { it > 0 }?.let { n -> "$n tracking ${if (n == 1) "SDK" else "SDKs"} in its code" }
    private fun sdkPhraseHi(sdks: List<String>?): String? = sdks?.size?.takeIf { it > 0 }?.let { n -> "इसके कोड में $n ट्रैकिंग SDK" }

    /**
     * One notification for several apps installed between two background checks (for example while a phone restores
     * its apps), instead of one notification each. It names the apps and asks for a check; tapping opens APK Guard.
     */
    fun combined(names: List<String>): InstallAlert {
        val count = names.size
        val shown = names.take(MAX_NAMED).map { displayName(it, it) }
        val rest = count - shown.size
        val listEn = shown.joinToString(", ") + if (rest > 0) " and $rest more" else ""
        val listHi = shown.joinToString(", ") + if (rest > 0) " और $rest अन्य" else ""
        return InstallAlert(
            title = "$count new apps installed — check them before you open them",
            text = "$listEn. Tap to see them in APK Guard.",
            hindi = "$count नए ऐप इंस्टॉल हुए — खोलने से पहले जाँच लें: $listHi।",
            spokenEn = "$count new apps were installed. Check them in TraceVeil before you open them.",
            spokenHi = "$count नए ऐप इंस्टॉल हुए। खोलने से पहले उन्हें TraceVeil में जाँच लें।",
        )
    }

    private const val MAX_NAMED = 4

    /**
     * A new install whose details Android did not return (rare). The person is still told about the install; nothing
     * is said about what the app can do, because nothing was read.
     */
    fun unread(label: String?, packageName: String): InstallAlert {
        val name = displayName(label, packageName)
        return InstallAlert(
            title = "New app installed: $name — check it before you open it",
            text = "TraceVeil could not read its details yet. Tap to check it in APK Guard.",
            hindi = "नया ऐप इंस्टॉल हुआ: $name — खोलने से पहले जाँच लें। TraceVeil अभी इसकी जानकारी नहीं पढ़ पाया।",
            spokenEn = "New app installed: $name. Check it in TraceVeil before you open it.",
            spokenHi = "नया ऐप इंस्टॉल हुआ: $name. खोलने से पहले TraceVeil में जाँच लें।",
        )
    }

    /** The app's name, or its package name when it has none, shortened so a notification title stays readable. */
    private fun displayName(label: String?, packageName: String): String =
        (label?.trim()?.takeIf { it.isNotEmpty() } ?: packageName).let { if (it.length > MAX_LABEL) it.take(MAX_LABEL - 1) + "…" else it }
}
