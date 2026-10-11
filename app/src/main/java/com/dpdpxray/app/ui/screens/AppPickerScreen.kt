package com.dpdpxray.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.control.AuditableApp
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.EmptyHint
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.GradientRule
import com.dpdpxray.app.ui.components.HostText
import com.dpdpxray.app.ui.components.SkeletonCard
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.leadSentence
import com.dpdpxray.app.ui.components.pressClickable
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.GuideFlow
import com.dpdpxray.app.ui.guide.SpeakStep
import com.dpdpxray.app.ui.system.SearchField
import com.dpdpxray.app.ui.system.ShizukuAccessNotice
import com.dpdpxray.app.ui.system.ShizukuHelp
import com.dpdpxray.app.ui.system.SegmentedControl
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.LinkAction
import com.dpdpxray.app.ui.guide.OutlineAction
import com.dpdpxray.app.ui.guide.lessLabel
import com.dpdpxray.app.ui.guide.moreLabel
import com.dpdpxray.app.ui.modes.CheckChoice
import com.dpdpxray.app.ui.modes.CheckSheet
import com.dpdpxray.app.ui.modes.EverydayCheckSetup
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.Motion
import com.dpdpxray.app.ui.theme.Radii

private val demoPackages = setOf("com.leakyshop.demo", "com.leakyshop.demo.fixed")

/** Only the first few rows fade up as the list opens; rows scrolled into view later just appear. */
private const val REVEALED_ROWS = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    vm: XrayViewModel,
    onBack: () -> Unit,
    onStarted: () -> Unit,
    initialQuery: String = "",
    onQuarantine: (String) -> Unit = {},
    /** Shows the Live check / APK Guard switch under the title; null hides it. */
    onSwitchToApk: (() -> Unit)? = null,
    /** Package to select once the list loads (Update Watch "Re-check"): its check sheet opens; nothing starts by itself. */
    preselect: String = "",
) {
    val apps by vm.apps.collectAsState()
    val pf by vm.preflight.collectAsState()
    val settings by vm.settings.collectAsState()
    var query by remember { mutableStateOf(initialQuery) }
    var selected by remember { mutableStateOf<AuditableApp?>(null) }
    LaunchedEffect(Unit) { vm.refreshPreflight(); vm.loadApps() }
    var preselected by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(apps, preselect) {
        if (preselected || preselect.isBlank()) return@LaunchedEffect
        apps.firstOrNull { it.packageName == preselect }?.let { selected = it; preselected = true }
    }

    val demos = apps.filter { it.packageName in demoPackages }
    Column(Modifier.fillMaxSize()) {
        TopBar("Choose an app", onBack)
        SpeakStep(GuideFlow.picker(demoInstalled = demos.isNotEmpty()))
        if (onSwitchToApk != null) {
            SegmentedControl(listOf("Live check", "APK Guard"), 0, { if (it == 1) onSwitchToApk() }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        SearchField(query, { query = it }, "Search apps", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        val q = query.trim()
        val shown = apps.filter { q.isBlank() || it.label.contains(q, true) || it.packageName.contains(q, true) }
        val showDemoSection = q.isBlank() && demos.isNotEmpty()
        when {
            // Shimmering rows instead of a lone line of text while the installed apps load. TalkBack hears one label.
            apps.isEmpty() -> Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clearAndSetSemantics { contentDescription = "Loading installed apps…" },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { repeat(4) { SkeletonCard() } }
            shown.isEmpty() -> NoMatch(q) { query = "" }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp)) {
            val rest = if (showDemoSection) shown.filter { it.packageName !in demoPackages } else shown
            if (showDemoSection) {
                item(key = "demo-header") { ListHeader("Demo apps, made for testing") }
                itemsIndexed(demos, key = { _, app -> "demo-" + app.packageName }) { i, app ->
                    AppRow(app, demo = true, showPackage = q.isNotBlank(), Modifier.reveal(i)) { selected = app }
                }
                item(key = "all-header") { ListHeader("All apps") }
            }
            itemsIndexed(rest, key = { _, app -> app.packageName }) { i, app ->
                val order = i + (if (showDemoSection) demos.size else 0)
                AppRow(
                    app, demo = app.packageName in demoPackages, showPackage = q.isNotBlank(),
                    if (q.isBlank() && order < REVEALED_ROWS) Modifier.reveal(order) else Modifier,
                ) { selected = app }
            }
        }
    }

    selected?.let { app ->
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = Film.raised) {
            val quarantine = { selected = null; onQuarantine(app.packageName) }
            val start: (AuditConfig) -> Unit = { config ->
                selected = null
                if (vm.startAudit(config, onStarted)) onStarted()
            }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val help = ShizukuHelp(
                running = pf.shizukuRunning,
                onAllow = { vm.requestShizuku() },
                onOpenShizuku = { com.dpdpxray.app.control.DeviceChecks.launch(ctx, "moe.shizuku.privileged.api") },
            )
            // Full check needs Shizuku's permission; while the sheet is open and it is missing, look again each second
            // so Full becomes selectable the moment the person allows it.
            LaunchedEffect(app.packageName, pf.shizukuGranted) {
                while (!pf.shizukuGranted) { kotlinx.coroutines.delay(1_000); vm.refreshPreflight() }
            }
            // Everyday mode: one Quick check that says No; Full and clearing data fold under Advanced.
            if (settings.audience == Audience.USER) {
                EverydayCheckSetup(app, controlledDemo = app.packageName in demoPackages, shizukuReady = pf.shizukuGranted, shizukuHelp = help, onQuarantine = quarantine, onStart = start)
            } else {
                AuditSetup(app, shizukuReady = pf.shizukuGranted, shizukuHelp = help, preferPreview = settings.fastDemo, onQuarantine = quarantine, onStart = start)
            }
        }
    }
}

/** Nothing matches the search: one line with a glyph, the hint folded to its first sentence, and a way out. */
@Composable
private fun NoMatch(q: String, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        EmptyHint("No apps match \"$q\"", glyph = Glyph.SEARCH)
        FoldText(
            if (q.contains("leaky", true)) "The LeakyShop demo isn't installed on this phone. Install its APK, or clear the search to pick another app."
            else "Check the spelling, or clear the search to see every app.",
        )
        LinkAction("Clear search", onClear)
    }
}

@Composable
private fun ListHeader(text: String) {
    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Film.bone, modifier = Modifier.semantics { heading() })
        GradientRule(Modifier.padding(top = 6.dp), width = 22.dp)
    }
}

/**
 * One installed app. Demo apps sit on a raised card with a brand outline so they stand out as the safe first choice;
 * every row shrinks slightly while pressed.
 */
@Composable
private fun AppRow(app: AuditableApp, demo: Boolean, showPackage: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.row)
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp)
            .pressClickable(shape = shape, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp = remember(app.packageName) { runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
        val iconShape = RoundedCornerShape(12.dp)
        if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(44.dp).clip(iconShape))
        else Box(Modifier.size(44.dp).clip(iconShape).background(FilmExtra.raisedHigh).border(1.dp, FilmExtra.hairline, iconShape))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showPackage) HostText(app.packageName, Film.boneDim)
        }
        if (demo) {
            Spacer(Modifier.width(8.dp))
            Tag("Demo", Film.boneDim)
        }
        Spacer(Modifier.width(8.dp))
        GlyphIcon(Glyph.CHEVRON_RIGHT, Film.boneDim.copy(alpha = 0.7f), size = 16.dp)
    }
}

/** A heading inside a check sheet: a clear title with the short brand rule under it. */
@Composable
internal fun CheckSheetTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = Film.bone, modifier = Modifier.semantics { heading() })
        GradientRule(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

/** First sentence of [text] and the rest (null when it is one sentence), so a caveat can fold its explanation. */
private fun leadAndRest(text: String): Pair<String, String?> {
    val lead = leadSentence(text)
    return lead to text.trim().substring(lead.length).trim().ifBlank { null }
}

@Composable
private fun AuditSetup(app: AuditableApp, shizukuReady: Boolean, shizukuHelp: ShizukuHelp, preferPreview: Boolean, onQuarantine: () -> Unit = {}, onStart: (AuditConfig) -> Unit) {
    val controlledDemo = app.packageName == "com.leakyshop.demo" || app.packageName == "com.leakyshop.demo.fixed"
    val initial = remember { CheckSheet.defaults(Audience.DEVELOPER, controlledDemo, shizukuReady, preferPreview) }
    var allowReset by remember { mutableStateOf(initial.allowReset) }
    val freshReset = allowReset && shizukuReady
    var full by remember { mutableStateOf(initial.choice == CheckChoice.FULL) }
    var preview by remember { mutableStateOf(initial.choice == CheckChoice.PREVIEW) }
    var agent by remember { mutableStateOf(initial.agentTaps) }
    var child by remember { mutableStateOf(false) }
    var gms by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckSheetTitle("Check ${app.label}")
        SpeakStep(GuideFlow.pickerSetup(fullAvailable = freshReset))
        if (shizukuReady) Toggle("Allow clearing this test app's local data", "Developer testing only. Removes its saved state, accounts and settings before each run. Leave off for personal apps.", allowReset) {
            allowReset = it
            if (!it) full = false
        }
        Column(Modifier.reveal(1), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetHeading("How to check")
            PlanOption(
                "Full audit · normal timing",
                "Three fresh-state runs: Silent, Reject, Accept. Observes Silent for 25 seconds and each choice for 15 seconds; launch, consent and AI review add time.",
                full, enabled = shizukuReady,
            ) { full = true; preview = false; allowReset = true }
            if (!shizukuReady) ShizukuAccessNotice(shizukuHelp.running, shizukuHelp.onAllow, shizukuHelp.onOpenShizuku)
            PlanOption("Quick audit · normal timing", "One Accept-plan run with 15 seconds of observation. No Silent or Reject comparison.", !full && !preview, enabled = true) { full = false; preview = false }
            PlanOption("Fast Preview · limited evidence", "One Accept-plan run with 8 seconds of observation and a shorter consent-screen wait. Delayed traffic may be missed; not a Full audit.", preview, enabled = true) { full = false; preview = true }
            // The caveat's first sentence stays in view; the rest folds behind More.
            val (lead, rest) = leadAndRest(
                if (!freshReset) "Quick keeps the app's data. An already-opened app may keep its consent choice and not repeat launch-time DNS lookups. A missing dialog or quiet run is not a privacy pass."
                else "Starting an audit clears this selected app's local data before each plan. Use a test app or a backed-up development build; do not use a personal app with data you need.",
            )
            Callout(lead, detail = rest)
        }

        // The three options fold into one card; its summary always shows how each one is set.
        val onOff = { on: Boolean -> if (on) "on" else "off" }
        CollapsibleCard(
            "Options", Modifier.padding(top = 8.dp).reveal(2),
            summary = "Agent taps ${onOff(agent)} · Children ${onOff(child)} · Play services ${onOff(gms)}",
            glyph = Glyph.SETTINGS,
        ) {
            Toggle("Let the agent tap Accept and Reject", "Off: you tap them yourself when asked.", agent) { agent = it }
            Toggle("This app is used by children", "Adds the DPDP section 9 check: no tracking or targeted ads for children.", child) { child = it }
            Toggle("Include Google Play services lookups", "Some SDKs upload through Play services. Those lookups may include other apps' traffic.", gms) { gms = it }
        }

        GradientAction("Start check", {
            val choice = when { full -> CheckChoice.FULL; preview -> CheckChoice.PREVIEW; else -> CheckChoice.QUICK }
            onStart(CheckSheet.config(Audience.DEVELOPER, app.packageName, app.label, choice, allowReset, shizukuReady, agent, child, gms))
        }, Modifier.padding(top = 8.dp).reveal(3), glyph = Glyph.PLAY, sweep = true)
        // Quarantine run (separate feature): opens its own explainer; nothing starts until Start is tapped there.
        OutlineAction("Run in quarantine (network blocked)", onQuarantine, Modifier.reveal(3), glyph = Glyph.LOCK)
        FoldText("Cuts only this app's internet and records what it tries to reach and which permissions it asks for. Not a malware verdict.")
    }
}

/**
 * A plan as a selectable card: the chosen one gets a scan-coloured outline and a light wash, both easing in. The
 * detail shows its first sentence; the rest (timings, limits) folds behind More.
 */
@Composable
internal fun PlanOption(title: String, detail: String, selected: Boolean, enabled: Boolean, lang: GuideLang = GuideLang.EN, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.row)
    val reduce = LocalReduceMotion.current
    val edge by animateColorAsState(
        if (selected) Film.scan else FilmExtra.hairline,
        if (reduce) snap() else tween(Motion.MEDIUM, easing = Motion.emphasized), label = "plan-edge",
    )
    val fill by animateColorAsState(
        if (selected) Film.scan.copy(alpha = 0.12f) else FilmExtra.raisedHigh.copy(alpha = 0.5f),
        if (reduce) snap() else tween(Motion.MEDIUM, easing = Motion.emphasized), label = "plan-fill",
    )
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(fill)
            .border(if (selected) 1.5.dp else 1.dp, edge, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp).padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = Film.scan), modifier = Modifier.padding(4.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Film.bone else Film.boneDim)
            FoldText(detail, Modifier.padding(top = 2.dp), moreLabel = lang.moreLabel, lessLabel = lang.lessLabel)
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) = SwitchRow(title, detail, value, onChange)
