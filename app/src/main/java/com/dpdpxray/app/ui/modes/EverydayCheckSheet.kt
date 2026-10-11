package com.dpdpxray.app.ui.modes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.control.AuditableApp
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.DisclosureRow
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.components.foldSpec
import com.dpdpxray.app.ui.components.leadSentence
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.SpeakStep
import com.dpdpxray.app.ui.system.ShizukuAccessNotice
import com.dpdpxray.app.ui.system.ShizukuHelp
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.OutlineAction
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.guide.lessLabel
import com.dpdpxray.app.ui.guide.moreLabel
import com.dpdpxray.app.ui.screens.CheckSheetTitle
import com.dpdpxray.app.ui.screens.PlanOption
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Radii

/**
 * Everyday check sheet. Opens on one Quick check that says No (a single Reject run that keeps the app's data), with
 * the agent-tap switch beside it, and a background watch (the app only watched while it runs in the background, for a
 * chosen length). Full, clearing app data, the children and Play-services options and the internet-cut-off run fold
 * under Advanced. Same engine and safeguards as the developer sheet: data is cleared only
 * with explicit approval and Shizuku, and a quiet result is never called a pass.
 *
 * Text diet: each explanation shows its first sentence; the rest folds behind More (in the guide language).
 */
@Composable
internal fun EverydayCheckSetup(
    app: AuditableApp,
    controlledDemo: Boolean,
    shizukuReady: Boolean,
    shizukuHelp: ShizukuHelp,
    onQuarantine: () -> Unit,
    onStart: (AuditConfig) -> Unit,
    /** What restarting the app would switch off (null while it is being read, or unreadable). */
    effects: List<com.dpdpxray.app.audit.FoundSideEffect>? = null,
) {
    val lang = guideLang()
    val c = EverydaySheetCopy
    val initial = remember { CheckSheet.defaults(Audience.USER, controlledDemo, shizukuReady, preferPreview = false) }
    var choice by remember { mutableStateOf(initial.choice) }
    var allowReset by remember { mutableStateOf(initial.allowReset) }
    var agent by remember { mutableStateOf(initial.agentTaps) }
    var child by remember { mutableStateOf(false) }
    var gms by remember { mutableStateOf(false) }
    var watchMinutes by remember { mutableStateOf(com.dpdpxray.core.model.BackgroundWatches.DEFAULT_MINUTES) }
    val freshReset = allowReset && shizukuReady
    // The safe option is the default: an app a restart would disturb is checked without restarting it.
    var restartAnyway by remember { mutableStateOf(false) }
    // Only a Full check clears data; Quick keeps it even with the switch on.
    val clears = freshReset && choice == CheckChoice.FULL
    val leftRunning = shizukuReady && !clears && !effects.isNullOrEmpty() && !restartAnyway
    // Clearing any app other than the demo apps is confirmed first; choosing Full never turns it on by itself.
    val confirmFirst = CheckSheet.resetNeedsConfirmation(app.packageName)
    var askReset by remember { mutableStateOf(false) }
    if (askReset) ResetConfirmDialog(app.label, lang,
        onConfirm = { askReset = false; allowReset = true; choice = CheckChoice.FULL },
        onDismiss = { askReset = false; if (!allowReset && choice == CheckChoice.FULL) choice = CheckChoice.QUICK })

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckSheetTitle(c.title(app.label).pick(lang))
        val step = c.guide(app.label)
        SpeakStep(step)
        // What to do once the check starts (say No when the app asks), as one quiet line; read-aloud speaks it too.
        Text(step.body.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        Column(Modifier.padding(top = 4.dp).reveal(1), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanOption(
                c.quickTitle.pick(lang), (if (shizukuReady && !leftRunning) c.quickDetailRestart else c.quickDetail).pick(lang),
                selected = choice == CheckChoice.QUICK, enabled = true, lang = lang,
            ) { choice = CheckChoice.QUICK }
            if (choice != CheckChoice.WATCH) SwitchRow(c.agentTitle.pick(lang), c.agentDetail.pick(lang), agent, { agent = it })
            PlanOption(
                com.dpdpxray.app.audit.WatchCopy.optionTitle.pick(lang), com.dpdpxray.app.audit.WatchCopy.optionDetail(watchMinutes).pick(lang),
                selected = choice == CheckChoice.WATCH, enabled = true, lang = lang,
            ) { choice = CheckChoice.WATCH }
            if (choice == CheckChoice.WATCH) WatchLengthChips(watchMinutes, { watchMinutes = it }, lang)
            SheetCaveat(when {
                choice == CheckChoice.WATCH -> com.dpdpxray.app.audit.WatchCopy.caveat
                clears -> c.resetCaveat
                else -> c.quickCaveat
            }.pick(lang), lang)
        }

        CollapsibleCard(
            c.advanced.pick(lang), Modifier.padding(top = 8.dp).reveal(2),
            summary = c.advancedSummary.pick(lang), glyph = Glyph.SETTINGS, initiallyOpen = choice == CheckChoice.FULL,
        ) {
            PlanOption(c.fullTitle.pick(lang), c.fullDetail.pick(lang), selected = choice == CheckChoice.FULL, enabled = shizukuReady, lang = lang) {
                choice = CheckChoice.FULL
                if (!confirmFirst) allowReset = true else if (!allowReset) askReset = true
            }
            if (!shizukuReady) ShizukuAccessNotice(shizukuHelp.running, shizukuHelp.onAllow, shizukuHelp.onOpenShizuku)
            if (shizukuReady) SwitchRow(c.resetTitle.pick(lang), c.resetDetail.pick(lang), allowReset, { on ->
                if (on && confirmFirst) askReset = true else allowReset = on
                if (!on && choice == CheckChoice.FULL) choice = CheckChoice.QUICK
            })
            Hairline()
            SwitchRow(c.childTitle.pick(lang), c.childDetail.pick(lang), child, { child = it })
            SwitchRow(c.gmsTitle.pick(lang), c.gmsDetail.pick(lang), gms, { gms = it })
            Hairline()
            Spacer(Modifier.height(4.dp))
            OutlineAction(c.quarantine.pick(lang), onQuarantine, glyph = Glyph.LOCK)
            FoldText(c.quarantineNote.pick(lang), moreLabel = lang.moreLabel, lessLabel = lang.lessLabel)
        }

        // A watch never restarts the app, so there is nothing a restart would switch off.
        if (choice != CheckChoice.WATCH) RestartWarning(app.label, effects, canStop = shizukuReady, reset = clears, restartAnyway = restartAnyway, onChange = { restartAnyway = it }, lang = lang)

        GradientAction((if (choice == CheckChoice.WATCH) com.dpdpxray.app.audit.WatchCopy.start else c.start).pick(lang), {
            onStart(CheckSheet.config(Audience.USER, app.packageName, app.label, choice, allowReset, shizukuReady, agent, child, gms,
                CheckSheet.restartPolicy(effects.takeIf { shizukuReady }, restartAnyway), watchMinutes))
        }, Modifier.padding(top = 12.dp).reveal(3), glyph = Glyph.PLAY, sweep = true)
    }
}

/**
 * The sheet's caveat: tinted wash, drawn amber rule, the first sentence always in view and the rest behind More.
 * Like the shared Callout, but its More/Less follow the guide language so the Hindi sheet reads in Hindi.
 */
@Composable
private fun SheetCaveat(text: String, lang: GuideLang, color: Color = Film.warn) {
    val lead = remember(text) { leadSentence(text) }
    val rest = remember(text) { text.trim().substring(lead.length).trim().ifBlank { null } }
    val shape = RoundedCornerShape(Radii.row)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0.06f))))
            .drawBehind { drawRect(color, size = Size(4.dp.toPx(), size.height)) }
            .border(1.dp, color.copy(alpha = 0.32f), shape)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = if (rest != null) 0.dp else 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        GlyphIcon(Glyph.WARN, color, Modifier.padding(top = 2.dp), size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).animateContentSize(foldSpec())) {
            Text(lead, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
            if (rest != null) {
                var open by rememberSaveable(text) { mutableStateOf(false) }
                if (open) Text(rest, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(top = 6.dp))
                DisclosureRow(open, lang.moreLabel, lang.lessLabel, { open = !open }, color = color, glyph = null)
            }
        }
    }
}
