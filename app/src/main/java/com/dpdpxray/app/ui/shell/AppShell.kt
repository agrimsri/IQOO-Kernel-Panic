package com.dpdpxray.app.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.Motion

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

/**
 * The bottom bar: a flat surface with a hairline on top, a line glyph and a short label per tab. The selected tab takes
 * the accent colour; there is no pill. Each item is a tab to TalkBack, with its selected state.
 */
@Composable
fun AppBottomBar(current: AppTab?, onSelect: (AppTab) -> Unit) {
    val reduce = LocalReduceMotion.current
    val tabs = AppTab.entries
    Column(Modifier.fillMaxWidth().background(Film.raised)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(FilmExtra.hairline))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).selectableGroup()) {
            tabs.forEach { tab ->
                val selected = tab == current
                val tint by animateColorAsState(if (selected) Film.scan else Film.boneDim, if (reduce) snap() else tween(Motion.SHORT), label = "tab-tint")
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    GlyphIcon(tab.glyph, tint, size = 24.dp)
                    Text(
                        tab.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
