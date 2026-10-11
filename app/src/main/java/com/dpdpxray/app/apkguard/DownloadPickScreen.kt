package com.dpdpxray.app.apkguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.system.PrimaryButton
import com.dpdpxray.app.ui.theme.Film

/** Copy for [DownloadPickScreen]. */
internal object DownloadPickCopy {
    val title = Bi("Check the new APK", "नई APK की जाँच करें")
    val body = Bi(
        "Android does not let TraceVeil open files that other apps downloaded. Pick the APK in Downloads and TraceVeil checks it; nothing is installed unless you tap Install anyway and confirm.",
        "Android TraceVeil को दूसरे ऐप्स की डाउनलोड की गई फ़ाइलें खोलने नहीं देता। Downloads में APK चुनें और TraceVeil उसकी जाँच करेगा; जब तक आप Install anyway दबाकर पुष्टि नहीं करते, कुछ इंस्टॉल नहीं होता।",
    )
    val choose = Bi("Choose the APK in Downloads", "Downloads में APK चुनें")
}

/**
 * Opened by a "New APK downloaded" alert for a file TraceVeil cannot open itself. Android's file picker opens once,
 * in Downloads and filtered to app files, because the person tapped the alert to check that file; the picked file goes
 * straight to the APK Guard check. Nothing else happens by itself.
 */
@Composable
fun DownloadPickScreen(onBack: () -> Unit, onOpenFile: (String) -> Unit) {
    val ctx = LocalContext.current
    val lang = guideLang()
    val picker = rememberLauncherForActivityResult(PickInDownloads()) { uri ->
        if (uri != null) onOpenFile(ApkFileChecks.start(ctx, uri))
    }
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!opened) {
            opened = true
            runCatching { picker.launch(PICK_TYPES) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("APK Guard", onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(DownloadPickCopy.title.pick(lang), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(DownloadPickCopy.body.pick(lang), style = MaterialTheme.typography.bodyLarge, color = Film.boneDim, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            PrimaryButton(DownloadPickCopy.choose.pick(lang), { runCatching { picker.launch(PICK_TYPES) } })
        }
    }
}

private val PICK_TYPES = arrayOf(IncomingApk.APK_MIME, "application/octet-stream")

/** Android's document picker, starting in Downloads (a hint the picker may ignore). */
private class PickInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS_ROOT)

    private companion object {
        val DOWNLOADS_ROOT: Uri = DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", "downloads")
    }
}
