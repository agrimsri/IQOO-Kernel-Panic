package com.dpdpxray.app.trust

import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dpdpxray.app.apkguard.ApkGuardRoutes
import com.dpdpxray.app.notices.NoticesRoutes

object TrustRoutes {
    /** Other screens (More, and the modes work) link here by this exact name. */
    const val ROUTE = "trust"

    /**
     * APK Guard's result screen for TraceVeil itself. APK Guard's own app list leaves TraceVeil out, so this explicit
     * self-check is the way in; the result screen uses the same rules as for any other app.
     */
    fun selfCheck(ownPackage: String): String = ApkGuardRoutes.app(ownPackage)
}

/** Registers the "Why you can trust TraceVeil" screen; one line in the app's NavHost. */
fun NavGraphBuilder.trustDestination(nav: NavController) {
    composable(TrustRoutes.ROUTE) {
        val ctx = LocalContext.current
        TrustScreen(
            onBack = { nav.popBackStack() },
            onSelfCheck = { nav.navigate(TrustRoutes.selfCheck(ctx.packageName)) },
            onNotices = { nav.navigate(NoticesRoutes.LIST) },
        )
    }
}
