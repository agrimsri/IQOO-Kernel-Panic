package com.dpdpxray.app.autoguard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dpdpxray.app.apkguard.ApkInstallWatcher
import com.dpdpxray.app.graph
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.theme.Film
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The "Auto-guard" card in Settings. Switching a check off cancels its background job at once (see [AutoGuard.start]);
 * nothing else changes.
 */
@Composable
fun AutoGuardSettingsCard(onCheckup: (() -> Unit)?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember(ctx) { ctx.graph.settings }
    val s by store.settings.collectAsState()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val lastNewAppCheck = remember(resumes, s.autoCheckNewApps) { AutoGuardStore(ctx).newAppsLastRunAt }
    val notificationsOn = remember(resumes) { ApkInstallWatcher.notificationsAllowed(ctx) }
    val on = listOf(s.autoCheckNewApps, s.weeklyCheckup).count { it }

    SectionCard(
        modifier, title = "Auto-guard", glyph = Glyph.BELL,
        trailing = { Tag(if (on == 0) "Off" else "$on of 2 on", if (on == 0) Film.boneDim else Film.clear) },
    ) {
        SwitchRow(
            "Check new apps automatically, even when TraceVeil is closed",
            "Android lets TraceVeil look for newly installed apps about every 15 minutes and show a notification for each new app " +
                "(several at once share one). Android decides the exact time; battery saving can delay it. When you open TraceVeil, " +
                "and while it is open, alerts appear at once. Switching this off stops new-install alerts while TraceVeil is open too.",
            s.autoCheckNewApps,
            { v -> store.update { it.copy(autoCheckNewApps = v) } },
        )
        if (s.autoCheckNewApps) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                GlyphIcon(Glyph.CLOCK, Film.scan, Modifier.padding(top = 3.dp), size = 16.dp)
                Spacer(Modifier.width(8.dp))
                FoldText(
                    lastNewAppCheck?.let { "Last background check: ${time(it)}" }
                        ?: "No background check has finished yet. Android usually runs the first one within a few minutes.",
                    Modifier.weight(1f),
                )
            }
        }
        Hairline()
        SwitchRow(
            "Weekly phone checkup",
            "Once a week, counts the apps that can read SMS, control your screen, read notifications and more, and shows one notification if any need review.",
            s.weeklyCheckup,
            { v -> store.update { it.copy(weeklyCheckup = v) } },
        )
        Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.LOCK, Film.clear, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Note("Runs on this phone only. Nothing is sent anywhere.", Film.clear)
        }
        LearnMore(label = "If the checks pause") {
            Note("If TraceVeil is force-stopped (by you or by the phone's battery manager), Android pauses both checks until you open TraceVeil again.")
        }
        if (!notificationsOn && (s.autoCheckNewApps || s.weeklyCheckup)) {
            Callout("Notifications are off for TraceVeil, so Auto-guard cannot show its alerts.", Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
        if (onCheckup != null) SecondaryAction("Open my phone checkup", onCheckup, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

private fun time(epochMs: Long): String =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
