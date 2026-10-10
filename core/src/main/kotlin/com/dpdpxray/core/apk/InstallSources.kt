package com.dpdpxray.core.apk

/** Android's own install-source hint (PackageInstaller.PACKAGE_SOURCE_*, API 33+), copied without Android types. */
enum class PackageSourceHint { UNSPECIFIED, OTHER, STORE, LOCAL_FILE, DOWNLOADED_FILE }

/**
 * Turns the installer that Android reports into a plain install source. The store list is a small, static catalogue
 * of well-known store installer package names; an installer that is not on it is reported as "another app" with its
 * package name, never assumed to be a store.
 */
object InstallSources {
    const val GOOGLE_PLAY = "com.android.vending"

    /** Installer package name to store name. Static catalogue; extend only with verified installer package names. */
    val KNOWN_STORES: Map<String, String> = mapOf(
        GOOGLE_PLAY to "Google Play",
        "com.sec.android.app.samsungapps" to "Samsung Galaxy Store",
        "com.amazon.venezia" to "Amazon Appstore",
        "com.huawei.appmarket" to "Huawei AppGallery",
        "com.xiaomi.market" to "Xiaomi GetApps",
        "com.xiaomi.mipicks" to "Xiaomi GetApps",
        "com.bbk.appstore" to "vivo App Store",
        "com.heytap.market" to "OPPO / realme App Market",
        "com.oppo.market" to "OPPO App Market",
        "org.fdroid.fdroid" to "F-Droid",
    )

    /** Android's own installer for APK files, and the adb shell. Vendors ship their own `*packageinstaller` packages. */
    private val FILE_INSTALLERS = setOf("com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.shell")

    fun classify(installer: String?, hint: PackageSourceHint = PackageSourceHint.UNSPECIFIED, isSystemApp: Boolean = false): InstallOrigin {
        val pkg = installer?.trim()?.takeIf { it.isNotEmpty() }
        val store = pkg?.let { KNOWN_STORES[it] }
        return when {
            pkg == GOOGLE_PLAY -> InstallOrigin(InstallSource.PLAY_STORE, pkg, store)
            store != null -> InstallOrigin(InstallSource.OTHER_STORE, pkg, store)
            hint == PackageSourceHint.LOCAL_FILE || hint == PackageSourceHint.DOWNLOADED_FILE -> InstallOrigin(InstallSource.FILE, pkg)
            pkg != null && (pkg in FILE_INSTALLERS || pkg.endsWith(".packageinstaller")) -> InstallOrigin(InstallSource.FILE, pkg)
            pkg != null -> InstallOrigin(InstallSource.OTHER_APP, pkg)
            isSystemApp -> InstallOrigin(InstallSource.PREINSTALLED)
            else -> InstallOrigin(InstallSource.UNKNOWN)
        }
    }
}
