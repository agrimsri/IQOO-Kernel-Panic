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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.dpdpxray.app.ui.components.CodeBlock
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.HostText
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.theme.Film

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
    Note("Give it to traceveil-ci as TRACEVEIL_TOKEN (or --token). Keep it like a password. Regenerate makes the old token stop working at once.")

    Hairline(Modifier.padding(vertical = 8.dp))
    Text("Test apps the runner may reset", style = MaterialTheme.typography.titleSmall, color = Film.bone)
    Note("A full run clears the app's local data before each of its three plans. Add only your own test or development builds, never a personal app.")
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
    Note("A runner check is the same check as one started here: it shows the live screen, uses the VPN and consent watcher you allowed, and the agent taps Accept and Reject. Results stay on this phone; the laptop copies them over USB.")

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

@Composable
private fun ReadyLine(ok: Boolean, done: String, todo: String) {
    Note(if (ok) "✓ $done" else "• $todo", if (ok) Film.clear else Film.warn)
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
