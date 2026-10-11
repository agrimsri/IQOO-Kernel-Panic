package com.dpdpxray.app.webcheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.web.WebAudit
import com.dpdpxray.core.web.WebSites

/** Routes of the website check. [START] is a Check-tab view (the bar stays); [LIVE] is a flow screen without the bar. */
object WebCheckRoutes {
    const val START = "webcheck"
    const val LIVE = "webcheck/live"
}

/** The website check's two screens. Results open on the shared results route, like an app audit. */
fun NavGraphBuilder.webCheckDestinations(nav: NavController, onSwitchToLive: () -> Unit, onSwitchToApk: () -> Unit) {
    composable(WebCheckRoutes.START) {
        WebCheckScreen(
            onBack = { nav.popBackStack() },
            onStarted = { nav.navigate(WebCheckRoutes.LIVE) },
            onSwitchToLive = onSwitchToLive,
            onSwitchToApk = onSwitchToApk,
        )
    }
    composable(WebCheckRoutes.LIVE) {
        WebCheckLiveScreen(
            onDone = { id -> nav.navigate("results/$id") { popUpTo(WebCheckRoutes.START) } },
            onBack = { nav.popBackStack(WebCheckRoutes.START, inclusive = false) },
        )
    }
}

/** "Website evidence" on a website check's result: per run, what the browser saw, then the method and its limits. */
@Composable
fun WebEvidenceCard(sessions: List<AuditSession>, modifier: Modifier = Modifier) {
    val lang = guideLang()
    val runs = remember(sessions) { WebCheckView.summary(sessions) }
    CollapsibleCard(WebCheckCopy.evidenceTitle.pick(lang), modifier, summary = WebCheckCopy.evidenceSummary.pick(lang), glyph = Glyph.SEARCH, initiallyOpen = true) {
        runs.forEach { run ->
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(run.plan, style = MaterialTheme.typography.titleSmall, color = Film.bone, modifier = Modifier.semantics { heading() })
                run.lines.forEach { Note(it.pick(lang)) }
            }
        }
        Note(WebAudit.METHOD)
        Note(WebAudit.REQUEST_CAVEAT)
        Note(WebSites.RULE)
        WebAudit.LIMITS.forEach { Note("• $it") }
    }
}
