package com.dpdpxray.core.web

import com.dpdpxray.core.fixes.FixTemplate
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.CheckId

/**
 * "How to fix" for a website check. The app fix templates change Android SDK settings, which do not apply to a site, so a
 * website check gets these instead: general steps for the site's tags and banner, marked unverified (check the exact
 * API in the vendor's documentation for your setup).
 */
object WebFixes {
    val TAGS = FixTemplate(
        id = "web-consent-gate", kind = "web", title = "Load tracking tags only after consent",
        appliesTo = listOf(CheckId.C1, CheckId.C2, CheckId.NC, CheckId.W1, CheckId.W2),
        code = """
            // Google tag (consent mode): set the defaults before any Google tag loads...
            gtag('consent', 'default', {
              ad_storage: 'denied', analytics_storage: 'denied', ad_user_data: 'denied', ad_personalization: 'denied'
            });
            // ...and grant only after the person taps Accept:
            gtag('consent', 'update', {
              ad_storage: 'granted', analytics_storage: 'granted', ad_user_data: 'granted', ad_personalization: 'granted'
            });

            // Meta Pixel: hold events until Accept, and revoke on Reject.
            fbq('consent', 'revoke');   // before fbq('init', ...)
            fbq('consent', 'grant');    // after Accept
        """.trimIndent(),
        steps = listOf(
            "Do not put analytics or advertising tags straight into the page. Let your consent tool or tag manager fire each tag only after the matching choice.",
            "With Google's consent mode in its advanced setup, Google's tag still sends cookieless requests while consent is denied. TraceVeil counts them as tracker requests; the basic setup holds the tag until Accept.",
            "On Reject, stop the tags and remove the cookies they already set.",
            "Run the website check again: after Reject there should be no tracker requests and no new tracking cookies.",
        ),
        docUrl = "https://developers.google.com/tag-platform/security/guides/consent",
        verified = false,
    )

    val BANNER = FixTemplate(
        id = "web-banner-choice", kind = "web", title = "Make Reject as easy as Accept, with nothing pre-ticked",
        appliesTo = listOf(CheckId.C3, CheckId.C4, CheckId.C5),
        steps = listOf(
            "Put \"Reject all\" on the banner's first screen next to \"Accept all\", in the same size and style.",
            "Leave every optional purpose switched off until the person switches it on; only strictly necessary cookies may be always on.",
            "Consent tools such as OneTrust, Cookiebot and Didomi have a setting for a first-screen reject button; turn it on for every region you serve.",
        ),
        verified = false,
    )

    val POLICY = FixTemplate(
        id = "web-policy-link", kind = "web", title = "Link the privacy and cookie policy from the banner",
        appliesTo = listOf(CheckId.C15),
        steps = listOf("Add a visible link to the privacy notice and the cookie policy on the banner's first screen, so people can read them before they choose."),
        verified = false,
    )

    val ALL = listOf(TAGS, BANNER, POLICY)

    /** The templates that apply to the findings of [result], in list order. */
    fun forResult(result: AuditResult): List<FixTemplate> {
        val checks = result.findings.map { it.checkId }.toSet()
        return ALL.filter { t -> t.appliesTo.any { it in checks } }
    }
}
