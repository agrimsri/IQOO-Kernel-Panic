package com.dpdpxray.app.apkguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.GroupHeader
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.IconBadge
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.theme.Radii
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.graph
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Mono
import com.dpdpxray.core.apk.ApkEvidence
import com.dpdpxray.core.apk.ApkGuardReport
import com.dpdpxray.core.apk.ApkRiskRules
import com.dpdpxray.core.apk.ApkSeverity
import com.dpdpxray.core.apk.ApkSignal
import com.dpdpxray.core.apk.Bilingual
import com.dpdpxray.core.apk.InstallSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// --- hub ------------------------------------------------------------------------------------------------------------

@Composable
fun ApkGuardHubScreen(onBack: (() -> Unit)?, onOpenApp: (String) -> Unit, onOpenFile: (String) -> Unit) {
    val ctx = LocalContext.current
    val store = remember(ctx) { ctx.graph.settings }
    val settings by store.settings.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val apps by produceState<List<ApkInspector.ListedApp>?>(null, resumes) {
        value = withContext(Dispatchers.IO) { ApkInspector.listInstalled(ctx) }
    }
    val notificationsAllowed = remember(resumes) { notificationsAllowed(ctx) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpenFile(ApkFileChecks.start(ctx, uri))
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("APK Guard", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item(key = "intro") {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Glyph.APK, Film.scan, size = 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "Check an app's powers before you install or open it.", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Note("Reads permissions, special services, where an app came from and who signed it.")
                    Spacer(Modifier.height(10.dp))
                    ChipFlow {
                        Tag("Not an antivirus", Film.warn)
                        Tag("Reads only, never installs", Film.scan)
                        Tag("Stays on this phone", Film.clear)
                    }
                    LearnMore(Modifier.padding(top = 4.dp)) {
                        Note("APK Guard explains what an app can do from what Android shows about it: permissions, special services, where it came from and who signed it. It is not an antivirus and cannot say whether an app is harmful. Keep Google Play Protect on as well (Play Store › your profile › Play Protect).")
                    }
                    Spacer(Modifier.height(4.dp))
                    PrimaryAction("Check an APK file", { picker.launch(arrayOf(IncomingApk.APK_MIME, "application/octet-stream")) })
                    Spacer(Modifier.height(10.dp))
                    Note("Got an app file on WhatsApp or in Files? Tap it and choose TraceVeil, or share it to TraceVeil. TraceVeil only reads the file; it never installs it.")
                }
            }
            item(key = "alerts") {
                SectionCard(Modifier.padding(top = 16.dp), title = "New-install alerts", glyph = Glyph.BELL) {
                    Note("While TraceVeil is running, it shows a notification asking you to check each newly installed app before you open it.")
                    LearnMore {
                        Note("While TraceVeil is running, Android tells it when a new app is installed, and TraceVeil shows a notification asking you to check that app before you open it. Android keeps TraceVeil running in the background only while its consent watcher (accessibility service) is switched on; otherwise alerts appear only while TraceVeil is still open or recently used. TraceVeil never starts an audit, a VPN or anything else by itself.")
                    }
                    if (!notificationsAllowed) {
                        Callout("Notifications are off for TraceVeil, so new-install alerts cannot appear.", Modifier.padding(bottom = 8.dp))
                    }
                    Hairline()
                    SwitchRow(
                        "Speak APK Guard alerts",
                        "Reads new-install alerts aloud with an offline voice already installed on this phone. Nothing is sent off the phone. Without an offline voice the alert stays silent.",
                        settings.speakApkGuardAlerts,
                        { v -> store.update { it.copy(speakApkGuardAlerts = v) } },
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryAction("Alert notification settings", { openAlertSettings(ctx) }, Modifier.fillMaxWidth())
                }
            }
            item(key = "installed-header") {
                GroupHeader("Check an installed app")
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search by name or package") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Film.boneDim) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = Film.boneDim) }
                    },
                    shape = RoundedCornerShape(Radii.button),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Film.scan, unfocusedBorderColor = Film.line, cursorColor = Film.scan),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("Show system apps", "Apps that came with the phone.", showSystem, { showSystem = it })
                Note("Newest installs first. Apps without a launcher screen are listed too.")
                Spacer(Modifier.height(8.dp))
            }
            val list = apps
            if (list == null) item(key = "loading") { LoadingRow("Loading installed apps…") }
            else {
                val q = query.trim()
                val shown = list.filter { (showSystem || !it.isSystem) && (q.isEmpty() || it.label.contains(q, true) || it.packageName.contains(q, true)) }
                if (shown.isEmpty()) item(key = "none") { Note("No matching apps.") }
                items(shown, key = { it.packageName }) { app -> InstalledRow(app) { onOpenApp(app.packageName) } }
            }
        }
    }
}

/** A small spinner with a line of text, for screens that are reading something from the phone. */
@Composable
private fun LoadingRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Film.scan, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Note(text)
    }
}

@Composable
private fun InstalledRow(app: ApkInspector.ListedApp, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(Radii.small)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(Radii.small))) }
            ?: Spacer(Modifier.size(44.dp).clip(RoundedCornerShape(Radii.small)).background(Film.raised))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, fontFamily = Mono, fontSize = 12.sp, color = Film.boneDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    app.isSystem -> "Came with the phone"
                    ApkInspector.plausibleInstallTime(app.firstInstallTime) -> "Installed ${date(app.firstInstallTime)}"
                    else -> "Install date not reported (preloaded)"
                }, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!app.hasLauncher) Tag("No launcher screen", Film.warn)
            if (app.isSystem) Tag("System", Film.boneDim)
        }
        Spacer(Modifier.width(6.dp))
        GlyphIcon(Glyph.CHEVRON_RIGHT, Film.boneDim, size = 18.dp)
    }
}

// --- result -------------------------------------------------------------------------------------------------------

sealed interface ApkGuardSubject {
    data class Installed(val packageName: String) : ApkGuardSubject
    data class File(val key: String) : ApkGuardSubject
}

private sealed interface ResultState {
    data object Loading : ResultState
    data class Failed(val reason: String) : ResultState
    data class Ready(val app: InspectedApp, val report: ApkGuardReport) : ResultState
}

@Composable
fun ApkGuardResultScreen(
    subject: ApkGuardSubject,
    onBack: () -> Unit,
    onAudit: (String) -> Unit,
    onOpenInstalled: (String) -> Unit,
    onQuarantine: ((String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    // An installed app is read again on return from Android settings, so a revoked permission shows at once.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (subject is ApkGuardSubject.Installed) resumes++ }
    val state by produceState<ResultState>(ResultState.Loading, subject, resumes) {
        value = withContext(Dispatchers.IO) { load(ctx, subject) }
    }
    var hindi by rememberSaveable { mutableStateOf(Locale.getDefault().language == "hi") }

    Column(Modifier.fillMaxSize()) {
        TopBar("APK Guard", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            when (val s = state) {
                ResultState.Loading -> LoadingRow(if (subject is ApkGuardSubject.File) "Reading the APK file… Nothing is installed." else "Reading what Android shows about this app…")
                is ResultState.Failed -> {
                    Callout(s.reason)
                    Spacer(Modifier.height(12.dp))
                    SecondaryAction("Back", onBack, Modifier.fillMaxWidth())
                }
                is ResultState.Ready -> ReadyResult(s.app, s.report, hindi, { hindi = it }, onAudit, onOpenInstalled, onQuarantine)
            }
        }
    }
}

// Runs on Dispatchers.IO (see ApkGuardResultScreen), so the model's one-time load never blocks the main thread.
private suspend fun load(ctx: Context, subject: ApkGuardSubject): ResultState = when (subject) {
    is ApkGuardSubject.Installed -> ApkInspector.readInstalled(ctx, subject.packageName)
        ?.let { ResultState.Ready(it, ApkGuardModel.withModelCard(ApkRiskRules.evaluate(it.facts))) }
        ?: ResultState.Failed("This app is not installed, or Android did not let TraceVeil read it.")
    is ApkGuardSubject.File -> when (val r = ApkFileChecks.await(subject.key)) {
        null -> ResultState.Failed("This file check is no longer in memory, for example because TraceVeil was closed. Open the APK file again from WhatsApp or Files.")
        is ArchiveRead.Failed -> ResultState.Failed(r.reason)
        is ArchiveRead.Ok -> ResultState.Ready(r.app, ApkGuardModel.withModelCard(ApkRiskRules.evaluate(r.app.facts)))
    }
}

@Composable
private fun ReadyResult(
    app: InspectedApp,
    report: ApkGuardReport,
    hindi: Boolean,
    onLanguage: (Boolean) -> Unit,
    onAudit: (String) -> Unit,
    onOpenInstalled: (String) -> Unit,
    onQuarantine: ((String) -> Unit)?,
) {
    val ctx = LocalContext.current
    val facts = app.facts
    val isFile = facts.origin.source == InstallSource.NOT_INSTALLED

    IdentityCard(app)
    Spacer(Modifier.height(16.dp))
    SectionCard {
        Text(
            report.summary.headline.pick(hindi),
            style = MaterialTheme.typography.titleLarge,
            color = if (report.summary.reviewCarefully > 0) Film.high else Film.bone,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Note(report.summary.disclaimer.pick(hindi))
        val counts = ApkSeverity.entries.mapNotNull { sev -> report.signals.count { it.severity == sev }.takeIf { it > 0 }?.let { sev to it } }
        if (counts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ChipFlow { counts.forEach { (sev, n) -> Tag("$n · ${sev.label.pick(hindi)}", severityColor(sev)) } }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageChip("English", !hindi) { onLanguage(false) }
            LanguageChip("हिंदी", hindi) { onLanguage(true) }
        }
    }

    ApkSeverity.entries.forEach { severity ->
        val group = report.signals.filter { it.severity == severity }
        if (group.isNotEmpty()) {
            SectionTitle("${severity.label.pick(hindi)} · ${group.size}")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { group.forEach { SignalCard(it, hindi) } }
        }
    }

    SectionTitle(if (hindi) "आगे क्या करें" else "What next")
    if (!isFile) {
        val launchable = facts.hasLauncherIcon == true
        if (facts.packageName == ctx.packageName) {
            // The self-check from "Why you can trust TraceVeil": the audit picker leaves TraceVeil out.
            Note("This is TraceVeil itself. It cannot audit its own traffic, because its VPN carries every audit; its own network use is shown under More › Why you can trust TraceVeil.")
        } else {
            // Opens the audit picker already searched for this app; the audit is set up and started there.
            PrimaryAction("Run a privacy audit of this app", { onAudit(facts.packageName) }, enabled = launchable)
            if (!launchable) Note("This app has no launcher screen, so TraceVeil cannot start it for an audit.")
        }
        if (onQuarantine != null && ApkGuardRoutes.quarantineOffered(installed = true, packageName = facts.packageName, ownPackage = ctx.packageName)) {
            // Opens the quarantine explainer only; nothing is blocked until Start is tapped there.
            Spacer(Modifier.height(10.dp))
            SecondaryAction("Run in quarantine (network blocked)", { onQuarantine(facts.packageName) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Note("Cuts only this app's internet and records what it tries to reach. Not a malware verdict.")
        }
        Spacer(Modifier.height(10.dp))
        SecondaryAction("Open Android app settings", { DeviceChecks.openAppInfo(ctx, facts.packageName) }, Modifier.fillMaxWidth())
    } else {
        facts.installedTwin?.let {
            SecondaryAction("Check the installed \"${it.label}\"", { onOpenInstalled(facts.packageName) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        CollapsibleCard(
            if (hindi) "अगर आपको अब भी इस फ़ाइल पर भरोसा है" else "If you still trust this file",
            summary = if (hindi) "TraceVeil ऐप इंस्टॉल नहीं करता। आगे कैसे बढ़ें, यह देखें।" else "TraceVeil does not install apps. See how to continue, if you choose to.",
            glyph = Glyph.INFO,
        ) {
            Note(CONTINUE_WITH_INSTALLER.pick(hindi), Film.bone)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.LOCK, Film.clear, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Note("Nothing about this check leaves the phone.", Film.clear)
    }
    LearnMore(label = "Rules and sources") {
        Note("APK Guard rules v${ApkRiskRules.VERSION}. Sources: Google Play Protect developer guidance; CERT-In advisory CICA-2026-3492; Cyber Swachhta Kendra SpyMax alert; McAfee Labs SpyLoan research. Nothing about this check leaves the phone.")
    }
}

private val CONTINUE_WITH_INSTALLER = Bilingual(
    "TraceVeil does not install apps. If, after this check, you still trust the file: go back to the app where you found it (for example WhatsApp or Files), tap the file again and choose Android's package installer. Android may ask you to allow installs from that app; you can switch that off again in Settings › Apps › Special app access › Install unknown apps. Better still, get the app from Google Play or the official website.",
    "TraceVeil ऐप इंस्टॉल नहीं करता। अगर इस जाँच के बाद भी आपको फ़ाइल पर भरोसा है: उस ऐप में वापस जाएँ जहाँ फ़ाइल मिली थी (जैसे WhatsApp या Files), फ़ाइल पर फिर टैप करें और Android का पैकेज इंस्टॉलर चुनें। Android उस ऐप से इंस्टॉल की अनुमति माँग सकता है; बाद में इसे सेटिंग्स › ऐप्स › ख़ास ऐप ऐक्सेस › अनजान ऐप इंस्टॉल करें में फिर से बंद कर सकते हैं। सबसे अच्छा है कि ऐप Google Play या आधिकारिक वेबसाइट से लें।",
)

@Composable
private fun IdentityCard(app: InspectedApp) {
    val facts = app.facts
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = remember(app) { app.icon?.asImageBitmap() }
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(Radii.small)))
            else IconBadge(Glyph.APK, Film.scan, size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(facts.label ?: "No app name", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(facts.packageName, fontFamily = Mono, fontSize = 12.5.sp, color = Film.boneDim)
            }
        }
        Spacer(Modifier.height(12.dp))
        Hairline()
        Spacer(Modifier.height(8.dp))
        Field("Version", listOfNotNull(facts.versionName, facts.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "Not reported" })
        Field("Installed from", ApkFactsMapper.originLabel(facts.origin))
        app.initiatingPackage?.let { Field("Install started by", it) }
        if (!app.isSystemApp) app.firstInstallTime?.let { Field("First installed", date(it)) }
        LearnMore(label = "Technical details", openLabel = "Hide technical details") {
            Field("Built for Android API", facts.targetSdk?.toString() ?: "Not reported")
            if (app.fileSizeBytes != null) Field("File", listOfNotNull(app.fileName, ApkFactsMapper.size(app.fileSizeBytes)).joinToString(" · "))
            Fingerprints("Signing certificate SHA-256", facts.certSha256)
            app.fileSha256?.let { Fingerprints("File SHA-256", listOf(it)) }
        }
    }
}

@Composable
private fun Field(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.width(132.dp).padding(top = 2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Fingerprints(name: String, hexes: List<String>) {
    val ctx = LocalContext.current
    if (hexes.isEmpty()) return Field(name, "Not readable (not established)")
    hexes.forEach { hex ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.width(132.dp))
            Text(ApkFactsMapper.shortFingerprint(hex), fontFamily = Mono, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { copy(ctx, name, ApkFactsMapper.colonHex(hex)) }) { Text("Copy", color = Film.scan) }
        }
    }
}

@Composable
private fun SignalCard(s: ApkSignal, hindi: Boolean) {
    RuleRow(severityColor(s.severity)) {
        ChipFlow {
            Tag(s.severity.label.pick(hindi), severityColor(s.severity))
            Tag(if (hindi) s.evidence.label.hi else s.evidence.label.en.uppercase(Locale.ROOT), evidenceColor(s.evidence))
        }
        Spacer(Modifier.height(10.dp))
        Text(s.title.pick(hindi), style = MaterialTheme.typography.titleMedium)
        s.state?.let { Text(it.pick(hindi), style = MaterialTheme.typography.labelMedium, color = Film.bone, modifier = Modifier.padding(top = 2.dp)) }
        Text(s.detail.pick(hindi), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Text(if (hindi) "आप क्या कर सकते हैं" else "What you can do", style = MaterialTheme.typography.labelLarge, color = Film.scan)
        Text(s.advice.pick(hindi), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        if (s.proof.isNotEmpty() || s.basis != null) {
            LearnMore(Modifier.padding(top = 4.dp), label = if (hindi) "सबूत देखें" else "Show proof", openLabel = if (hindi) "सबूत छिपाएँ" else "Hide proof") {
                if (s.proof.isNotEmpty()) Text(s.proof.joinToString("\n"), fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, color = Film.boneDim)
                s.basis?.let { Text("Why this is a signal: $it", style = MaterialTheme.typography.labelMedium, color = Film.boneDim) }
            }
        }
    }
}

@Composable
private fun LanguageChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) Film.scan else Film.base)
            .border(1.dp, if (selected) Film.scan else Film.line, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Film.base else Film.bone)
    }
}

// --- helpers ------------------------------------------------------------------------------------------------------

private fun Bilingual.pick(hindi: Boolean) = if (hindi) hi else en

private fun severityColor(s: ApkSeverity): Color = when (s) {
    ApkSeverity.REVIEW_CAREFULLY -> Film.high
    ApkSeverity.WORTH_KNOWING -> Film.warn
    ApkSeverity.INFO -> Film.boneDim
}

private fun evidenceColor(e: ApkEvidence): Color = when (e) {
    ApkEvidence.OBSERVED -> Film.scan
    ApkEvidence.INFERRED -> Film.warn
    ApkEvidence.NOT_ESTABLISHED -> Film.boneDim
}

private fun date(epochMs: Long): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun copy(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
}

private fun notificationsAllowed(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    return ctx.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() != false
}

private fun openAlertSettings(ctx: Context) {
    ApkInstallWatcher.ensureChannel(ctx)
    val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, ApkInstallWatcher.CHANNEL)
    val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    runCatching { ctx.startActivity(channel) }.recoverCatching { ctx.startActivity(app) }
}
