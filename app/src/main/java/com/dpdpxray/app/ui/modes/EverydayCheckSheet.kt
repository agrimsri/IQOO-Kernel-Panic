package com.dpdpxray.app.ui.modes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.control.AuditableApp
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.GuideBanner
import com.dpdpxray.app.ui.guide.OutlineAction
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.screens.PlanOption

/**
 * Everyday check sheet. Opens on one Quick check that says No (a single Reject run that keeps the app's data), with
 * the agent-tap switch beside it. Full, clearing app data, the children and Play-services options and the
 * internet-cut-off run fold under Advanced. Same engine and safeguards as the developer sheet: data is cleared only
 * with explicit approval and Shizuku, and a quiet result is never called a pass.
 */
@Composable
internal fun EverydayCheckSetup(
    app: AuditableApp,
    controlledDemo: Boolean,
    shizukuReady: Boolean,
    onQuarantine: () -> Unit,
    onStart: (AuditConfig) -> Unit,
) {
    val lang = guideLang()
    val c = EverydaySheetCopy
    val initial = remember { CheckSheet.defaults(Audience.USER, controlledDemo, shizukuReady, preferPreview = false) }
    var choice by remember { mutableStateOf(initial.choice) }
    var allowReset by remember { mutableStateOf(initial.allowReset) }
    var agent by remember { mutableStateOf(initial.agentTaps) }
    var child by remember { mutableStateOf(false) }
    var gms by remember { mutableStateOf(false) }
    val freshReset = allowReset && shizukuReady

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(c.title(app.label).pick(lang), style = MaterialTheme.typography.headlineMedium)
        GuideBanner(c.guide(app.label), Modifier.padding(vertical = 6.dp))
        PlanOption(
            c.quickTitle.pick(lang), (if (freshReset) c.quickDetailFresh else c.quickDetail).pick(lang),
            selected = choice == CheckChoice.QUICK, enabled = true,
        ) { choice = CheckChoice.QUICK }
        SwitchRow(c.agentTitle.pick(lang), c.agentDetail.pick(lang), agent, { agent = it })
        if (freshReset) Callout(c.resetCaveat.pick(lang)) else Callout(c.quickCaveat.pick(lang))

        Spacer(Modifier.height(6.dp))
        CollapsibleCard(c.advanced.pick(lang), summary = c.advancedSummary.pick(lang), glyph = Glyph.SETTINGS, initiallyOpen = choice == CheckChoice.FULL) {
            PlanOption(c.fullTitle.pick(lang), c.fullDetail.pick(lang), selected = choice == CheckChoice.FULL, enabled = freshReset) { choice = CheckChoice.FULL }
            if (!freshReset) Note(c.fullNeeds.pick(lang))
            if (shizukuReady) SwitchRow(c.resetTitle.pick(lang), c.resetDetail.pick(lang), allowReset, { on ->
                allowReset = on
                if (!on && choice == CheckChoice.FULL) choice = CheckChoice.QUICK
            })
            Hairline()
            SwitchRow(c.childTitle.pick(lang), c.childDetail.pick(lang), child, { child = it })
            SwitchRow(c.gmsTitle.pick(lang), c.gmsDetail.pick(lang), gms, { gms = it })
            Hairline()
            Spacer(Modifier.height(4.dp))
            OutlineAction(c.quarantine.pick(lang), onQuarantine, glyph = Glyph.LOCK)
            Note(c.quarantineNote.pick(lang))
        }

        Spacer(Modifier.height(12.dp))
        GradientAction(c.start.pick(lang), {
            onStart(CheckSheet.config(Audience.USER, app.packageName, app.label, choice, allowReset, shizukuReady, agent, child, gms))
        })
    }
}
