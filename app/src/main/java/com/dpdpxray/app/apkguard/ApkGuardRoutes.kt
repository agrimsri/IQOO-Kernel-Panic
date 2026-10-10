package com.dpdpxray.app.apkguard

/**
 * APK Guard navigation routes. Home (owned by the UX work) navigates to the literal [HUB] route. Arguments are
 * validated so a crafted package name or key can never select a different destination.
 */
object ApkGuardRoutes {
    const val HUB = "apkguard"
    const val APP = "apkguard/app/{pkg}"
    const val FILE = "apkguard/file/{key}"

    /** The existing audit app picker; TraceVeil has no per-package audit setup route. */
    const val AUDIT_PICKER = "pick"

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val KEY = Regex("[A-Za-z0-9-]{1,64}")

    fun isPackageName(value: String?): Boolean = value != null && value.length <= 255 && PACKAGE.matches(value)

    fun app(pkg: String): String {
        require(isPackageName(pkg)) { "A valid package name is required" }
        return APP.replace("{pkg}", pkg)
    }

    fun file(key: String): String {
        require(KEY.matches(key)) { "A file-check key is required" }
        return FILE.replace("{key}", key)
    }

    /** The audit picker, already searched for [pkg], so the person sees just that app. Nothing starts by itself. */
    fun auditPicker(pkg: String): String {
        require(isPackageName(pkg)) { "A valid package name is required" }
        return "$AUDIT_PICKER?q=$pkg"
    }
}
