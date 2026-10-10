package com.dpdpxray.app.ui.screens

import android.database.ContentObserver
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.GroupHeader
import com.dpdpxray.app.ui.guide.ActionTile
import com.dpdpxray.app.ui.guide.EmptyState
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.GuideBanner
import com.dpdpxray.app.ui.guide.GuideFlow
import com.dpdpxray.app.ui.guide.LinkAction
import com.dpdpxray.app.ui.guide.OutlineAction
import com.dpdpxray.app.ui.guide.Panel
import com.dpdpxray.app.ui.guide.PillButton
import com.dpdpxray.app.ui.guide.SetupItem
import com.dpdpxray.app.ui.guide.SetupKind
import com.dpdpxray.app.ui.guide.SetupNeed
import com.dpdpxray.app.ui.guide.SetupSteps
import com.dpdpxray.app.ui.modes.ModeGlyph
import com.dpdpxray.app.ui.modes.ModeGlyphIcon
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra

@Composable
fun HomeScreen(
    vm: XrayViewModel,
    onAudit: () -> Unit,
    onOpen: (String) -> Unit,
    onLab: () -> Unit,
    onCamera: () -> Unit,
    onSettings: () -> Unit,
    onVoice: (String) -> Unit,
    /** Opens the APK check; null hides the entry when this build has no such screen. */
    onApk: (() -> Unit)? = null,
    onHowItWorks: () -> Unit = {},
    onLive: () -> Unit = {},
    /** Opens the Reports tab with every saved check. */
    onAllReports: () -> Unit = {},
    /** Opens Settings, where developer automation (laptop and CI runs) lives; null hides the card. */
    onAutomation: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val pf by vm.preflight.collectAsState()
    val audits by vm.audits.collectAsState()
    val llm by vm.llmStatus.collectAsState()
    val progress by vm.progress.collectAsState()
    var showSampleFixtures by remember { mutableStateOf(false) }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshPreflight() }
    LaunchedEffect(vm) { vm.refreshPreflight() }
    DisposableEffect(ctx, vm) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { vm.refreshPreflight() }
        }
        ctx.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        onDispose { ctx.contentResolver.unregisterContentObserver(observer) }
    }

    val items = SetupSteps.from(pf)
    val todo = SetupSteps.todo(items)
    val running = progress as? AuditProgress.Running
    val fix: (SetupKind) -> Unit = { kind ->
        when (kind) {
            SetupKind.VPN -> if (DeviceChecks.otherVpnActive(ctx)) DeviceChecks.openVpnSettings(ctx) else VpnService.prepare(ctx)?.let { vpnLauncher.launch(it) } ?: vm.refreshPreflight()
            SetupKind.WATCHER -> DeviceChecks.openAccessibilitySettings(ctx)
            SetupKind.PRIVATE_DNS -> DeviceChecks.openPrivateDnsSettings(ctx)
            SetupKind.SHIZUKU -> vm.requestShizuku()
        }
    }
    val aiLine = aiStatusLine(llm)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TraceVeil", style = MaterialTheme.typography.headlineMedium, maxLines = 1, modifier = Modifier.semantics { heading() })
                Box(Modifier.padding(top = 4.dp).size(width = 44.dp, height = 3.dp).clip(CircleShape).background(Brand.horizontal))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = "Settings" }) {
                GlyphIcon(Glyph.SETTINGS, Film.boneDim, size = 24.dp)
            }
        }
        Text("Apps promise. TraceVeil checks.", style = MaterialTheme.typography.bodyLarge, color = Film.boneDim, modifier = Modifier.padding(top = 8.dp))

        Spacer(Modifier.height(16.dp))
        GuideBanner(
            running?.let { GuideFlow.homeRunning(it.config.targetLabel) } ?: GuideFlow.home(pf),
            speak = running == null,
            action = running?.let { "Watch the check" to onLive },
        )

        if (todo.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            GettingReady(items, todo, aiLine, fix, onLab)
        }

        Spacer(Modifier.height(16.dp))
        Hero(canAudit = pf.canAudit, stepsLeft = todo.size) { if (vm.prepareAudit()) onAudit() }

        if (todo.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            ReadyPanel(items, aiLine, fix, onLab)
        }

        val latest = audits.firstOrNull()
        val tiles = buildList {
            if (onApk != null) add(Tile("Check an APK file", "Inspect an app file on this phone", Glyph.APK, onApk))
            if (latest != null) add(Tile("Ask about my last check", "By voice or tap, about ${latest.targetLabel}", Glyph.ASK) { onVoice(latest.id) })
            add(Tile("See a sample", "A labelled example result; no setup", Glyph.SAMPLE) { vm.openSample(onOpen) })
            add(Tile("Camera check", "Review a photo of a consent screen", Glyph.CAMERA, onCamera))
        }
        GroupHeader("Quick actions")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { t -> ActionTile(t.title, t.detail, t.glyph, t.onClick, Modifier.weight(1f).fillMaxHeight()) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LinkAction("How it works", onHowItWorks, glyph = Glyph.BOOK)
            LinkAction("More samples", { showSampleFixtures = true }, color = Film.boneDim, glyph = Glyph.REPORTS)
        }
        if (onAutomation != null) {
            Spacer(Modifier.height(8.dp))
            AutomateCard(onAutomation)
        }

        GroupHeader("Recent checks") {
            if (audits.size > RECENT_ON_HOME) LinkAction("See all ${audits.size}", onAllReports)
            else if (audits.isNotEmpty()) Text("${audits.size}", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        }
        if (audits.isEmpty()) {
            EmptyState(
                "No checks yet",
                "Your checks will appear here. Start with a sample to see what a result looks like.",
                action = "See a sample" to { vm.openSample(onOpen) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            audits.take(RECENT_ON_HOME).forEach { RecentCheckCard(it) { onOpen(it.id) } }
        }
        if (audits.size > RECENT_ON_HOME) {
            OutlineAction("See all ${audits.size} checks in Reports", onAllReports, Modifier.padding(top = 12.dp), glyph = Glyph.REPORTS)
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showSampleFixtures) SampleFixturesDialog(vm, onOpen) { showSampleFixtures = false }
}

/** Home shows the newest few; the Reports tab lists them all. */
private const val RECENT_ON_HOME = 3

private data class Tile(val title: String, val detail: String, val glyph: Glyph, val onClick: () -> Unit)

/**
 * The lightbox: a readiness chip, one question, one sentence, one action. A faint film grid and a consent line sit
 * behind it.
 */
@Composable
private fun Hero(canAudit: Boolean, stepsLeft: Int, onCheck: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(Film.raised).border(1.dp, FilmExtra.hairline, shape)) {
        Canvas(Modifier.matchParentSize()) {
            val step = 22.dp.toPx()
            var x = step
            while (x < size.width) { drawLine(FilmExtra.hairline, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = step
            while (y < size.height) { drawLine(FilmExtra.hairline, Offset(0f, y), Offset(size.width, y), 1f); y += step }
            val line = size.height * 0.36f
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, Film.scan.copy(alpha = 0.55f), Color.Transparent)),
                Offset(0f, line), Offset(size.width, line), 2.dp.toPx(),
            )
        }
        Column(Modifier.padding(20.dp)) {
            ReadinessChip(canAudit, stepsLeft)
            Text(
                "Does an app respect your choice?", style = MaterialTheme.typography.headlineMedium, color = Film.bone,
                modifier = Modifier.padding(top = 14.dp).semantics { heading() },
            )
            Text(
                "TraceVeil watches one app's lookups while you accept or refuse its consent popup, then explains what it saw.",
                style = MaterialTheme.typography.bodyLarge, color = Film.boneDim, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
            )
            GradientAction("Check an app", onCheck, glyph = Glyph.PLAY)
            if (!canAudit) Text(
                "Allow the VPN and turn on the consent watcher first (see Getting ready).",
                style = MaterialTheme.typography.bodyMedium, color = Film.warn, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** "Ready to check" in green once nothing is left to do; otherwise what is left, in amber. Same checks as Getting ready. */
@Composable
private fun ReadinessChip(canAudit: Boolean, stepsLeft: Int) {
    val color = if (canAudit && stepsLeft == 0) Film.clear else Film.warn
    val text = when {
        canAudit && stepsLeft == 0 -> "Ready to check"
        canAudit -> "Ready · see the tip above"
        stepsLeft == 1 -> "1 setup step left"
        else -> "$stepsLeft setup steps left"
    }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Developer mode only: the same audits, run from a laptop or CI. Opens Settings › Developer automation. */
@Composable
private fun AutomateCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(shape).background(Film.raised).border(1.dp, FilmExtra.hairline, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Film.scan.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            ModeGlyphIcon(ModeGlyph.CODE, Film.scan, size = 24.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Automate it", style = MaterialTheme.typography.titleMedium, color = Film.bone)
            Text("Run audits from your laptop or CI on every alpha/beta build.", style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
        Spacer(Modifier.width(8.dp))
        GlyphIcon(Glyph.CHEVRON_RIGHT, Film.boneDim, size = 18.dp)
    }
}

private fun glyphFor(kind: SetupKind) = when (kind) {
    SetupKind.VPN -> Glyph.VPN
    SetupKind.WATCHER -> Glyph.WATCHER
    SetupKind.PRIVATE_DNS -> Glyph.PRIVATE_DNS
    SetupKind.SHIZUKU -> Glyph.RESET
}

/** Shown above the main action while something is missing: one numbered step per item, each with a Fix button. */
@Composable
private fun GettingReady(items: List<SetupItem>, todo: List<SetupItem>, aiLine: String, fix: (SetupKind) -> Unit, onLab: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Getting ready", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            Text(if (todo.size == 1) "1 step left" else "${todo.size} steps left", style = MaterialTheme.typography.labelMedium, color = Film.warn)
        }
        Spacer(Modifier.height(6.dp))
        todo.forEachIndexed { i, item -> SetupRow(item, number = i + 1) { fix(item.kind) } }
        val rest = items.filter { it !in todo }
        if (rest.isNotEmpty()) {
            Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(FilmExtra.hairline))
            rest.forEach { item -> SetupRow(item, number = null) { fix(item.kind) } }
            AiRow(aiLine, onLab)
        }
    }
}

/** Shown below the main action once everything required is done: one line, details on request. */
@Composable
private fun ReadyPanel(items: List<SetupItem>, aiLine: String, fix: (SetupKind) -> Unit, onLab: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Panel {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Film.clear.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.CHECK, Film.clear, size = 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Setup", style = MaterialTheme.typography.titleMedium)
                Text("VPN allowed and consent watcher on", style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
            }
            Text(if (open) "Hide" else "Details", style = MaterialTheme.typography.labelLarge, color = Film.scan)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 6.dp)) {
                items.forEach { item -> SetupRow(item, number = null) { fix(item.kind) } }
                AiRow(aiLine, onLab)
            }
        }
    }
}

/** One setup step with its Fix button; also used inside the everyday Home's "No" card. */
@Composable
internal fun SetupRow(item: SetupItem, number: Int?, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val accent = when {
            item.done -> Film.clear
            item.need == SetupNeed.REQUIRED -> Brand.coral
            item.need == SetupNeed.RECOMMENDED -> Film.warn
            else -> Film.boneDim
        }
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(accent.copy(alpha = 0.13f)).border(1.dp, accent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (number != null) Text("$number", style = MaterialTheme.typography.labelLarge, color = accent)
            else GlyphIcon(if (item.done) Glyph.CHECK else glyphFor(item.kind), accent, size = 18.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(item.detail, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
        if (item.fixable && !item.done) {
            Spacer(Modifier.width(8.dp))
            PillButton(if (item.kind == SetupKind.SHIZUKU) "Allow" else "Fix", onFix)
        }
    }
}

@Composable
private fun AiRow(line: String, onLab: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Film.scan.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.AI, Film.scan, size = 18.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("On-device AI (optional)", style = MaterialTheme.typography.titleMedium)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
        LinkAction("AI lab", onLab)
    }
}
