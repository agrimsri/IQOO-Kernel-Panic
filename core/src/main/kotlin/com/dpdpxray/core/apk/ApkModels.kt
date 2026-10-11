package com.dpdpxray.core.apk

/**
 * APK Guard input and output. APK Guard explains what an app *can* do from what Android shows about its package
 * (declared permissions, declared services, install source, signing certificate). It is not an antivirus: it never
 * decides that an app is harmful or harmless, and every signal carries an evidence label.
 */

/** Text shown in plain English and simple Hindi. Both are always filled. */
data class Bilingual(val en: String, val hi: String)

/** How much attention a signal deserves. Never a verdict about the app. */
enum class ApkSeverity(val label: Bilingual) {
    REVIEW_CAREFULLY(Bilingual("Review carefully", "ध्यान से जाँचें")),
    WORTH_KNOWING(Bilingual("Worth knowing", "जानने लायक")),
    INFO(Bilingual("Info", "जानकारी")),
}

/** How a signal is known. Unknown stays unknown: it is labelled, never filled with a default. */
enum class ApkEvidence(val label: Bilingual) {
    /** Read directly from the package or from Android (a declared permission, a service, the installer). */
    OBSERVED(Bilingual("Observed", "देखा गया")),
    /** A heuristic, such as a name that resembles a bank or government service. */
    INFERRED(Bilingual("Inferred", "अनुमान")),
    /** Something APK Guard cannot know, such as whether this is the official app. */
    NOT_ESTABLISHED(Bilingual("Not established", "पता नहीं")),
}

/** Powerful services an app can declare. Each needs the user to switch it on in Android settings before it works. */
enum class ServiceKind(val bindPermission: String) {
    ACCESSIBILITY("android.permission.BIND_ACCESSIBILITY_SERVICE"),
    NOTIFICATION_LISTENER("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
    VPN("android.permission.BIND_VPN_SERVICE"),
    DEVICE_ADMIN("android.permission.BIND_DEVICE_ADMIN"),
    INPUT_METHOD("android.permission.BIND_INPUT_METHOD"),
    AUTOFILL("android.permission.BIND_AUTOFILL_SERVICE"),
    ;

    companion object {
        /** The kind a service (or, for device admin, a receiver) declares through its binding permission. */
        fun fromBindPermission(permission: String?): ServiceKind? = entries.firstOrNull { it.bindPermission == permission }
    }
}

/** One declared service or receiver. [enabled] is null when Android does not let us read whether it is switched on. */
data class DeclaredComponent(val kind: ServiceKind, val className: String, val enabled: Boolean? = null)

enum class InstallSource {
    /** Android reports Google Play as the installer. */
    PLAY_STORE,
    /** Android reports a known app store other than Google Play. */
    OTHER_STORE,
    /** Installed from an APK file through Android's own package installer (or from a computer with adb). */
    FILE,
    /** Installed by an app that is neither a known store nor Android's package installer. */
    OTHER_APP,
    /** Android marks it as a system app with no separate installer. */
    PREINSTALLED,
    /** Android did not report an installer. */
    UNKNOWN,
    /** An APK file that is being checked before installation. */
    NOT_INSTALLED,
    ;

    /** True for every source except Google Play and the phone's own system image. */
    val offPlay: Boolean get() = this != PLAY_STORE && this != PREINSTALLED
}

data class InstallOrigin(val source: InstallSource, val installerPackage: String? = null, val storeName: String? = null)

/**
 * An installed app with the same package name as an APK file being checked. [versionCode] and [versionName] are the
 * installed version; null when Android did not report them.
 */
data class InstalledTwin(
    val label: String,
    val certSha256: List<String>,
    val versionCode: Long? = null,
    val versionName: String? = null,
)

data class ApkFacts(
    val packageName: String,
    val label: String?,
    val versionName: String?,
    val versionCode: Long?,
    val targetSdk: Int?,
    /** Permissions the package declares (asks for). */
    val permissions: Set<String>,
    /** Runtime permissions Android reports as granted now; null when not knowable (an APK file). */
    val grantedPermissions: Set<String>? = null,
    val components: List<DeclaredComponent> = emptyList(),
    val origin: InstallOrigin,
    /** Upper-case hex SHA-256 of each signing certificate; empty when Android returned none. */
    val certSha256: List<String> = emptyList(),
    /** X.500 subject of each signing certificate, when readable. */
    val signerSubjects: List<String> = emptyList(),
    /**
     * Upper-case hex SHA-256 of each certificate in the signing history (APK Signature Scheme v3 key rotation), oldest
     * first, including the current one; empty when the package has no rotated key or Android did not report it.
     */
    val certHistorySha256: List<String> = emptyList(),
    /** Whether the app shows an icon in the launcher; null when not knowable (an APK file). */
    val hasLauncherIcon: Boolean? = null,
    /** For an APK file: the installed app with the same package name, if any. */
    val installedTwin: InstalledTwin? = null,
)

data class ApkSignal(
    val id: String,
    val severity: ApkSeverity,
    val evidence: ApkEvidence,
    val title: Bilingual,
    val detail: Bilingual,
    /** What the user can do about it. */
    val advice: Bilingual,
    /** The exact package facts that triggered the signal (permission names, service classes, installer). */
    val proof: List<String> = emptyList(),
    /** Current on/off or allowed state when Android reports it. */
    val state: Bilingual? = null,
    /** Why APK Guard treats this as a signal, with its public source. */
    val basis: String? = null,
)

data class ApkSummary(val reviewCarefully: Int, val worthKnowing: Int, val headline: Bilingual, val disclaimer: Bilingual)

data class ApkGuardReport(val facts: ApkFacts, val signals: List<ApkSignal>, val summary: ApkSummary)
