package com.dpdpxray.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.data.CheckBackups
import com.dpdpxray.app.ui.BackupUi
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.BreathingDot
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.export.AuditBackups
import com.dpdpxray.core.export.AuditBundles

/**
 * "Back up my checks" / "Restore from a backup": one file the person creates or picks with Android's own file picker.
 * TraceVeil never uploads it; Android cloud backup stays off. Shown on the Reports tab and in Settings.
 */
@Composable
internal fun BackupRestoreCard(vm: XrayViewModel, modifier: Modifier = Modifier) {
    val audits by vm.audits.collectAsState()
    val state by vm.backup.collectAsState()
    val working = state is BackupUi.Working
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AuditBackups.MIME)) { uri ->
        if (uri != null) vm.backupTo(uri)
    }
    // Some file managers label .json files as plain text or a generic binary, so those are offered too; the file is checked.
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreFrom(uri)
    }
    SectionCard(modifier, title = "Back up or restore your checks", glyph = Glyph.RESET) {
        FoldText(
            "Keep every saved check in one file, and bring them back if TraceVeil's data is cleared, the app is reinstalled or " +
                "you move to a new phone. " + CheckBackups.SCOPE_LINE + " Restoring adds only checks that are not here yet.",
        )
        Spacer(Modifier.height(10.dp))
        SecondaryAction(
            if (audits.isEmpty()) "Back up my checks (none saved yet)" else "Back up my checks (${audits.size})",
            { create.launch(AuditBackups.fileName(AuditBundles.now())) },
            Modifier.fillMaxWidth(), enabled = audits.isNotEmpty() && !working,
        )
        Spacer(Modifier.height(10.dp))
        SecondaryAction(
            "Restore from a backup",
            { open.launch(arrayOf(AuditBackups.MIME, "application/octet-stream", "text/plain")) },
            Modifier.fillMaxWidth(), enabled = !working,
        )
        KeyCaveat(CheckBackups.PRIVACY_LINE, Modifier.padding(top = 8.dp), color = Film.clear)
        when (val s = state) {
            BackupUi.Idle -> Unit
            is BackupUi.Working -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BreathingDot(Film.scan)
                Spacer(Modifier.width(10.dp))
                Note(s.message, Film.scan)
            }
            is BackupUi.Done -> Note(s.message, Film.bone)
            is BackupUi.Failed -> Callout(s.message, Modifier.padding(top = 6.dp))
        }
    }
}
