package com.dpdpxray.app.quarantine

import android.content.Context
import android.net.VpnService
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.report.AuditJsonExport
import com.dpdpxray.app.report.ExportManager
import com.dpdpxray.app.report.QuarantineJsonExport
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.EmptyHint
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.HostText
import com.dpdpxray.app.ui.components.IconBadge
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.RecordingDot
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.leadSentence
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.LinkAction
import com.dpdpxray.app.ui.guide.OutlineAction
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.Motion
import com.dpdpxray.core.netcapture.CleartextPersonalData
import com.dpdpxray.core.netcapture.FlowRecord
import com.dpdpxray.core.netcapture.TlsStatus
import com.dpdpxray.core.quarantine.BlockedAttempt
import com.dpdpxray.core.trackers.TrackerCatalog
import com.dpdpxray.core.quarantine.DestinationRole
import com.dpdpxray.core.quarantine.DomainAttempt
import com.dpdpxray.core.quarantine.QuarantineEvidence
import com.dpdpxray.core.quarantine.QuarantineObservations
import com.dpdpxray.core.quarantine.QuarantineReport
import com.dpdpxray.core.quarantine.QuarantineReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Quarantine runner for one app: explainer + Start → live list → behaviour report. Opening this screen never starts a
 * run; the person must tap Start.
 */
@Composable
fun QuarantineScreen(vm: XrayViewModel, packageName: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val run by QuarantineController.state.collectAsState()
    val live by QuarantineBus.live.collectAsState()
    val pf by vm.preflight.collectAsState()
    val label = remember(packageName) {
        runCatching { ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() } }.getOrNull()
    }
    var viewing by remember { mutableStateOf<QuarantineReport?>(null) }
    var lastSaved by remember { mutableStateOf<QuarantineReport?>(null) }
    LaunchedEffect(packageName, run) {
        vm.refreshPreflight()
        lastSaved = withContext(Dispatchers.IO) { QuarantineController.store(ctx).latestFor(packageName) }
    }

    val settings by vm.settings.collectAsState()
    val catalog = remember(vm, settings.firstPartyDomains) { vm.graph.catalog() }
    val current = run
    when {
        viewing != null -> ReportView(viewing!!, saved = true, path = null, catalog = catalog, onBack = { viewing = null }, onAgain = { viewing = null }, onDone = { viewing = null })
        current is QuarantineRunState.Running && current.targetPackage == packageName -> LiveView(current, live, catalog, onBack)
        current is QuarantineRunState.Running -> Column(Modifier.fillMaxSize()) {
            TopBar("Quarantine run", onBack)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Callout("Another quarantine run is active for ${current.targetLabel}. Only one app can be quarantined at a time.", Modifier.reveal(0), glyph = Glyph.VPN)
                GradientAction("Stop that run", { QuarantineController.stop() }, Modifier.reveal(1))
            }
        }
        current is QuarantineRunState.Finished && current.report.targetPackage == packageName ->
            ReportView(current.report, current.saved, current.path, catalog = catalog, onBack = onBack, onAgain = { QuarantineController.acknowledge() }, onDone = { QuarantineController.acknowledge(); onBack() })
        else -> Explainer(
            packageName = packageName,
            label = label,
            vpnReady = pf.vpnReady,
            watcherOn = pf.accessibilityOn,
            failure = (current as? QuarantineRunState.Failed)?.takeIf { it.targetPackage == packageName }?.message,
            lastSaved = lastSaved,
            onRefresh = { vm.refreshPreflight() },
            onView = { viewing = it },
            onBack = onBack,
        )
    }
}

/** Short names for the four steps, drawn as a numbered strip; the full steps fold under "Step by step". */
private val STEP_NAMES = listOf("Close the app", "Tap Start", "Use it", "Stop")

@Composable
private fun Explainer(
    packageName: String,
    label: String?,
    vpnReady: Boolean,
    watcherOn: Boolean,
    failure: String?,
    lastSaved: QuarantineReport?,
    onRefresh: () -> Unit,
    onView: (QuarantineReport) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onRefresh() }
    var durationMs by remember { mutableLongStateOf(QuarantineController.DEFAULT_DURATION_MS) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Quarantine run", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Hero: the app, three chips that say what this is not, and the first sentence of the explainer.
            Column(Modifier.reveal(0).fillMaxWidth().cardSurface().background(Brand.heroWash).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Glyph.VPN, Film.leak, size = 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label ?: packageName, style = MaterialTheme.typography.headlineSmall, color = Film.bone, modifier = Modifier.semantics { heading() })
                        HostText(packageName, Film.boneDim)
                    }
                }
                Spacer(Modifier.height(12.dp))
                NotAVerdictChips()
                Spacer(Modifier.height(12.dp))
                FoldText(QuarantineText.EXPLAINER, style = MaterialTheme.typography.bodyLarge, color = Film.bone)
            }

            SectionCard(Modifier.reveal(1), title = "How it works", glyph = Glyph.PLAY) {
                StepStrip(STEP_NAMES)
                LearnMore(Modifier.padding(top = 4.dp), label = "Step by step") {
                    QuarantineText.STEPS.forEachIndexed { i, s -> StepRow(i + 1, s) }
                }
                OnlyInternetCallout()
            }

            SectionCard(Modifier.reveal(2), title = "How long", glyph = Glyph.CLOCK) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuarantineController.DURATIONS_MS.forEach { ms -> Choice(QuarantineText.duration(ms), ms == durationMs) { durationMs = ms } }
                }
                Spacer(Modifier.height(10.dp))
                Note("You can stop earlier at any time. Stopping gives the app its internet back.")
            }

            Column(Modifier.reveal(3), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!watcherOn) Callout("The TraceVeil watcher is off in Accessibility settings, so permission dialogs and settings screens will not be recorded. Network attempts still will.", glyph = Glyph.WATCHER)
                if (label == null) Callout("This app is not installed on this phone.", color = Film.leak)
                (error ?: failure)?.let { Callout(it, color = Film.leak) }
                if (!vpnReady && com.dpdpxray.app.control.DeviceChecks.otherVpnActive(ctx)) {
                    Callout(com.dpdpxray.app.ui.AuditStartReadiness.OTHER_VPN, glyph = Glyph.VPN)
                    GradientAction("Open VPN settings", { com.dpdpxray.app.control.DeviceChecks.openVpnSettings(ctx) }, glyph = Glyph.SETTINGS)
                } else if (!vpnReady) {
                    GradientAction("Allow VPN permission", { VpnService.prepare(ctx)?.let { vpnLauncher.launch(it) } ?: onRefresh() }, glyph = Glyph.VPN)
                    Note("Android asks once. In a quarantine run the VPN covers only the app you choose.")
                } else {
                    GradientAction("Start quarantine run", {
                        error = QuarantineController.start(ctx, packageName, label ?: packageName, durationMs)
                    }, enabled = label != null, glyph = Glyph.PLAY)
                }

                lastSaved?.let { r ->
                    OutlineAction(
                        "Last run ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startedAtEpochMs))} · view report",
                        { onView(r) }, glyph = Glyph.REPORTS,
                    )
                }
            }

            CollapsibleCard(
                "What this cannot see", Modifier.reveal(4),
                summary = "Limits of a quarantine run: read these before drawing conclusions", glyph = Glyph.INFO,
            ) {
                QuarantineReports.LIMITS.forEach { Note("• $it") }
            }
        }
    }
}

/** [QuarantineText.NOT_A_VERDICT] as three chips: "Network blocked", "Not a malware verdict", "Not a full sandbox". */
@Composable
private fun NotAVerdictChips() {
    val parts = remember { QuarantineText.NOT_A_VERDICT.split(" · ").map { p -> p.replaceFirstChar { it.uppercase() } } }
    ChipFlow { parts.forEachIndexed { i, p -> Tag(p, if (i == 0) Film.leak else Film.warn) } }
}

/**
 * "Only internet is blocked" stays in view as a callout, including the safety advice not to grant SMS,
 * Accessibility or overlay access to an app you do not trust.
 */
@Composable
private fun OnlyInternetCallout() {
    val title = "Only internet is blocked"
    val body = remember { QuarantineText.ONLY_INTERNET.removePrefix("$title.").trim() }
    Callout(body, Modifier.padding(top = 8.dp), color = Film.leak, title = title)
}

/** Four numbered nodes on a thin line, each with a two-word name. */
@Composable
private fun StepStrip(names: List<String>) {
    val n = names.size
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).drawWithCache {
            val y = 18.dp.toPx()
            val start = size.width / (2 * n)
            val end = size.width - start
            val brush = Brush.horizontalGradient(listOf(Film.scan.copy(alpha = 0.55f), Film.scan.copy(alpha = 0.15f)), startX = start, endX = end)
            val stroke = 2.dp.toPx()
            onDrawBehind { drawLine(brush, Offset(start, y), Offset(end, y), stroke, StrokeCap.Round) }
        },
    ) {
        names.forEachIndexed { i, name ->
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(FilmExtra.raisedHigh).border(1.5.dp, Film.scan, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Film.scan) }
                Spacer(Modifier.height(6.dp))
                Text(name, style = MaterialTheme.typography.labelMedium, color = Film.bone, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun StepRow(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(Film.scan.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelLarge, color = Film.scan) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 3.dp))
    }
}

@Composable
private fun LiveView(run: QuarantineRunState.Running, live: QuarantineObservations, catalog: TrackerCatalog, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(run.runId) {
        while (true) {
            now = SystemClock.elapsedRealtimeNanos()
            delay(500)
        }
    }
    val leftMs = run.startedAtNanos?.let { (run.plannedMs - (now - it) / 1_000_000).coerceAtLeast(0) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Quarantine run", onBack)
        Column(Modifier.padding(horizontal = 20.dp).weight(1f)) {
            LiveHero(run, live, leftMs, Modifier.reveal(0))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
                if (live.permissionDialogs.isNotEmpty()) {
                    item(key = "h-p") { SectionTitle("Permission dialogs (${live.permissionDialogs.size})") }
                    items(live.permissionDialogs, key = { "p-" + it.text }) { d ->
                        RuleRow(Film.warn, liveItemModifier()) {
                            Text("\"${d.text}\"", style = MaterialTheme.typography.bodyMedium)
                            if (!d.namesTarget) Note("Does not name this app; it may belong to another app.")
                        }
                    }
                }
                if (live.settingsScreens.isNotEmpty()) {
                    item(key = "h-s") { SectionTitle("Settings screens opened") }
                    items(live.settingsScreens, key = { "s-" + it.kind }) { st ->
                        RuleRow(Film.leak, liveItemModifier()) { Text(st.kind.title, style = MaterialTheme.typography.bodyMedium); Note("By the app or by you; TraceVeil cannot tell which.") }
                    }
                }
                observationItems(live, catalog)
                if (live.domains.isEmpty() && live.blocked.isEmpty() && live.permissionDialogs.isEmpty() && live.settingsScreens.isEmpty()) {
                    item(key = "empty") { EmptyHint("Nothing yet. Use the app; what it tries appears here.", glyph = Glyph.WATCHER) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineAction("Open the app", { DeviceChecks.launch(ctx, run.targetPackage) }, Modifier.weight(1f))
            GradientAction("Stop and restore internet", { QuarantineController.stop() }, Modifier.weight(1.4f))
        }
    }
}

/** The live card: recording dot, countdown with a draining bar, the current step, and the counts as numbers. */
@Composable
private fun LiveHero(run: QuarantineRunState.Running, live: QuarantineObservations, leftMs: Long?, modifier: Modifier = Modifier) {
    val wash = remember { tintWash(Film.leak) }
    Column(modifier.fillMaxWidth().cardSurface(glow = Film.leak).background(wash).padding(16.dp)) {
        Text(run.targetLabel, style = MaterialTheme.typography.headlineSmall, color = Film.bone, modifier = Modifier.semantics { heading() })
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RecordingDot(Film.leak)
            Spacer(Modifier.width(6.dp))
            Text("No internet for this app", style = MaterialTheme.typography.titleMedium, color = Film.leak, modifier = Modifier.weight(1f))
            if (leftMs != null) Text("${(leftMs + 999) / 1000} s left", style = MaterialTheme.typography.titleMedium, color = Film.bone)
        }
        if (leftMs != null && run.plannedMs > 0) {
            TimeLeftBar(leftMs.toFloat() / run.plannedMs, Modifier.padding(top = 10.dp))
        } else {
            Spacer(Modifier.height(6.dp))
        }
        Note(run.step)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val names = live.domains.size
            val packets = live.blockedPackets.toInt()
            val dialogs = live.permissionDialogs.size
            StatTile("$names", if (names == 1) "name looked up" else "names looked up", Modifier.weight(1f))
            StatTile("$packets", if (packets == 1) "blocked packet" else "blocked packets", Modifier.weight(1f))
            StatTile("$dialogs", if (dialogs == 1) "permission dialog" else "permission dialogs", Modifier.weight(1f))
        }
    }
}

/** A thin bar that drains as the run's time runs out; it glides between the half-second ticks unless motion is off. */
@Composable
private fun TimeLeftBar(fraction: Float, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), if (reduce) snap() else tween(500, easing = LinearEasing), label = "time-left")
    Box(
        modifier.fillMaxWidth().height(4.dp).drawBehind {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(Film.line, cornerRadius = r)
            drawRoundRect(Film.leak, size = Size(size.width * shown, size.height), cornerRadius = r)
        },
    )
    Spacer(Modifier.height(8.dp))
}

/** A number over a short label, read by TalkBack as one phrase. */
@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(shape).background(Film.base.copy(alpha = 0.45f)).border(1.dp, FilmExtra.hairline, shape)
            .padding(horizontal = 10.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = Film.bone, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
    }
}

@Composable
private fun ReportView(report: QuarantineReport, saved: Boolean, path: String?, catalog: TrackerCatalog, onBack: () -> Unit, onAgain: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val s = report.summary
    val exportScope = rememberCoroutineScope()
    var exportNote by remember(report.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Behaviour report", onBack)
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "hero") { ReportHero(report, Modifier.reveal(0)) }
            // Run-quality notes (for example "could not open the app") stay in view, right under the summary.
            items(report.notes) { n -> Callout(n, Modifier.reveal(1)) }
            if (s.dnsBypassSignal) item {
                Signal(Film.high, "Tried to reach addresses without looking them up", QuarantineEvidence.INFERRED, QuarantineText.bypassExplanation(s.addressesWithoutLookup), Modifier.reveal(1))
            }
            if (s.cleartextPersonalData.isNotEmpty()) item {
                // OBSERVED when a value itself had a personal-data shape (an e-mail address, a phone number); otherwise
                // only field names matched.
                val shaped = report.observations.connections.flatMap { it.http }.any { h -> CleartextPersonalData.scan(h).any { it.valueShape } }
                Signal(
                    Film.high, "Tried to send personal data without encryption",
                    if (shaped) QuarantineEvidence.OBSERVED else QuarantineEvidence.INFERRED,
                    QuarantineText.cleartextExplanation(s.cleartextPersonalData), Modifier.reveal(1),
                )
            } else if (s.cleartextRequests.isNotEmpty()) item {
                Signal(Film.warn, "Used plain HTTP (no encryption)", QuarantineEvidence.OBSERVED, QuarantineText.cleartextExplanation(s.cleartextRequests), Modifier.reveal(1))
            }
            if (s.namesWithoutLookup.isNotEmpty()) item {
                Signal(Film.warn, "Connected to names it never looked up", QuarantineEvidence.INFERRED, QuarantineText.namesWithoutLookupExplanation(s.namesWithoutLookup), Modifier.reveal(2))
            }
            if (s.directResolvers.isNotEmpty()) item {
                Signal(Film.warn, "Sent DNS straight to its own resolver", QuarantineEvidence.OBSERVED,
                    "Instead of the phone's resolver: ${s.directResolvers.joinToString(", ")}. Answered locally; nothing was forwarded.", Modifier.reveal(2))
            }
            if (s.encryptedDnsDestinations.isNotEmpty()) item {
                Signal(Film.warn, "Encrypted or TCP DNS was attempted", QuarantineEvidence.OBSERVED,
                    "${s.encryptedDnsDestinations.joinToString(", ")}. Names inside such lookups cannot be read; they were blocked. " +
                        "Sent by the app, or by Android's Private DNS on its behalf; this run cannot tell which.", Modifier.reveal(2))
            }
            items(report.observations.permissionDialogs) { d ->
                val times = if (d.timesShown > 1) " Shown ${d.timesShown} times." else ""
                val whose = if (d.namesTarget) "" else " The text does not name this app; it may belong to another app."
                Signal(Film.warn, "Permission dialog", QuarantineEvidence.OBSERVED, "\"${d.text}\"$times$whose", Modifier.reveal(3))
            }
            items(report.observations.settingsScreens) { st ->
                Signal(Film.leak, "${st.kind.title} screen opened", QuarantineEvidence.OBSERVED,
                    "Seen ${QuarantineText.plural(st.timesShown, "time")} during the run. TraceVeil cannot tell whether the app or you opened it.", Modifier.reveal(3))
            }
            if (!s.appCameToScreen) item {
                KeyCaveat("The app's own window was not seen (the watcher may be off, or the app did not open).")
            }

            observationItems(report.observations, catalog)

            item(key = "timeline") {
                if (report.timeline.isEmpty()) {
                    SectionTitle("Timeline")
                    EmptyHint("No events in this window.", glyph = Glyph.CLOCK)
                } else {
                    CollapsibleCard(
                        "Timeline", Modifier.padding(top = 16.dp),
                        summary = "${QuarantineText.plural(report.timeline.size, "event")} · first at +${QuarantineText.seconds(report.timeline.first().atMs)}",
                        glyph = Glyph.CLOCK,
                    ) {
                        report.timeline.forEach { e ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(Film.scan))
                                Spacer(Modifier.width(10.dp))
                                Text("+${QuarantineText.seconds(e.atMs)}", style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.width(64.dp).padding(top = 2.dp))
                                Text(e.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            item(key = "capture") {
                val o = report.observations
                CollapsibleCard(
                    "Capture details", Modifier.padding(top = 8.dp),
                    summary = "${o.dnsQueries} lookups · ${o.blockedPackets} packets blocked", glyph = Glyph.SEARCH,
                ) {
                    Note("${o.dnsQueries} lookups (all answered \"does not exist\" on this phone), ${o.blockedPackets} packets blocked (none forwarded; connections TraceVeil answered count their first packet here).")
                    if (o.connectionsRead == true) {
                        Note("${QuarantineText.plural(o.connections.size, "connection")} answered on the phone by TraceVeil and closed after the first message; nothing was forwarded.")
                    }
                    if (s.echConnections > 0) {
                        Note("${QuarantineText.plural(s.echConnections, "connection")} offered Encrypted Client Hello, so the name shown may be the provider's public name rather than the real server.")
                    }
                    if (o.networkControlPackets > 0) Note("${o.networkControlPackets} Android network-control packets (e.g. IPv6 neighbour discovery) were dropped and not counted as the app's attempts.")
                    if (o.unreadablePackets > 0) Note("${o.unreadablePackets} packets could not be read and were dropped.")
                    if (o.domainsOverflow + o.destinationsOverflow + o.uiEventsOverflow > 0) {
                        Note("Some new names, destinations or screens arrived after a list was full; they were still blocked and counted.", Film.warn)
                    }
                    if (report.privateDnsActive == true) Note("Private DNS was on, so some lookups may appear only as blocked connections (for example port 853) rather than as names.")
                }
            }

            item(key = "limits") {
                CollapsibleCard("Limits", Modifier.padding(top = 8.dp), summary = "What this run cannot see", glyph = Glyph.INFO) {
                    report.limits.forEach { Note("• $it") }
                }
            }

            item(key = "footer") {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        GlyphIcon(Glyph.LOCK, Film.clear, Modifier.padding(top = 2.dp), size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Note(if (path != null && saved) "Saved on this phone: $path" else if (path != null) "Could not save this report on the phone." else "Saved on this phone.")
                    }
                    FoldText("To remove an app you do not trust, uninstall it from App info. TraceVeil never removes apps itself.")
                    OutlineAction("Export report (JSON)", { exportScope.launch { exportNote = exportQuarantineJson(ctx, report, share = false) } }, glyph = Glyph.REPORTS)
                    LinkAction("Share report (JSON)", { exportScope.launch { exportNote = exportQuarantineJson(ctx, report, share = true) } })
                    exportNote?.let { Note(it) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryAction("App info", { runCatching { DeviceChecks.openAppInfo(ctx, report.targetPackage) } }, Modifier.weight(1f))
            SecondaryAction("Run again", onAgain, Modifier.weight(1f))
            GradientAction("Done", onDone, Modifier.weight(1f))
        }
    }
}

/**
 * The report's hero: the app, when and how long, the chips that say what this is not, the counts as numbers and the
 * one-sentence summary. An empty run shows its full sentence, so "not proof the app does nothing" is always in view.
 */
@Composable
private fun ReportHero(report: QuarantineReport, modifier: Modifier = Modifier) {
    val s = report.summary
    // Attention colour, never a verdict: amber for attempts, orange for hard-coded addresses or settings screens.
    val tone = when {
        s.nothingObserved -> Film.scan
        s.dnsBypassSignal || s.settingsScreens.isNotEmpty() -> Film.high
        else -> Film.warn
    }
    val wash = remember(tone) { tintWash(tone) }
    Column(modifier.fillMaxWidth().cardSurface(glow = tone).background(wash).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Glyph.REPORTS, tone, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(report.targetLabel, style = MaterialTheme.typography.headlineSmall, color = Film.bone, modifier = Modifier.semantics { heading() })
                HostText(report.targetPackage, Film.boneDim)
            }
        }
        Text(
            "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(report.startedAtEpochMs))} · " +
                "quarantined ${QuarantineText.seconds(report.observedMs)} of ${QuarantineText.duration(report.plannedDurationMs)} · ${report.stopReason.label}",
            style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(12.dp))
        NotAVerdictChips()
        Spacer(Modifier.height(12.dp))
        if (s.nothingObserved) {
            Text(QuarantineText.headline(report), style = MaterialTheme.typography.bodyLarge, color = Film.bone)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("${s.uniqueDomains}", if (s.uniqueDomains == 1) "name looked up" else "names looked up", Modifier.weight(1f))
                StatTile("${s.uniqueIps}", if (s.uniqueIps == 1) "address tried" else "addresses tried", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("${s.blockedPackets}", if (s.blockedPackets == 1L) "packet blocked" else "packets blocked", Modifier.weight(1f))
                StatTile("${s.permissionDialogs}", if (s.permissionDialogs == 1) "permission dialog" else "permission dialogs", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(QuarantineText.headline(report), style = MaterialTheme.typography.bodyMedium, color = Film.bone)
        }
    }
}

/** Writes the traceveil.quarantine.v1 file, then saves it to Downloads/TraceVeil or opens the share sheet. */
private suspend fun exportQuarantineJson(ctx: Context, report: QuarantineReport, share: Boolean): String? {
    val file = withContext(Dispatchers.IO) { runCatching { QuarantineJsonExport.write(File(ctx.cacheDir, "exports"), report) }.getOrNull() }
        ?: return "Could not build the JSON export."
    if (share) {
        ExportManager.share(ctx, file, AuditJsonExport.MIME)
        return null
    }
    val ok = withContext(Dispatchers.IO) { ExportManager.saveToDownloads(ctx, file, file.name, AuditJsonExport.MIME) }
    return if (ok) "Saved to Downloads/TraceVeil as ${file.name}" else "Could not save the report to Downloads."
}

/** New rows fade and slide into place as they arrive; they simply appear when motion is off. */
@Composable
private fun LazyItemScope.liveItemModifier(): Modifier =
    if (LocalReduceMotion.current) Modifier
    else Modifier.animateItem(
        fadeInSpec = tween(Motion.MEDIUM, easing = Motion.enter),
        placementSpec = tween(Motion.MEDIUM, easing = Motion.emphasized),
        fadeOutSpec = tween(Motion.SHORT, easing = Motion.exit),
    )

/** Names, answered connections, blocked destinations: shared by the live list and the report. */
private fun LazyListScope.observationItems(o: QuarantineObservations, catalog: TrackerCatalog) {
    if (o.domains.isNotEmpty()) {
        item(key = "h-d") { SectionTitle("Names it tried to look up (${o.domains.size})") }
        items(o.domains, key = { "d-" + it.hostname }) { DomainRow(it, liveItemModifier()) }
    }
    if (o.connections.isNotEmpty()) {
        item(key = "h-c") {
            Column {
                SectionTitle("Connections answered on the phone (${o.connections.size})")
                Note("TraceVeil answered these itself and closed them after the first message. Nothing was forwarded.")
            }
        }
        items(o.connections.sortedBy { it.startNanos }, key = { "c-" + it.id }) { ConnectionRow(it, catalog, liveItemModifier()) }
    }
    val app = o.blocked.filter { it.role == DestinationRole.APP_DESTINATION }
    val resolver = o.blocked.filter { it.role == DestinationRole.PHONE_RESOLVER }
    if (app.isNotEmpty()) {
        item(key = "h-b") { SectionTitle("Connections blocked (${app.map { it.address to it.port }.distinct().size})") }
        val answered = o.connections.map { it.dstIp to it.dstPort }.toSet()
        items(app, key = { "b-" + it.protocol + it.endpoint }) { BlockedRow(it, liveItemModifier(), answered = (it.address to it.port) in answered) }
    }
    if (resolver.isNotEmpty()) {
        item(key = "h-r") {
            SectionTitle("Blocked DNS to the phone's own resolver")
            Note("Usually Android checking for Private DNS, not necessarily the app.")
        }
        items(resolver, key = { "r-" + it.protocol + it.endpoint }) { BlockedRow(it, liveItemModifier()) }
    }
}

@Composable
private fun DomainRow(d: DomainAttempt, modifier: Modifier = Modifier) {
    RuleRow(Film.scan, modifier) {
        HostText(d.hostname)
        val via = d.resolvers.filter { it !in QuarantineNet.RESOLVERS }
        Note(
            "${QuarantineText.plural(d.count.toInt(), "lookup")} · answered \"does not exist\"" +
                if (via.isNotEmpty()) " · sent straight to ${via.joinToString(", ")}" else "",
        )
    }
}

@Composable
private fun ConnectionRow(f: FlowRecord, catalog: TrackerCatalog, modifier: Modifier = Modifier) {
    val tracker = remember(f.host, catalog) {
        f.host?.let { catalog.classify(it) }?.takeIf { it.entity != null && it.category.isTracking }
            ?.let { "${it.entity} (${it.category.name.lowercase().replace('_', ' ')})" }
    }
    RuleRow(if (f.tls == TlsStatus.CLEARTEXT) Film.high else Film.scan, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { HostText(f.host ?: "Name not read") }
            Tag(QuarantineText.connectionLabel(f), Film.boneDim)
        }
        Note(QuarantineText.connectionNote(f, tracker))
    }
}

@Composable
private fun BlockedRow(b: BlockedAttempt, modifier: Modifier = Modifier, answered: Boolean = false) {
    RuleRow(if (b.role == DestinationRole.PHONE_RESOLVER) Film.warn else Film.leak, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { HostText(b.endpoint) }
            Tag(b.protocol.label, Film.boneDim)
        }
        val scope = QuarantineText.scopeNote(b.scope)
        // An answered connection's SYN was replied to on the phone, not dropped; either way nothing was forwarded.
        val fate = if (answered) " · answered on the phone, nothing forwarded" else " dropped"
        Note(QuarantineText.plural(b.packets.toInt(), "packet") + fate + if (scope != null) " · $scope" else "")
    }
}

@Composable
private fun Signal(color: Color, title: String, evidence: QuarantineEvidence, body: String, modifier: Modifier = Modifier) {
    RuleRow(color, modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Tag(evidence.name.replace('_', ' '), if (evidence == QuarantineEvidence.OBSERVED) Film.scan else Film.warn)
        }
        Spacer(Modifier.height(4.dp))
        Note(body)
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) Film.scan else Film.raised)
            .border(1.dp, if (selected) Film.scan else Film.line, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Film.base else Film.bone)
    }
}

/** A faint wash of [c] from the top-left corner, fading into the card. */
private fun tintWash(c: Color): Brush = Brush.linearGradient(listOf(c.copy(alpha = 0.20f), c.copy(alpha = 0.06f), Color.Transparent))
