package com.dpdpxray.app.ui.modes

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.theme.Film

/** Asked before clearing the data of any app other than TraceVeil's demo apps (see [CheckSheet.resetNeedsConfirmation]). */
@Composable
fun ResetConfirmDialog(app: String, lang: GuideLang, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val t = CheckSheet.resetConfirmText(app)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.title.pick(lang)) },
        text = { Text(t.body.pick(lang)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(t.confirm.pick(lang)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t.cancel.pick(lang)) } },
        containerColor = Film.raised,
    )
}
