package com.dpdpxray.core.apk

import com.dpdpxray.core.model.Category
import com.dpdpxray.core.trackers.TrackerCatalog
import java.util.Locale

/**
 * The short summary a new-install notification carries: how many APK Guard signals deserve attention, the most
 * important capabilities in a few words, and which tracking SDKs were named in the app's code. Everything comes from
 * the deterministic APK Guard rules and the static code scan; nothing here judges the app.
 */
data class InstallHighlights(
    val reviewCarefully: Int,
    val worthKnowing: Int,
    /** Up to [MAX_CAPABILITIES] short phrases ("can read SMS"), review-carefully signals first. */
    val capabilities: List<Bilingual>,
    /**
     * Catalogue entities in a tracking category (analytics, advertising, attribution, social, engagement) whose class
     * names were found in the app's code. Null when the code was not read; an empty list is "none of the bundled
     * signatures found", which is not proof that no SDK is present.
     */
    val trackingSdks: List<String>?,
) {
    companion object {
        const val MAX_CAPABILITIES = 2

        /** [codeFound] is [CodeScanResult.found] (entity to package names) or null when the code was not read. */
        fun of(report: ApkGuardReport, codeFound: Map<String, List<String>>?, categoryOf: (String) -> Category? = ::catalogueCategory): InstallHighlights {
            val attention = report.signals.filter { it.severity != ApkSeverity.INFO }
                .sortedBy { it.severity.ordinal }
            val phrases = attention.map(::phrase).distinctBy { it.en }.take(MAX_CAPABILITIES)
            val tracking = codeFound?.keys?.filter { categoryOf(it)?.isTracking == true }
            return InstallHighlights(report.summary.reviewCarefully, report.summary.worthKnowing, phrases, tracking)
        }

        private val catalogue: TrackerCatalog by lazy { TrackerCatalog.loadDefault() }

        /** The bundled tracker catalogue's category for a code-signature entity, or null when it is not catalogued. */
        fun catalogueCategory(entity: String): Category? = catalogue.entryFor(entity)?.category

        /** A signal in a few lower-case words, for a notification line. Unknown ids fall back to the signal's title. */
        fun phrase(signal: ApkSignal): Bilingual = PHRASES[signal.id] ?: Bilingual(
            signal.title.en.replaceFirstChar { it.lowercase(Locale.ROOT) },
            signal.title.hi,
        )

        private val PHRASES = mapOf(
            "sms_read" to Bilingual("can read SMS", "SMS पढ़ सकता है"),
            "sms_send" to Bilingual("can send SMS", "SMS भेज सकता है"),
            "call_log" to Bilingual("can read call history", "कॉल हिस्ट्री पढ़ सकता है"),
            "phone" to Bilingual("can use phone features", "फ़ोन की सुविधाएँ इस्तेमाल कर सकता है"),
            "accessibility" to Bilingual("has an accessibility service", "Accessibility सर्विस है"),
            "notification_listener" to Bilingual("can ask to read notifications", "नोटिफ़िकेशन पढ़ने की अनुमति माँग सकता है"),
            "vpn" to Bilingual("has a VPN service", "VPN सर्विस है"),
            "device_admin" to Bilingual("can ask to be device admin", "डिवाइस एडमिन बनने की अनुमति माँग सकता है"),
            "input_method" to Bilingual("includes a keyboard", "कीबोर्ड है"),
            "autofill" to Bilingual("has an autofill service", "ऑटोफ़िल सर्विस है"),
            "overlay" to Bilingual("can draw over other apps", "दूसरे ऐप्स के ऊपर दिख सकता है"),
            "install_packages" to Bilingual("can ask to install apps", "ऐप इंस्टॉल करने की अनुमति माँग सकता है"),
            "contacts" to Bilingual("can read contacts", "कॉन्टैक्ट पढ़ सकता है"),
            "precise_location" to Bilingual("can ask for precise location", "सटीक लोकेशन माँग सकता है"),
            "camera_microphone" to Bilingual("can use camera or microphone", "कैमरा या माइक्रोफ़ोन इस्तेमाल कर सकता है"),
            "all_files" to Bilingual("can ask for all files", "सभी फ़ाइलों का ऐक्सेस माँग सकता है"),
            "install_source" to Bilingual("not from an app store", "ऐप स्टोर से नहीं"),
            "combo_otp_screen_control" to Bilingual("can see OTPs and control the screen", "OTP देख सकता है और स्क्रीन नियंत्रित कर सकता है"),
            "combo_sms_and_notifications" to Bilingual("two ways to see OTPs", "OTP देखने के दो रास्ते"),
            "combo_installer_control" to Bilingual("can install apps and control the phone", "ऐप इंस्टॉल और फ़ोन नियंत्रित कर सकता है"),
            "combo_contacts_files" to Bilingual("contacts plus photos or files", "कॉन्टैक्ट और फ़ोटो या फ़ाइलें"),
            "name_resembles_service" to Bilingual("name looks like a known service", "नाम किसी जानी-मानी सेवा जैसा"),
            "same_package_installed" to Bilingual("signed differently from your installed app", "आपके इंस्टॉल ऐप से अलग साइन"),
            "debug_certificate" to Bilingual("signed with a development certificate", "डेवलपमेंट सर्टिफ़िकेट से साइन"),
            "no_launcher_icon" to Bilingual("no icon in your app list", "ऐप लिस्ट में आइकन नहीं"),
            "old_target_sdk" to Bilingual("built for an old Android version", "पुराने Android वर्ज़न के लिए बना"),
        )
    }
}
