package com.dpdpxray.app.quarantine

import android.net.VpnService
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.HostText
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.StatusDot
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.quarantine.BlockedAttempt
import com.dpdpxray.core.quarantine.DestinationRole
import com.dpdpxray.core.quarantine.DomainAttempt
import com.dpdpxray.core.quarantine.QuarantineEvidence
import com.dpdpxray.core.quarantine.QuarantineObservations
import com.dpdpxray.core.quarantine.QuarantineReport
import com.dpdpxray.core.quarantine.QuarantineReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

    val current = run
    when {
        viewing != null -> ReportView(viewing!!, saved = true, path = null, onBack = { viewing = null }, onAgain = { viewing = null }, onDone = { viewing = null })
        current is QuarantineRunState.Running && current.targetPackage == packageName -> LiveView(current, live, onBack)
        current is QuarantineRunState.Running -> Column(Modifier.fillMaxSize()) {
            TopBar("Quarantine run", onBack)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Note("Another quarantine run is active for ${current.targetLabel}. Only one app can be quarantined at a time.", Film.warn)
                PrimaryAction("Stop that run", { QuarantineController.stop() })
            }
        }
        current is QuarantineRunState.Finished && current.report.targetPackage == packageName ->
            ReportView(current.report, current.saved, current.path, onBack = onBack, onAgain = { QuarantineController.acknowledge() }, onDone = { QuarantineController.acknowledge(); onBack() })
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
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label ?: packageName, style = MaterialTheme.typography.headlineMedium)
            HostText(packageName, Film.boneDim)
            Tag(QuarantineText.NOT_A_VERDICT, Film.warn)
            Spacer(Modifier.height(4.dp))
            Text(QuarantineText.EXPLAINER, style = MaterialTheme.typography.bodyLarge)

            SectionTitle("How it works")
            QuarantineText.STEPS.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(4.dp))
            RuleRow(Film.leak) {
                Text("Only internet is blocked", style = MaterialTheme.typography.titleMedium)
                Note(QuarantineText.ONLY_INTERNET)
            }

            SectionTitle("How long")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuarantineController.DURATIONS_MS.forEach { ms -> Choice(QuarantineText.duration(ms), ms == durationMs) { durationMs = ms } }
            }
            Note("You can stop earlier at any time. Stopping gives the app its internet back.")

            Spacer(Modifier.height(8.dp))
            if (!watcherOn) Note("The TraceVeil watcher is off in Accessibility settings, so permission dialogs and settings screens will not be recorded. Network attempts still will.", Film.warn)
            if (label == null) Note("This app is not installed on this phone.", Film.leak)
            (error ?: failure)?.let { Note(it, Film.leak) }
            if (!vpnReady) {
                PrimaryAction("Allow VPN permission", { VpnService.prepare(ctx)?.let { vpnLauncher.launch(it) } ?: onRefresh() })
                Note("Android asks once. In a quarantine run the VPN covers only the app you choose.")
            } else {
                PrimaryAction("Start quarantine run", {
                    error = QuarantineController.start(ctx, packageName, label ?: packageName, durationMs)
                }, enabled = label != null)
            }

            lastSaved?.let { r ->
                Spacer(Modifier.height(4.dp))
                SecondaryAction("Last run ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startedAtEpochMs))} · view report", { onView(r) }, Modifier.fillMaxWidth())
            }

            SectionTitle("What this cannot see")
            QuarantineReports.LIMITS.forEach { Note("• $it") }
        }
    }
}

@Composable
private fun LiveView(run: QuarantineRunState.Running, live: QuarantineObservations, onBack: () -> Unit) {
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
            Text(run.targetLabel, style = MaterialTheme.typography.headlineMedium)
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(false)
                Spacer(Modifier.width(10.dp))
                Text("No internet for this app", style = MaterialTheme.typography.titleMedium, color = Film.leak)
                Spacer(Modifier.weight(1f))
                if (leftMs != null) Text("${(leftMs + 999) / 1000} s left", style = MaterialTheme.typography.titleMedium)
            }
            Note(run.step)
            Text(
                "${QuarantineText.plural(live.domains.size, "name")} looked up · ${QuarantineText.plural(live.blockedPackets.toInt(), "blocked packet")} · " +
                    QuarantineText.plural(live.permissionDialogs.size, "permission dialog"),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                observationItems(live)
                if (live.domains.isEmpty() && live.blocked.isEmpty() && live.permissionDialogs.isEmpty() && live.settingsScreens.isEmpty()) {
                    item { Note("Nothing yet. Use the app; what it tries appears here.") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryAction("Open the app", { DeviceChecks.launch(ctx, run.targetPackage) }, Modifier.weight(1f))
            PrimaryAction("Stop and restore internet", { QuarantineController.stop() }, Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun ReportView(report: QuarantineReport, saved: Boolean, path: String?, onBack: () -> Unit, onAgain: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val s = report.summary
    Column(Modifier.fillMaxSize()) {
        TopBar("Behaviour report", onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(report.targetLabel, style = MaterialTheme.typography.headlineMedium)
                HostText(report.targetPackage, Film.boneDim)
                Note(
                    "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(report.startedAtEpochMs))} · " +
                        "quarantined ${QuarantineText.seconds(report.observedMs)} of ${QuarantineText.duration(report.plannedDurationMs)} · ${report.stopReason.label}",
                )
                Spacer(Modifier.height(6.dp))
                Tag(QuarantineText.NOT_A_VERDICT, Film.warn)
                Spacer(Modifier.height(8.dp))
                Text(QuarantineText.headline(report), style = MaterialTheme.typography.bodyLarge)
            }
            if (s.dnsBypassSignal) item {
                Signal(Film.high, "Tried to reach addresses without looking them up", QuarantineEvidence.INFERRED, QuarantineText.bypassExplanation(s.addressesWithoutLookup))
            }
            if (s.directResolvers.isNotEmpty()) item {
                Signal(Film.warn, "Sent DNS straight to its own resolver", QuarantineEvidence.OBSERVED,
                    "Instead of the phone's resolver: ${s.directResolvers.joinToString(", ")}. Answered locally; nothing was forwarded.")
            }
            if (s.encryptedDnsDestinations.isNotEmpty()) item {
                Signal(Film.warn, "Tried encrypted or TCP DNS", QuarantineEvidence.OBSERVED,
                    "${s.encryptedDnsDestinations.joinToString(", ")}. Names inside such lookups cannot be read; they were blocked.")
            }
            items(report.observations.permissionDialogs) { d ->
                val times = if (d.timesShown > 1) " Shown ${d.timesShown} times." else ""
                val whose = if (d.namesTarget) "" else " The text does not name this app; it may belong to another app."
                Signal(Film.warn, "Permission dialog", QuarantineEvidence.OBSERVED, "\"${d.text}\"$times$whose")
            }
            items(report.observations.settingsScreens) { st ->
                Signal(Film.leak, "${st.kind.title} screen opened", QuarantineEvidence.OBSERVED,
                    "Seen ${QuarantineText.plural(st.timesShown, "time")} during the run. TraceVeil cannot tell whether the app or you opened it.")
            }
            if (!s.appCameToScreen) item {
                Note("The app's own window was not seen (the watcher may be off, or the app did not open).", Film.warn)
            }

            observationItems(report.observations)

            item { SectionTitle("Timeline") }
            if (report.timeline.isEmpty()) item { Note("No events in this window.") }
            items(report.timeline) { e ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("+${QuarantineText.seconds(e.atMs)}", style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.width(64.dp))
                    Text(e.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }

            item {
                SectionTitle("Capture details")
                val o = report.observations
                Note("${o.dnsQueries} lookups (all answered \"does not exist\" on this phone), ${o.blockedPackets} packets dropped.")
                if (o.networkControlPackets > 0) Note("${o.networkControlPackets} Android network-control packets (e.g. IPv6 neighbour discovery) were dropped and not counted as the app's attempts.")
                if (o.unreadablePackets > 0) Note("${o.unreadablePackets} packets could not be read and were dropped.")
                if (o.domainsOverflow + o.destinationsOverflow + o.uiEventsOverflow > 0) {
                    Note("Some new names, destinations or screens arrived after a list was full; they were still blocked and counted.", Film.warn)
                }
                if (report.privateDnsActive == true) Note("Private DNS was on, so some lookups may appear only as blocked connections (for example port 853) rather than as names.")
                report.notes.forEach { Note(it, Film.warn) }
                SectionTitle("Limits")
                report.limits.forEach { Note("• $it") }
                Spacer(Modifier.height(8.dp))
                Note(if (path != null && saved) "Saved on this phone: $path" else if (path != null) "Could not save this report on the phone." else "Saved on this phone.")
                Spacer(Modifier.height(8.dp))
                Note("To remove an app you do not trust, uninstall it from App info. TraceVeil never removes apps itself.")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryAction("App info", { runCatching { DeviceChecks.openAppInfo(ctx, report.targetPackage) } }, Modifier.weight(1f))
            SecondaryAction("Run again", onAgain, Modifier.weight(1f))
            PrimaryAction("Done", onDone, Modifier.weight(1f))
        }
    }
}

/** Names, blocked destinations: shared by the live list and the report. */
private fun LazyListScope.observationItems(o: QuarantineObservations) {
    if (o.domains.isNotEmpty()) {
        item { SectionTitle("Names it tried to look up (${o.domains.size})") }
        items(o.domains, key = { "d-" + it.hostname }) { DomainRow(it) }
    }
    val app = o.blocked.filter { it.role == DestinationRole.APP_DESTINATION }
    val resolver = o.blocked.filter { it.role == DestinationRole.PHONE_RESOLVER }
    if (app.isNotEmpty()) {
        item { SectionTitle("Connections blocked (${app.map { it.address to it.port }.distinct().size})") }
        items(app, key = { "b-" + it.protocol + it.endpoint }) { BlockedRow(it) }
    }
    if (resolver.isNotEmpty()) {
        item { SectionTitle("Blocked DNS to the phone's own resolver") }
        items(resolver, key = { "r-" + it.protocol + it.endpoint }) { BlockedRow(it) }
    }
}

@Composable
private fun DomainRow(d: DomainAttempt) {
    RuleRow(Film.scan) {
        HostText(d.hostname)
        val via = d.resolvers.filter { it !in QuarantineNet.RESOLVERS }
        Note(
            "${QuarantineText.plural(d.count.toInt(), "lookup")} · answered \"does not exist\"" +
                if (via.isNotEmpty()) " · sent straight to ${via.joinToString(", ")}" else "",
        )
    }
}

@Composable
private fun BlockedRow(b: BlockedAttempt) {
    RuleRow(if (b.role == DestinationRole.PHONE_RESOLVER) Film.warn else Film.leak) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { HostText(b.endpoint) }
            Tag(b.protocol.label, Film.boneDim)
        }
        val scope = QuarantineText.scopeNote(b.scope)
        Note(QuarantineText.plural(b.packets.toInt(), "packet") + " dropped" + if (scope != null) " · $scope" else "")
    }
}

@Composable
private fun Signal(color: Color, title: String, evidence: QuarantineEvidence, body: String) {
    RuleRow(color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Tag(evidence.name.replace('_', ' '), if (evidence == QuarantineEvidence.OBSERVED) Film.scan else Film.warn)
        }
        Note(body)
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Film.scan else Film.boneDim
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Film.base else Film.bone,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Film.scan else Film.raised)
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
