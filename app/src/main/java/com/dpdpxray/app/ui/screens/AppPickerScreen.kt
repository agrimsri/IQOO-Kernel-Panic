package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.control.AuditableApp
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.HostText
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.guide.EmptyState
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.GuideBanner
import com.dpdpxray.app.ui.guide.GuideFlow
import com.dpdpxray.app.ui.guide.OutlineAction
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.core.model.AuditPlan

private val demoPackages = setOf("com.leakyshop.demo", "com.leakyshop.demo.fixed")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    vm: XrayViewModel,
    onBack: () -> Unit,
    onStarted: () -> Unit,
    initialQuery: String = "",
    onQuarantine: (String) -> Unit = {},
) {
    val apps by vm.apps.collectAsState()
    val pf by vm.preflight.collectAsState()
    val settings by vm.settings.collectAsState()
    var query by remember { mutableStateOf(initialQuery) }
    var selected by remember { mutableStateOf<AuditableApp?>(null) }
    LaunchedEffect(Unit) { vm.refreshPreflight(); vm.loadApps() }

    val demos = apps.filter { it.packageName in demoPackages }
    Column(Modifier.fillMaxSize()) {
        TopBar("Choose an app", onBack)
        GuideBanner(GuideFlow.picker(demoInstalled = demos.isNotEmpty()), Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Film.boneDim) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = Film.boneDim) }
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Film.scan, unfocusedBorderColor = Film.line, cursorColor = Film.scan),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        val q = query.trim()
        val shown = apps.filter { q.isBlank() || it.label.contains(q, true) || it.packageName.contains(q, true) }
        val showDemoSection = q.isBlank() && demos.isNotEmpty()
        when {
            apps.isEmpty() -> Box(Modifier.padding(20.dp)) { Note("Loading installed apps…", Film.boneDim) }
            shown.isEmpty() -> EmptyState(
                "No apps match \"$q\"",
                if (q.contains("leaky", true)) "The LeakyShop demo isn't installed on this phone. Install its APK, or clear the search to pick another app."
                else "Check the spelling, or clear the search to see every app.",
                action = "Clear search" to { query = "" },
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
            if (showDemoSection) {
                item(key = "demo-header") { ListHeader("Demo apps, made for testing") }
                items(demos, key = { "demo-" + it.packageName }) { app -> AppRow(app, demo = true) { selected = app } }
                item(key = "all-header") { ListHeader("All apps") }
            }
            items(if (showDemoSection) shown.filter { it.packageName !in demoPackages } else shown, key = { it.packageName }) { app ->
                AppRow(app, demo = app.packageName in demoPackages) { selected = app }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
        }
    }

    selected?.let { app ->
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = Film.raised) {
            AuditSetup(app, shizukuReady = pf.shizukuGranted, preferPreview = settings.fastDemo, onQuarantine = { selected = null; onQuarantine(app.packageName) }) { config ->
                selected = null
                if (vm.startAudit(config)) onStarted()
            }
        }
    }
}

@Composable
private fun ListHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Film.boneDim, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun AppRow(app: AuditableApp, demo: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp = remember(app.packageName) { runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
        if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        else Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Film.raised))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HostText(app.packageName, Film.boneDim)
        }
        if (demo) Tag("Demo", Brand.peach)
    }
}

@Composable
private fun AuditSetup(app: AuditableApp, shizukuReady: Boolean, preferPreview: Boolean, onQuarantine: () -> Unit = {}, onStart: (AuditConfig) -> Unit) {
    val controlledDemo = app.packageName == "com.leakyshop.demo" || app.packageName == "com.leakyshop.demo.fixed"
    var allowReset by remember { mutableStateOf(controlledDemo) }
    val freshReset = allowReset && shizukuReady
    var full by remember { mutableStateOf(shizukuReady && controlledDemo) }
    var preview by remember { mutableStateOf(preferPreview && !(shizukuReady && controlledDemo)) }
    var agent by remember { mutableStateOf(controlledDemo) }
    var child by remember { mutableStateOf(false) }
    var gms by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Check ${app.label}", style = MaterialTheme.typography.headlineMedium)
        GuideBanner(GuideFlow.pickerSetup(fullAvailable = freshReset), Modifier.padding(vertical = 6.dp))
        if (shizukuReady) Toggle("Allow clearing this test app's local data", "Developer testing only. Removes its saved state, accounts and settings before each run. Leave off for personal apps.", allowReset) {
            allowReset = it
            if (!it) full = false
        }
        Text("How to check", style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        PlanOption(
            "Full audit · normal timing",
            "Three fresh-state runs: Silent, Reject, Accept. Observes Silent for 25 seconds and each choice for 15 seconds; launch, consent and AI review add time.",
            full, enabled = freshReset,
        ) { full = true; preview = false }
        if (!freshReset) Callout("Full audits need Shizuku and explicit approval to reset a test app between runs.")
        PlanOption("Quick audit · normal timing", "One Accept-plan run with 15 seconds of observation. No Silent or Reject comparison.", !full && !preview, enabled = true) { full = false; preview = false }
        PlanOption("Fast Preview · limited evidence", "One Accept-plan run with 8 seconds of observation and a shorter consent-screen wait. Delayed traffic may be missed; not a Full audit.", preview, enabled = true) { full = false; preview = true }
        if (!freshReset) Callout("Quick keeps the app's data. An already-opened app may keep its consent choice and not repeat launch-time DNS lookups. A missing dialog or quiet run is not a privacy pass.")
        else Callout("Starting an audit clears this selected app's local data before each plan. Use a test app or a backed-up development build; do not use a personal app with data you need.")

        Text("Options", style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
        Toggle("Let the agent tap Accept and Reject", "Off: you tap them yourself when asked.", agent) { agent = it }
        Toggle("This app is used by children", "Adds the DPDP section 9 check: no tracking or targeted ads for children.", child) { child = it }
        Toggle("Include Google Play services lookups", "Some SDKs upload through Play services. Those lookups may include other apps' traffic.", gms) { gms = it }

        Spacer(Modifier.height(12.dp))
        GradientAction("Start check", {
            onStart(
                AuditConfig(
                    targetPackage = app.packageName, targetLabel = app.label,
                    plans = if (full && freshReset) listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT) else listOf(AuditPlan.ACCEPT),
                    childFacing = child, includeGms = gms, autoDrive = agent, resetAppData = freshReset, fastPreview = preview,
                ),
            )
        })
        // Quarantine run (separate feature): opens its own explainer; nothing starts until Start is tapped there.
        Spacer(Modifier.height(4.dp))
        OutlineAction("Run in quarantine (network blocked)", onQuarantine, glyph = Glyph.LOCK)
        Note("Cuts only this app's internet and records what it tries to reach and which permissions it asks for. Not a malware verdict.")
    }
}

/** A plan as a selectable card: the chosen one gets a scan-coloured outline and a light wash. */
@Composable
private fun PlanOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) FilmExtra.scanWash else FilmExtra.raisedHigh.copy(alpha = 0.5f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Film.scan else FilmExtra.hairline, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = Film.scan), modifier = Modifier.padding(4.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Film.bone else Film.boneDim)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) = SwitchRow(title, detail, value, onChange)
