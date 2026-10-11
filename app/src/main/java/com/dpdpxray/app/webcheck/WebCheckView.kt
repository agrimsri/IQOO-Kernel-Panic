package com.dpdpxray.app.webcheck

import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.trackers.TrackerCatalog
import com.dpdpxray.core.web.RawWebRequest
import com.dpdpxray.core.web.WebBannerDetection
import com.dpdpxray.core.web.WebTrackers
import com.dpdpxray.core.web.WebUrl

/** The live screen's three numbers. */
data class WebLiveCounts(val requests: Int, val toTrackers: Int, val beforeChoice: Int)

/** One run's lines on the "Website evidence" card. */
data class WebRunSummary(val plan: String, val lines: List<Bi>)

/** What the website screens show, worked out without Compose so it is unit tested. */
object WebCheckView {
    /**
     * Requests so far, those to catalogued trackers (by host, or by a documented tracking path), and of those the ones made
     * before the consent choice (all of them while no choice has been made).
     */
    fun counts(requests: List<RawWebRequest>, catalog: TrackerCatalog, choiceNanos: Long?, trackers: WebTrackers = WebTrackers.default): WebLiveCounts {
        val cache = HashMap<String, Boolean>()
        val tracking = requests.filter { r ->
            trackers.pathRule(r.url) != null || (WebUrl.hostOf(r.url)?.let { h -> cache.getOrPut(h) { catalog.classify(h).category.isTracking } } ?: false)
        }
        return WebLiveCounts(requests.size, tracking.size, tracking.count { choiceNanos == null || it.tNanos < choiceNanos })
    }

    /** Plain names of the three runs, in the order they run. */
    val planNames = listOf(Bi("Don't touch", "न छुएँ"), Bi("Reject", "Reject"), Bi("Accept", "Accept"))

    /** Per run: requests, cookies, storage, the banner and the choice, in both languages. */
    fun summary(sessions: List<AuditSession>): List<WebRunSummary> = sessions.mapNotNull { s ->
        val w = s.web ?: return@mapNotNull null
        val trackerRequests = w.requests.count { it.trackerEntity != null }
        val third = w.requests.count { it.thirdParty }
        val trackingCookies = w.cookies.count { it.trackerEntity != null }
        val banner = w.banner
        val bannerLine = when {
            banner == null -> Bi("Banner: not found in the parts of the page TraceVeil can read", "बैनर: पेज के पढ़े जा सकने वाले हिस्सों में नहीं मिला")
            banner.detection == WebBannerDetection.FRAME_ONLY -> Bi(
                "Banner: inside a frame" + (banner.frameHost?.let { " from $it" } ?: "") + "; its buttons could not be read",
                "बैनर: एक फ़्रेम के अंदर" + (banner.frameHost?.let { " ($it)" } ?: "") + "; उसके बटन पढ़े नहीं जा सके",
            )
            banner.detection == WebBannerDetection.CMP_ELEMENT -> Bi("Banner: ${banner.cmp}, found by its own element", "बैनर: ${banner.cmp}, उसके अपने एलिमेंट से मिला")
            else -> Bi("Banner: found by its wording", "बैनर: उसके शब्दों से मिला")
        }
        val choice = s.consentChoice
        val choiceLine = when {
            choice == null -> Bi("Choice: none recorded", "चुनाव: कोई दर्ज नहीं")
            else -> {
                val who = if (w.agentTaps) Bi("TraceVeil tapped", "TraceVeil ने टैप किया") else Bi("You tapped", "आपने टैप किया")
                val what = choice.nodeText?.takeIf { it.isNotBlank() } ?: if (choice.kind == ConsentKind.REJECT) "Reject" else "Accept"
                val at = String.format(java.util.Locale.US, "%.1f", (choice.tNanos - s.startedAtNanos) / 1e9)
                Bi("Choice: ${who.en} \"$what\" at $at s", "चुनाव: ${who.hi} \"$what\" ($at सेकंड पर)")
            }
        }
        WebRunSummary(
            s.plan.label,
            listOf(
                Bi(
                    "Requests: ${w.requests.size} · $trackerRequests to trackers · $third to other sites" + (if (w.requestsDropped > 0) " · ${w.requestsDropped} not kept" else ""),
                    "रिक्वेस्ट: ${w.requests.size} · $trackerRequests ट्रैकरों को · $third दूसरी साइटों को" + (if (w.requestsDropped > 0) " · ${w.requestsDropped} नहीं रखी गईं" else ""),
                ),
                Bi(
                    "Cookies: ${w.cookies.size} · ${w.cookies.count { it.thirdParty }} third-party · $trackingCookies tracking (values not stored)",
                    "कुकी: ${w.cookies.size} · ${w.cookies.count { it.thirdParty }} थर्ड-पार्टी · $trackingCookies ट्रैकिंग (वैल्यू सेव नहीं)",
                ),
                Bi("Storage keys: ${w.storage.size}", "स्टोरेज कुंजियाँ: ${w.storage.size}"),
                bannerLine,
                choiceLine,
            ) + w.errors.map { Bi(it, it) },
        )
    }

    /** True when the saved check is a website check. */
    fun isWeb(sessions: List<AuditSession>): Boolean = com.dpdpxray.core.web.WebAudit.isWeb(sessions)
}
