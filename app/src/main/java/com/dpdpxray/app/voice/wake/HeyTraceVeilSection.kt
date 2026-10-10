package com.dpdpxray.app.voice.wake

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.theme.Film
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Settings section for the experimental, opt-in "Hey TraceVeil" wake word. Switching it on only reveals the controls;
 * the microphone starts only from the Start button, for at most 15 minutes at a time.
 */
@Composable
fun HeyTraceVeilSection(optedIn: Boolean, onOptInChange: (Boolean) -> Unit, showTitle: Boolean = true) {
    val context = LocalContext.current
    val models = remember(context) { WakeModelManager.get(context) }
    val model by models.status.collectAsState()
    val wake by WakeRuntime.state.collectAsState()
    val endsAt by WakeRuntime.endsAtWallMs.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        message = WakeController.start(context)?.let(WakeStopReason::explain)
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
            Text(
                "Off by default. When you press Start, TraceVeil listens on this phone only for “Hey TraceVeil”, " +
                    "for up to 15 minutes, with a notification the whole time. Google stays your default assistant.",
                style = MaterialTheme.typography.bodyMedium, color = Film.boneDim,
            )
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
        is WakeModelStatus.Ready -> ReadyControls(
            wake = wake,
            endsAt = endsAt,
            onStart = {
                message = null
                val needed = WakeController.missingPermissions(context)
                if (needed.isEmpty()) message = WakeController.start(context)?.let(WakeStopReason::explain)
                else permissions.launch(needed)
            },
            onStop = { WakeController.stop(WakeStopReason.USER) },
            onRemove = { models.deleteModel() },
            unverified = !m.verified,
        )
        WakeModelStatus.Checking, WakeModelStatus.Verifying -> Unit
    }
    message?.let { Note(it, Film.high) }
    Note(
        "Experimental: accuracy has not been measured yet. It may miss the phrase or, rarely, react to similar speech. " +
            "Opening the voice copilot stops it, so only one recogniser uses the microphone. It can only be started from this screen; " +
            "some phones may stop it earlier to save power, which has not been tested on our phones yet.",
    )
}

@Composable
private fun ReadyControls(
    wake: WakeState,
    endsAt: Long?,
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
        is WakeState.Listening, is WakeState.Responding -> {
            PrimaryAction("Stop listening", onStop, Modifier.fillMaxWidth().padding(top = 8.dp))
            val until = endsAt?.let { " until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" }.orEmpty()
            Note("Listening for “Hey TraceVeil”$until. Audio stays on this phone and is never saved.", Film.scan)
        }
        else -> {
            PrimaryAction("Start listening (15 minutes)", onStart, Modifier.fillMaxWidth().padding(top = 8.dp))
            if (unverified) Note("This model was placed manually and its checksum was not verified by TraceVeil.", Film.high)
            (wake as? WakeState.Stopped)?.let { s ->
                Note(WakeStopReason.explain(s.reason) + (s.detail?.let { " ($it)" } ?: ""))
            }
            SecondaryAction("Remove wake-word model", onRemove, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}
