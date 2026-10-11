package com.dpdpxray.core.apk

/** Why Android would refuse to install an APK file now. TraceVeil then explains instead of offering "Install anyway". */
enum class InstallBlock {
    /** Same package name as an installed app, different signing key: Android refuses it as an update. */
    DIFFERENT_SIGNER,

    /** A lower version code than the installed app: Android refuses a downgrade. */
    OLDER_VERSION,

    /** Built for an Android API level this version of Android no longer installs. */
    TOO_OLD_FOR_ANDROID,
}

enum class BannerTone { INFO, CAUTION, RISK }

/** One sentence shown above the decision buttons. */
data class PreInstallBanner(val tone: BannerTone, val text: Bilingual)

/**
 * What the "check before install" screen says next to its two buttons. [leanDontInstall] puts "Don't install" first
 * (something to review carefully, or a block); it never hides "Install anyway" unless [block] says Android would refuse.
 */
data class PreInstallAdvice(
    val relation: InstallRelation,
    val block: InstallBlock?,
    val leanDontInstall: Boolean,
    val banner: PreInstallBanner?,
)

/**
 * Pure advice for an APK file that is about to be installed, from its APK Guard report and the phone's Android API
 * level. It never says the file is safe or harmful: it states how the file relates to an installed app and whether
 * Android itself would refuse it.
 */
object PreInstallCheck {
    /** The lowest target API level Android installs: 23 on Android 14 (API 34), 24 on Android 15 (API 35) and later. */
    fun minimumTargetSdk(deviceSdk: Int): Int? = when {
        deviceSdk >= 35 -> 24
        deviceSdk >= 34 -> 23
        else -> null
    }

    fun advise(report: ApkGuardReport, deviceSdk: Int): PreInstallAdvice {
        val facts = report.facts
        val twin = facts.installedTwin
        val relation = SignerCheck.relation(facts.certSha256, facts.certHistorySha256, twin)
        val downgrade = SignerCheck.isDowngrade(facts.versionCode, twin) == true
        val minimum = minimumTargetSdk(deviceSdk)
        val target = facts.targetSdk
        val tooOld = minimum != null && target != null && target < minimum
        val block = when {
            relation == InstallRelation.DIFFERENT_SIGNER -> InstallBlock.DIFFERENT_SIGNER
            downgrade -> InstallBlock.OLDER_VERSION
            tooOld -> InstallBlock.TOO_OLD_FOR_ANDROID
            else -> null
        }
        val label = twin?.label ?: ""
        val fileVersion = versionLabel(facts.versionName, facts.versionCode)
        val installedVersion = versionLabel(twin?.versionName, twin?.versionCode)
        val banner = when (block) {
            InstallBlock.DIFFERENT_SIGNER -> PreInstallBanner(
                BannerTone.RISK,
                Bilingual(
                    "Red flag: this file is signed with a different key than your installed \"$label\", although it uses the same package name. " +
                        "A real update is signed with the same key, so Android will refuse to install it over your app. Do not uninstall your app to make room for it.",
                    "ख़तरे का संकेत: यह फ़ाइल आपके इंस्टॉल \"$label\" वाला पैकेज नाम इस्तेमाल करती है, पर किसी दूसरी चाबी से साइन है। " +
                        "असली अपडेट उसी चाबी से साइन होता है, इसलिए Android इसे आपके ऐप के ऊपर इंस्टॉल नहीं करेगा। इसके लिए अपना ऐप अनइंस्टॉल न करें।",
                ),
            )
            InstallBlock.OLDER_VERSION -> PreInstallBanner(
                BannerTone.CAUTION,
                Bilingual(
                    "This file is an older version ($fileVersion) than your installed \"$label\" ($installedVersion). Android does not install an older version over a newer one.",
                    "यह फ़ाइल आपके इंस्टॉल \"$label\" ($installedVersion) से पुराना वर्ज़न ($fileVersion) है। Android नए वर्ज़न के ऊपर पुराना वर्ज़न इंस्टॉल नहीं करता।",
                ),
            )
            InstallBlock.TOO_OLD_FOR_ANDROID -> PreInstallBanner(
                BannerTone.CAUTION,
                Bilingual(
                    "This app was built for Android API $target. This version of Android refuses to install apps built for API levels below $minimum.",
                    "यह ऐप Android API $target के लिए बना है। यह Android वर्ज़न API $minimum से नीचे के लिए बने ऐप इंस्टॉल नहीं करता।",
                ),
            )
            null -> when (relation) {
                InstallRelation.SAME_SIGNER -> PreInstallBanner(
                    BannerTone.INFO,
                    Bilingual(
                        "This file would update your installed \"$label\" ($installedVersion → $fileVersion). It is signed with the same key.",
                        "यह फ़ाइल आपके इंस्टॉल \"$label\" को अपडेट करेगी ($installedVersion → $fileVersion)। यह उसी चाबी से साइन है।",
                    ),
                )
                InstallRelation.ROTATED_SIGNER -> PreInstallBanner(
                    BannerTone.INFO,
                    Bilingual(
                        "This file would update your installed \"$label\" ($installedVersion → $fileVersion). It is signed with a newer key that your app's key was rotated to.",
                        "यह फ़ाइल आपके इंस्टॉल \"$label\" को अपडेट करेगी ($installedVersion → $fileVersion)। यह उस नई चाबी से साइन है जिस पर आपके ऐप की चाबी बदली गई थी।",
                    ),
                )
                InstallRelation.SIGNER_UNKNOWN -> PreInstallBanner(
                    BannerTone.CAUTION,
                    Bilingual(
                        "You already have \"$label\" installed. Whether this file is signed with the same key is not established, because a signing certificate could not be read.",
                        "\"$label\" आपके फ़ोन में पहले से इंस्टॉल है। यह फ़ाइल उसी चाबी से साइन है या नहीं, पता नहीं, क्योंकि एक साइनिंग सर्टिफ़िकेट पढ़ा नहीं जा सका।",
                    ),
                )
                InstallRelation.NEW_INSTALL, InstallRelation.DIFFERENT_SIGNER -> null
            }
        }
        return PreInstallAdvice(relation, block, leanDontInstall = block != null || report.summary.reviewCarefully > 0, banner = banner)
    }

    /** "2.2 (20)", "(20)" or "version not reported". */
    fun versionLabel(name: String?, code: Long?): String {
        val n = name?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            n != null && code != null -> "$n ($code)"
            n != null -> n
            code != null -> "($code)"
            else -> "version not reported"
        }
    }
}
