package com.dpdpxray.app.voice.wake

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.theme.Film
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings section for the experimental, opt-in "Hey TraceVeil" wake word. Switching it on only reveals the controls;
 * the microphone starts only from the Start button, for the session length the user picks.
 */
@Composable
fun HeyTraceVeilSection(optedIn: Boolean, onOptInChange: (Boolean) -> Unit, showTitle: Boolean = true) {
    val context = LocalContext.current
    val models = remember(context) { WakeModelManager.get(context) }
    val prefsStore = remember(context) { WakePreferences.get(context) }
    val prefs by prefsStore.state.collectAsState()
    val model by models.status.collectAsState()
    val wake by WakeRuntime.state.collectAsState()
    val endsAt by WakeRuntime.endsAtWallMs.collectAsState()
    val lastDuration by WakeRuntime.duration.collectAsState()
    val events by WakeRuntime.events.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val charging = remember(wake) { WakeController.battery(context).charging }
    val duration = WakeDuration.effective(prefs.duration, charging)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        message = WakeController.start(context, duration)?.let(WakeStopReason::explain)
    }

    LaunchedEffect(optedIn) { if (optedIn) models.refresh() }
    val polling = model is WakeModelStatus.Downloading || model is WakeModelStatus.Verifying
    LaunchedEffect(polling) {
        while (polling) {
            delay(1_000)
            models.refresh()
        }
    }

    // Settings shows this section inside a titled card, so it can leave the heading to the card.
    if (showTitle) SectionTitle("Hey TraceVeil (experimental)")
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Hands-free wake word", style = MaterialTheme.typography.titleMedium)
            Text(WakeCopy.INTRO, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
        Switch(
            checked = optedIn,
            onCheckedChange = { on ->
                if (!on) WakeController.stop(WakeStopReason.OPTED_OUT)
                message = null
                onOptInChange(on)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = Film.scan, checkedThumbColor = Film.base),
        )
    }
    if (!optedIn) {
        Note("You can still say “Hey Google, open TraceVeil”, or long-press the TraceVeil icon for shortcuts. TraceVeil's own microphone stays off.")
        return
    }

    val modelColor = when (model) {
        is WakeModelStatus.Ready -> if ((model as WakeModelStatus.Ready).verified) Film.boneDim else Film.high
        is WakeModelStatus.Missing -> if ((model as WakeModelStatus.Missing).problem != null) Film.high else Film.boneDim
        else -> Film.boneDim
    }
    Note(model.describe(models.spec), modelColor)

    when (val m = model) {
        is WakeModelStatus.Missing -> {
            SecondaryAction("Download wake-word model (≈40 MB)", { message = models.startDownload() }, Modifier.fillMaxWidth().padding(top = 8.dp))
            Note(
                "One download from alphacephei.com (Vosk small English model, Apache 2.0) through the phone's download service. " +
                    "It is checked against pinned SHA-256 and MD5 values before use. No audio or audit data is sent. " +
                    "Developers can copy it with adb instead: see docs/HEY_TRACEVEIL.md.",
            )
            SecondaryAction("Check again", { models.refresh() }, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        is WakeModelStatus.Downloading -> SecondaryAction("Cancel download", { models.cancelDownload() }, Modifier.fillMaxWidth().padding(top = 8.dp))
        is WakeModelStatus.Ready -> {
            ChoiceGroup(
                "Listen for", WakeDuration.entries, selected = duration, label = { it.label },
                enabled = !wake.active, onSelect = prefsStore::setDuration,
            )
            Note(WakeCopy.DURATION_NOTE)
            ChoiceGroup("Wake sensitivity", WakeSensitivity.entries, selected = prefs.sensitivity, label = { it.label }, onSelect = prefsStore::setSensitivity)
            Note(WakeCopy.sensitivityNote(prefs.sensitivity))
            ReadyControls(
                wake = wake,
                endsAt = endsAt,
                startLabel = WakeCopy.startLabel(duration),
                stoppedFor = lastDuration ?: duration,
                onStart = {
                    message = null
                    val needed = WakeController.missingPermissions(context)
                    if (needed.isEmpty()) message = WakeController.start(context, duration)?.let(WakeStopReason::explain)
                    else permissions.launch(needed)
                },
                onStop = { WakeController.stop(WakeStopReason.USER) },
                onRemove = { models.deleteModel() },
                unverified = !m.verified,
            )
            RecentWakeEvents(events)
            BackgroundHint(context)
            Note("After “Yes?”, say for example: “Check Instagram”, “Check my phone”, “What did you find?”, “Open reports”, “What can you do?” or “Stop listening”.")
        }
        WakeModelStatus.Checking, WakeModelStatus.Verifying -> Unit
    }
    message?.let { Note(it, Film.high) }
    Note(
        "Experimental: accuracy has not been measured yet. It may miss the phrase or, rarely, react to similar speech. " +
            "While the voice copilot is open or a check runs, it lets go of the microphone and resumes afterwards. It can only be started from this screen.",
    )
}

@Composable
private fun <T> ChoiceGroup(title: String, options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
    ChipFlow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                label = { Text(label(option)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Film.scan.copy(alpha = 0.18f), selectedLabelColor = Film.bone),
            )
        }
    }
}

@Composable
private fun RecentWakeEvents(log: WakeEventLog) {
    Text("Recent wake events", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp).semantics { heading() })
    Note("${log.accepted} accepted · ${log.rejected} rejected", if (log.accepted + log.rejected > 0) Film.bone else Film.boneDim)
    val time = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    log.recent.forEach { e ->
        val conf = e.confidence?.let { " · confidence ${String.format(Locale.ROOT, "%.2f", it)}" }.orEmpty()
        Note("${time.format(Date(e.atWallMs))} · ${WakeEventLog.describe(e.reason)}$conf · ${e.sensitivity.label}", if (e.accepted) Film.scan else Film.boneDim)
    }
    Note(WakeCopy.EVENTS_NOTE)
}

@Composable
private fun BackgroundHint(context: Context) {
    val unrestricted = remember(context) {
        runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }
    if (unrestricted) return
    Note(WakeCopy.BACKGROUND_NOTE)
    SecondaryAction("TraceVeil battery settings", { openAppSettings(context) }, Modifier.fillMaxWidth().padding(top = 6.dp))
}

/** App info, where Android and OEM skins keep the per-app battery and background options. No permission is needed. */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun ReadyControls(
    wake: WakeState,
    endsAt: Long?,
    startLabel: String,
    stoppedFor: WakeDuration,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
    unverified: Boolean,
) {
    when (wake) {
        WakeState.Starting -> {
            SecondaryAction("Stop", onStop, Modifier.fillMaxWidth().padding(top = 8.dp))
            Note("Loading the offline recogniser…")
        }
        is WakeState.Listening, is WakeState.Responding, is WakeState.Paused -> {
            PrimaryAction("Stop listening", onStop, Modifier.fillMaxWidth().padding(top = 8.dp))
            val until = endsAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
            Note(WakeCopy.listeningNote(until, paused = wake is WakeState.Paused), Film.scan)
        }
        else -> {
            PrimaryAction(startLabel, onStart, Modifier.fillMaxWidth().padding(top = 8.dp))
            if (unverified) Note("This model was placed manually and its checksum was not verified by TraceVeil.", Film.high)
            (wake as? WakeState.Stopped)?.let { s ->
                Note(WakeStopReason.explain(s.reason, stoppedFor) + (s.detail?.let { " ($it)" } ?: ""))
            }
            SecondaryAction("Remove wake-word model", onRemove, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}
