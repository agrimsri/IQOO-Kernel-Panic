package com.dpdpxray.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra

/**
 * The five top-level destinations shown in the bottom navigation bar. Each tab opens an existing route; the bar only
 * changes how people move between them, never what a screen does.
 */
enum class AppTab(val route: String, val label: String, val glyph: Glyph) {
    HOME("home", "Home", Glyph.HOME),
    CHECK("pick", "Check", Glyph.SEARCH),
    APK("apkguard", "APK Guard", Glyph.APK),
    REPORTS(AppTabs.REPORTS, "Reports", Glyph.REPORTS),
    MORE(AppTabs.MORE, "More", Glyph.MORE),
}

object AppTabs {
    /** All saved checks, newest first. */
    const val REPORTS = "reports"
    /** Settings, AI lab, Camera check, samples and the intro. */
    const val MORE = "more"

    /**
     * The tab a destination belongs to, or null for screens shown without the bar: onboarding, a running check,
     * results and their details, quarantine runs, the voice copilot and every other step inside a flow.
     */
    fun tabOf(route: String?): AppTab? = when (route?.substringBefore('?')) {
        AppTab.HOME.route -> AppTab.HOME
        AppTab.CHECK.route -> AppTab.CHECK
        AppTab.APK.route -> AppTab.APK
        REPORTS -> AppTab.REPORTS
        MORE -> AppTab.MORE
        else -> null
    }
}

/**
 * Opens [tab] the way a bottom bar should: Home stays the root, so Back from any tab returns to Home and Back from
 * Home leaves the app. Re-selecting the current tab does nothing. When Home is not on the back stack (TraceVeil was
 * opened straight into a screen by an APK file, a notification or a shortcut), the stack is rebuilt as Home + tab.
 */
fun NavController.openTab(tab: AppTab) {
    if (AppTabs.tabOf(currentBackStackEntry?.destination?.route) == tab) return
    val homeOnStack = runCatching { getBackStackEntry(AppTab.HOME.route) }.isSuccess
    if (!homeOnStack) {
        navigate(AppTab.HOME.route) { popUpTo(graph.id) { inclusive = true } }
    } else if (tab == AppTab.HOME) {
        popBackStack(AppTab.HOME.route, inclusive = false)
        return
    }
    if (tab != AppTab.HOME) navigate(tab.route) {
        popUpTo(AppTab.HOME.route)
        launchSingleTop = true
    }
}

/** Material 3 navigation bar in the film palette: line glyphs, scan-coloured selection, a hairline on top. */
@Composable
fun AppBottomBar(current: AppTab?, onSelect: (AppTab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(FilmExtra.hairline))
        NavigationBar(containerColor = FilmExtra.bar, contentColor = Film.bone, tonalElevation = 0.dp) {
            AppTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == current,
                    onClick = { onSelect(tab) },
                    icon = { GlyphIcon(tab.glyph, LocalContentColor.current, size = 24.dp) },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Film.scan, selectedTextColor = Film.scan, indicatorColor = Film.scan.copy(alpha = 0.16f),
                        unselectedIconColor = Film.boneDim, unselectedTextColor = Film.boneDim,
                    ),
                )
            }
        }
    }
}
