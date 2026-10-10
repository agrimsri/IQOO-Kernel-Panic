package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.control.AuditableApp
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.model.AuditPlan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(vm: XrayViewModel, onBack: () -> Unit, onStarted: () -> Unit) {
    val apps by vm.apps.collectAsState()
    val pf by vm.preflight.collectAsState()
    val settings by vm.settings.collectAsState()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AuditableApp?>(null) }
    LaunchedEffect(Unit) { vm.refreshPreflight(); vm.loadApps() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Choose an app", onBack)
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        val q = query.trim()
        val shown = apps.filter { q.isBlank() || it.label.contains(q, true) || it.packageName.contains(q, true) }
        if (apps.isEmpty()) Note("Loading installed apps…", Film.boneDim)
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(shown, key = { it.packageName }) { app ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = app }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bmp = remember(app.packageName) { runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
                    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Text(app.packageName, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                    }
                }
            }
        }
    }

    selected?.let { app ->
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = Film.raised) {
            AuditSetup(app, shizukuReady = pf.shizukuGranted, preferPreview = settings.fastDemo) { config ->
                selected = null
                if (vm.startAudit(config)) onStarted()
            }
        }
    }
}

@Composable
private fun AuditSetup(app: AuditableApp, shizukuReady: Boolean, preferPreview: Boolean, onStart: (AuditConfig) -> Unit) {
    val controlledDemo = app.packageName == "com.leakyshop.demo" || app.packageName == "com.leakyshop.demo.fixed"
    var allowReset by remember { mutableStateOf(controlledDemo) }
    val freshReset = allowReset && shizukuReady
    var full by remember { mutableStateOf(shizukuReady && controlledDemo) }
    var preview by remember { mutableStateOf(preferPreview && !(shizukuReady && controlledDemo)) }
    var agent by remember { mutableStateOf(controlledDemo) }
    var child by remember { mutableStateOf(false) }
    var gms by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Audit ${app.label}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (shizukuReady) Toggle("Allow clearing this test app's local data", "Developer testing only. Removes its saved state, accounts and settings before each run. Leave off for personal apps.", allowReset) {
            allowReset = it
            if (!it) full = false
        }
        PlanOption(
            "Full audit · normal timing",
            "Three fresh-state runs: Silent, Reject, Accept. Observes Silent for 25 seconds and each choice for 15 seconds; launch, consent and AI review add time.",
            full, enabled = freshReset,
        ) { full = true; preview = false }
        if (!freshReset) Note("Full audits need Shizuku and explicit approval to reset a test app between runs.", Film.warn)
        PlanOption("Quick audit · normal timing", "One Accept-plan run with 15 seconds of observation. No Silent or Reject comparison.", !full && !preview, enabled = true) { full = false; preview = false }
        PlanOption("Fast Preview · limited evidence", "One Accept-plan run with 8 seconds of observation and a shorter consent-screen wait. Delayed traffic may be missed; not a Full audit.", preview, enabled = true) { full = false; preview = true }
        if (!freshReset) Note("Quick keeps the app's data. An already-opened app may keep its consent choice and not repeat launch-time DNS lookups. A missing dialog or quiet run is not a privacy pass.", Film.warn)
        else Note("Starting an audit clears this selected app's local data before each plan. Use a test app or a backed-up development build; do not use a personal app with data you need.", Film.warn)

        Spacer(Modifier.height(8.dp))
        Toggle("Let the agent tap Accept and Reject", "Off: you tap them yourself when asked.", agent) { agent = it }
        Toggle("This app is used by children", "Adds the DPDP section 9 check: no tracking or targeted ads for children.", child) { child = it }
        Toggle("Include Google Play services lookups", "Some SDKs upload through Play services. Those lookups may include other apps' traffic.", gms) { gms = it }

        Spacer(Modifier.height(12.dp))
        PrimaryAction("Start audit", {
            onStart(
                AuditConfig(
                    targetPackage = app.packageName, targetLabel = app.label,
                    plans = if (full && freshReset) listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT) else listOf(AuditPlan.ACCEPT),
                    childFacing = child, includeGms = gms, autoDrive = agent, resetAppData = freshReset, fastPreview = preview,
                ),
            )
        })
    }
}

@Composable
private fun PlanOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = Film.scan))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Film.bone else Film.boneDim)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Film.scan, checkedThumbColor = Film.base))
    }
}
