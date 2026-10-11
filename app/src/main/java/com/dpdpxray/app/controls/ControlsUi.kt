package com.dpdpxray.app.controls

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.automation.AutomationStore
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.graph
import com.dpdpxray.app.ui.BackupUi
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.SetupSteps
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.screens.SetupContent
import com.dpdpxray.app.ui.system.Group
import com.dpdpxray.app.ui.system.GroupDivider
import com.dpdpxray.app.ui.system.ListRow
import com.dpdpxray.app.ui.system.StatusDot
import com.dpdpxray.app.ui.system.TextAction
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.voice.OnlineVoiceSwitch
import com.dpdpxray.app.voice.wake.WakeController
import com.dpdpxray.app.voice.wake.WakeRuntime
import com.dpdpxray.app.voice.wake.WakeStopReason
import com.dpdpxray.core.export.AuditBackups
import com.dpdpxray.core.export.AuditBundles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * One home per control. Home's Setup sheet (SetupSteps, with its Fix / Allow / Manage buttons) owns the check VPN, the
 * consent watcher, Private DNS and Shizuku. This file adds the rest of what TraceVeil uses to that same sheet
 * ([ControlsSheetGroups]), puts a one-line count on Home that opens it ([HomeControlsSection]), and shows the same sheet
 * content as a full page ([ControlsScreen], Settings › Permissions & background).
 */

/**
 * The live inventory for the current mode, or null until Android's part has been read. TraceVeil's own switches come
 * from their stores' flows, so a flip shows at once; Android's permissions are read again on every resume, so a change
 * made in Android's settings shows as soon as the person comes back.
 */
@Composable
internal fun rememberControls(vm: XrayViewModel): List<Control>? {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val audits by vm.audits.collectAsState()
    val progress by vm.progress.collectAsState()
    val runner by remember(ctx) { AutomationStore.get(ctx).settings }.collectAsState()
    val voiceAllowed by remember(ctx) { OnlineVoiceSwitch.get(ctx).allowed }.collectAsState()
    val wake by WakeRuntime.state.collectAsState()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val phone by produceState<PhoneReadings?>(null, resumes) {
        value = withContext(Dispatchers.IO) { ControlsReader.read(ctx) }
    }
    val p = phone ?: return null
    val readings = ControlsReader.readings(
        settings, p, wakeListening = wake.active, onlineVoiceAllowed = voiceAllowed, runnerOn = runner.enabled,
        savedChecks = audits.size, checkRunning = progress is AuditProgress.Running,
    )
    return ControlsInventory.build(readings, settings.audience)
}

/** The one summary line over this inventory and the Setup sheet's items ("8 on · 4 off"), or null while reading. */
@Composable
private fun rememberSummary(vm: XrayViewModel, controls: List<Control>?): ControlsSummary? {
    val pf by vm.preflight.collectAsState()
    if (controls == null) return null
    return ControlsInventory.summary(controls, ControlsInventory.setupStates(SetupSteps.from(pf), pf.otherVpnActive))
}

/** What a row's control does: [flip] writes a TraceVeil switch to its store; [open] opens Android's own screen. */
internal class ControlActions(val flip: (ControlId, Boolean) -> Unit, val open: (SystemScreen) -> Unit)

/**
 * TraceVeil's switches are written to the same stores Settings writes, so the code that already follows them reacts:
 * Auto-guard's job sync cancels or schedules its jobs, the install watcher reads the new value, the runner refuses
 * requests, the voice copilot picks its voice. Android's permissions are only ever opened; TraceVeil grants or revokes
 * nothing itself.
 */
@Composable
internal fun rememberControlActions(vm: XrayViewModel): ControlActions {
    val ctx = LocalContext.current
    return remember(ctx, vm) {
        ControlActions(
            flip = { id, on ->
                when (ControlsInventory.storeOf(id)) {
                    SwitchStore.SETTINGS -> {
                        if (ControlsInventory.stopsListening(id, on)) WakeController.stop(WakeStopReason.OPTED_OUT)
                        vm.updateSettings { s -> ControlsInventory.settingsAfter(id, on, s) ?: s }
                    }
                    SwitchStore.RUNNER -> AutomationStore.get(ctx).setEnabled(on)
                    SwitchStore.ONLINE_VOICE -> OnlineVoiceSwitch.get(ctx).set(on)
                    null -> Unit
                }
            },
            open = { screen ->
                when (screen) {
                    SystemScreen.NOTIFICATIONS -> openOrAppInfo(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                    SystemScreen.APP_PERMISSIONS -> runCatching { DeviceChecks.openAppInfo(ctx, ctx.packageName) }
                    SystemScreen.INSTALL_UNKNOWN_APPS -> openOrAppInfo(ctx, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                }
            },
        )
    }
}

/** Opens [intent]; on a phone without that screen, TraceVeil's App info instead. */
private fun openOrAppInfo(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recoverCatching { DeviceChecks.openAppInfo(ctx, ctx.packageName) }
}

/**
 * Home's "What TraceVeil uses": one line ("8 on · 4 off", "Switch off any time") whose Manage opens the Setup sheet,
 * which lists every permission and switch with its state and its switch or Android button. Both modes.
 */
@Composable
fun HomeControlsSection(vm: XrayViewModel, lang: GuideLang, onManage: () -> Unit, modifier: Modifier = Modifier) {
    val summary = rememberSummary(vm, rememberControls(vm))
    Column(modifier.fillMaxWidth()) {
        Text(
            ControlsCopy.title.pick(lang), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
        )
        Group {
            ListRow(
                summary?.line?.pick(lang) ?: ControlsCopy.reading.pick(lang), subtitle = ControlsCopy.switchOffAnyTime.pick(lang),
                leading = { GlyphIcon(Glyph.VPN, Film.boneDim, size = 24.dp) },
                trailing = { TextAction(ControlsCopy.manage.pick(lang), onManage) },
            )
        }
    }
}

/**
 * The rest of what TraceVeil uses, as groups under the Setup sheet's own: other phone permissions (notifications, the
 * microphone), TraceVeil's own switches, and saved checks. Used inside Home's sheet and on Permissions & background.
 */
@Composable
fun ControlsSheetGroups(vm: XrayViewModel, lang: GuideLang) {
    val controls = rememberControls(vm)
    val actions = rememberControlActions(vm)
    if (controls == null) {
        Spacer(Modifier.height(20.dp))
        Text(ControlsCopy.reading.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(horizontal = 16.dp))
        return
    }
    ControlsInventory.groups(controls).forEach { g ->
        Spacer(Modifier.height(20.dp))
        Group(title = ControlsCopy.groupTitle(g).pick(lang), footer = ControlsCopy.groupFooter(g)?.pick(lang)) {
            controls.filter { it.group == g }.forEachIndexed { i, c ->
                if (i > 0) GroupDivider()
                ControlRow(c, lang, actions, vm)
            }
        }
    }
}

/**
 * Settings › "Permissions & background": the same content as Home's sheet (the Setup items with their buttons, then the
 * groups above), as a page reachable from More and Settings. Read again on every return to the page.
 */
@Composable
fun ControlsScreen(vm: XrayViewModel, onBack: () -> Unit, onLab: () -> Unit, onTrust: (() -> Unit)? = null) {
    val lang = guideLang()
    val summary = rememberSummary(vm, rememberControls(vm))
    Column(Modifier.fillMaxSize()) {
        TopBar(ControlsCopy.pageTitle.pick(lang), onBack)
        SetupContent(
            vm, lang, title = null, onLab = onLab,
            lead = {
                Text(ControlsCopy.pageIntro.pick(lang), style = MaterialTheme.typography.bodyLarge, color = Film.boneDim)
                Text(
                    summary?.line?.pick(lang) ?: ControlsCopy.reading.pick(lang), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                )
            },
            extra = {
                ControlsSheetGroups(vm, lang)
                Spacer(Modifier.height(24.dp))
                Text(ControlsCopy.installTime.pick(lang), style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(horizontal = 16.dp))
                if (onTrust != null) TextAction(ControlsCopy.openTrust.pick(lang), onTrust)
            },
        )
    }
}

/**
 * One row: a state dot, the name, "state · what it does" and how to change it; then its switch (flipped in place) or
 * the button that opens Android's screen.
 */
@Composable
private fun ControlRow(c: Control, lang: GuideLang, actions: ControlActions, vm: XrayViewModel) {
    val stateLine = if (c.group == ControlGroup.DATA) c.what.pick(lang) else ControlsCopy.state(c.state).pick(lang) + " · " + c.what.pick(lang)
    val subtitle = stateLine + "\n" + c.hint.pick(lang)
    val dot: @Composable () -> Unit = { StatusDot(if (c.state == ControlState.ON) Film.clear else Film.graphic) }
    when (val a = c.action) {
        is ControlAction.Toggle -> ListRow(
            c.title.pick(lang),
            Modifier.toggleable(value = a.on, enabled = a.enabled, role = Role.Switch) { actions.flip(c.id, it) },
            subtitle = subtitle, subtitleMaxLines = 6, leading = dot,
            titleColor = if (a.enabled) Film.bone else Film.boneDim,
            trailing = {
                Switch(
                    checked = a.on, onCheckedChange = null, enabled = a.enabled,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Film.scan, checkedThumbColor = Film.onAccent, checkedBorderColor = Color.Transparent,
                        uncheckedTrackColor = Film.fill, uncheckedThumbColor = Film.graphic, uncheckedBorderColor = Color.Transparent,
                    ),
                )
            },
        )
        is ControlAction.Open -> ListRow(
            c.title.pick(lang), subtitle = subtitle, subtitleMaxLines = 6, leading = dot,
            trailing = { TextAction(a.label.pick(lang), { actions.open(a.screen) }) },
        )
        is ControlAction.Checks -> ChecksRow(c, a, lang, vm, subtitle)
        ControlAction.None -> ListRow(c.title.pick(lang), subtitle = subtitle, subtitleMaxLines = 6, leading = dot)
    }
}

/**
 * "Your checks · 5 saved on this phone": Back up writes every saved check to a file the person picks (the same backup
 * as Reports); Delete all asks first, says it cannot be undone, and waits while a check is running.
 */
@Composable
private fun ChecksRow(c: Control, a: ControlAction.Checks, lang: GuideLang, vm: XrayViewModel, subtitle: String) {
    val ctx = LocalContext.current
    val backup by vm.backup.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AuditBackups.MIME)) { uri ->
        if (uri != null) vm.backupTo(uri)
    }
    Column(Modifier.fillMaxWidth()) {
        ListRow(c.title.pick(lang), subtitle = subtitle, subtitleMaxLines = 6, leading = { GlyphIcon(Glyph.REPORTS, Film.boneDim, size = 24.dp) })
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            RowAction(ControlsCopy.backUp.pick(lang), a.canBackUp && backup !is BackupUi.Working, Film.scan) {
                create.launch(AuditBackups.fileName(AuditBundles.now()))
            }
            RowAction(ControlsCopy.deleteAll.pick(lang), a.canDelete, Film.leak) { confirm = true }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Film.raised,
            title = { Text(ControlsCopy.deleteTitle(a.saved).pick(lang), color = Film.bone) },
            text = { Text(ControlsCopy.deleteBody(a.saved).pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim) },
            confirmButton = {
                TextAction(ControlsCopy.deleteAll.pick(lang), {
                    confirm = false
                    deleteAllChecks(ctx, vm)
                }, color = Film.leak)
            },
            dismissButton = { TextAction(ControlsCopy.cancel.pick(lang), { confirm = false }, color = Film.boneDim) },
        )
    }
}

/** Deletes every saved check (its folder in TraceVeil's private storage). Never while a check is running. */
private fun deleteAllChecks(ctx: Context, vm: XrayViewModel) {
    if (vm.progress.value is AuditProgress.Running) return
    val ids = vm.audits.value.map { it.id }
    val graph = ctx.graph
    graph.scope.launch { ids.forEach { id -> runCatching { graph.repository.delete(id) } } }
}

/** A compact text button that can be disabled, for the two actions under "Your checks". */
@Composable
private fun RowAction(text: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else Film.tertiary)
    }
}
