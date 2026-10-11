package com.dpdpxray.app.ui.modes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.audit.FoundSideEffect
import com.dpdpxray.app.audit.RestartSafeguardCopy
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.screens.PlanOption
import com.dpdpxray.app.ui.system.Group
import com.dpdpxray.app.ui.system.GroupDivider
import com.dpdpxray.app.ui.system.ListRow
import com.dpdpxray.app.ui.system.StatusDot
import com.dpdpxray.app.ui.theme.Film

/**
 * Shown in the check sheet, above Start, when stopping the app would switch something off (its accessibility service,
 * VPN, keyboard...). It says what, then offers "Check without restarting" (selected by default) or "Restart anyway".
 * With a reset approved there is no choice: the reset itself stops the app, and the sheet says so. Nothing shows when
 * the phone could not be read, found nothing, or cannot stop apps (no Shizuku).
 */
@Composable
internal fun RestartWarning(
    app: String,
    effects: List<FoundSideEffect>?,
    canStop: Boolean,
    reset: Boolean,
    restartAnyway: Boolean,
    onChange: (Boolean) -> Unit,
    lang: GuideLang,
    modifier: Modifier = Modifier,
) {
    if (effects.isNullOrEmpty() || !canStop) return
    val c = RestartSafeguardCopy
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Group(title = c.title.pick(lang)) {
            effects.forEachIndexed { i, f ->
                if (i > 0) GroupDivider()
                ListRow(c.found(f.kind).pick(lang), subtitle = c.warning(app, f, reset).pick(lang), subtitleMaxLines = 6, leading = { StatusDot(Film.warn) })
            }
            if (reset) {
                GroupDivider()
                ListRow(c.resetCovers.pick(lang))
            }
        }
        if (!reset) {
            PlanOption(c.skip.pick(lang), c.skipDetail(app).pick(lang), selected = !restartAnyway, enabled = true, lang = lang) { onChange(false) }
            PlanOption(c.anyway.pick(lang), c.anywayDetail(app).pick(lang), selected = restartAnyway, enabled = true, lang = lang) { onChange(true) }
        }
    }
}
