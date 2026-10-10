package com.dpdpxray.app.automation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.CodeBlock
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.HostText
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.theme.Film

/**
 * The Developer automation card in Settings: folded by default, with a one-line summary that says whether the runner
 * is allowed and that a laptop on USB can start checks and copy their results (the only way data leaves through it).
 */
@Composable
fun DeveloperAutomationCard(vm: XrayViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { AutomationStore.get(ctx) }
    val s by store.settings.collectAsState()
    CollapsibleCard(
        "Developer automation", modifier,
        summary = if (s.enabled) "On · a laptop on USB can start checks and copy their results" else "Off · lets a laptop on USB start checks and copy their results",
        summaryColor = if (s.enabled) Film.scan else Film.boneDim, glyph = Glyph.SETTINGS,
    ) {
        DeveloperAutomationSection(vm)
    }
}

/**
 * Settings > Developer automation: the switch for TraceVeil Runner, its pairing token and the test apps the runner may
 * reset. Everything is off or empty until the person sets it; nothing here grants a permission or changes another app.
 */
@Composable
fun DeveloperAutomationSection(vm: XrayViewModel) {
    val ctx = LocalContext.current
    val store = remember { AutomationStore.get(ctx) }
    val s by store.settings.collectAsState()
    val preflight by vm.preflight.collectAsState()
    val apps by vm.apps.collectAsState()
    var confirmRegenerate by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    SwitchRow(
        "Allow TraceVeil Runner",
        "Lets a laptop or CI runner connected over USB (adb) start a check on this phone with traceveil-ci run. Off by default.",
        s.enabled, store::setEnabled,
    )
    if (!s.enabled) {
        Note("Off: run requests from a laptop are refused.")
        return
    }

    Hairline(Modifier.padding(vertical = 8.dp))
    Text("Pairing token", style = MaterialTheme.typography.titleSmall, color = Film.bone)
    s.token?.let { CodeBlock(it, Modifier.padding(vertical = 6.dp)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryAction("Copy", { s.token?.let { copyToken(ctx, it) } }, Modifier.weight(1f))
        SecondaryAction("Regenerate", { confirmRegenerate = true }, Modifier.weight(1f))
    }
    FoldText("Give it to traceveil-ci as TRACEVEIL_TOKEN (or --token). Keep it like a password. Regenerate makes the old token stop working at once.")

    Hairline(Modifier.padding(vertical = 8.dp))
    Text("Test apps the runner may reset", style = MaterialTheme.typography.titleSmall, color = Film.bone)
    // A safety caveat: it stays fully visible.
    Callout("A full run clears the app's local data before each of its three plans. Add only your own test or development builds, never a personal app.", Modifier.padding(vertical = 6.dp))
    if (s.resetAllowlist.isEmpty()) Note("None yet: full runs are refused until you add the app under test.", Film.warn)
    s.resetAllowlist.sorted().forEach { pkg ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                apps.firstOrNull { it.packageName == pkg }?.let { Text(it.label, style = MaterialTheme.typography.bodyLarge) }
                HostText(pkg, Film.boneDim)
            }
            TextButton(onClick = { store.disallow(pkg) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove", color = Film.leak) }
        }
    }
    SecondaryAction("Add a test app", { picking = true }, Modifier.fillMaxWidth().padding(top = 4.dp))

    Hairline(Modifier.padding(vertical = 8.dp))
    Text("Set up by you on this phone", style = MaterialTheme.typography.titleSmall, color = Film.bone)
    ReadyLine(preflight.vpnReady, "VPN allowed", "Allow the VPN once from Home")
    ReadyLine(preflight.accessibilityOn, "Consent watcher on", "Switch on the TraceVeil consent watcher in Accessibility")
    ReadyLine(preflight.shizukuGranted, "Shizuku ready (full runs only)", "Full runs need Shizuku running and allowed")
    FoldText(
        "A runner check is the same check as one started here: it shows the live screen, uses the VPN and consent watcher you allowed, and the agent taps Accept and Reject. Results stay on this phone; the laptop copies them over USB.",
        Modifier.padding(top = 4.dp),
    )

    if (confirmRegenerate) AlertDialog(
        onDismissRequest = { confirmRegenerate = false },
        title = { Text("Regenerate the pairing token?") },
        text = { Text("The old token stops working at once. Update TRACEVEIL_TOKEN on the laptop or CI runner afterwards.") },
        confirmButton = { TextButton(onClick = { store.regenerateToken(); confirmRegenerate = false }) { Text("Regenerate") } },
        dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") } },
        containerColor = Film.raised,
    )

    if (picking) {
        LaunchedEffect(Unit) { if (apps.isEmpty()) vm.loadApps() }
        var query by remember { mutableStateOf("") }
        val q = query.trim()
        val shown = apps.filter { it.packageName !in s.resetAllowlist && (q.isEmpty() || it.label.contains(q, true) || it.packageName.contains(q, true)) }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Add a test app") },
            text = {
                Column {
                    Note("The runner may clear this app's local data before each plan. Pick only your own test build.")
                    OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search apps") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    if (apps.isEmpty()) Note("Loading installed apps…")
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(shown, key = { it.packageName }) { app ->
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable(role = Role.Button) { store.allow(app.packageName); picking = false }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                HostText(app.packageName, Film.boneDim)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
            containerColor = Film.raised,
        )
    }
}

/** One set-up item: a check glyph in green when done, a warning glyph and what to do when not. */
@Composable
private fun ReadyLine(ok: Boolean, done: String, todo: String) {
    val color = if (ok) Film.clear else Film.warn
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        GlyphIcon(if (ok) Glyph.CHECK else Glyph.WARN, color, Modifier.padding(top = 2.dp), size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Note(if (ok) done else todo, color)
    }
}

/** Copies the token, marked sensitive so Android 13+ hides it from the clipboard preview. */
private fun copyToken(ctx: Context, token: String) {
    val clip = ClipData.newPlainText("TraceVeil pairing token", token)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    Toast.makeText(ctx, "Pairing token copied", Toast.LENGTH_SHORT).show()
}
