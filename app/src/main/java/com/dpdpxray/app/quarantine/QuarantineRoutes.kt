package com.dpdpxray.app.quarantine

import androidx.navigation.NavController

/** Opens the quarantine screen for [packageName] (explainer first; the person must still tap Start). */
fun NavController.navigateToQuarantine(packageName: String) = navigate(QuarantineRoutes.forPackage(packageName))

/**
 * Navigation route of the quarantine runner. Other features (e.g. APK Guard) open it with
 * `nav.navigate(QuarantineRoutes.forPackage(pkg))`; opening the screen never starts a run by itself.
 */
object QuarantineRoutes {
    const val ROUTE = "quarantine/{pkg}"
    const val ARG = "pkg"

    fun forPackage(packageName: String): String {
        require(isValidPackage(packageName)) { "An installed app's package name is required" }
        return ROUTE.replace("{pkg}", packageName)
    }

    /** Android package names: letters, digits, underscores and dots. Anything else could break the route. */
    fun isValidPackage(packageName: String): Boolean =
        packageName.length in 1..255 && packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*"))
}
