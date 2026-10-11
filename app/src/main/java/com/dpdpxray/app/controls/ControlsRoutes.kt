package com.dpdpxray.app.controls

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dpdpxray.app.trust.TrustRoutes
import com.dpdpxray.app.ui.XrayViewModel

object ControlsRoutes {
    /** "Permissions & background": opened from Home, More and Settings by this exact name. */
    const val ROUTE = "controls"
}

/** The AI lab's route in the app's NavHost (the Setup items' "On-device AI" row opens it). */
private const val LAB_ROUTE = "lab"

/** Registers the "Permissions & background" page; one line in the app's NavHost. */
fun NavGraphBuilder.controlsDestination(nav: NavController, vm: XrayViewModel) {
    composable(ControlsRoutes.ROUTE) {
        ControlsScreen(
            vm, onBack = { nav.popBackStack() }, onLab = { nav.navigate(LAB_ROUTE) }, onTrust = { nav.navigate(TrustRoutes.ROUTE) },
        )
    }
}
