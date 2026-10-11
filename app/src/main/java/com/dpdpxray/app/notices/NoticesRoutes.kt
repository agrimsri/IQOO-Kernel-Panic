package com.dpdpxray.app.notices

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/** Routes of the "Open-source notices" screens, opened from More › About TraceVeil and from the trust screen. */
object NoticesRoutes {
    const val LIST = "notices"
    const val ARG = "id"
    const val COMPONENT = "notices/{$ARG}"

    fun component(id: String) = "notices/$id"
}

/** Registers the notices list and the per-component licence screen; one line in the app's NavHost. */
fun NavGraphBuilder.noticesDestinations(nav: NavController) {
    composable(NoticesRoutes.LIST) {
        NoticesScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(NoticesRoutes.component(it)) })
    }
    composable(NoticesRoutes.COMPONENT) { e ->
        NoticeDetailScreen(e.arguments?.getString(NoticesRoutes.ARG).orEmpty(), onBack = { nav.popBackStack() })
    }
}
