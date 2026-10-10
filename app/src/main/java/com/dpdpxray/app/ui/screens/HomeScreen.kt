package com.dpdpxray.app.ui.screens

import android.database.ContentObserver
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.apkguard.ApkFileChecks
import com.dpdpxray.app.apkguard.IncomingApk
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.RecentChecks
import com.dpdpxray.app.ui.guide.SetupItem
import com.dpdpxray.app.ui.guide.SetupKind
import com.dpdpxray.app.ui.guide.SetupNeed
import com.dpdpxray.app.ui.guide.SetupSteps
import com.dpdpxray.app.ui.guide.SpeakStep
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.modes.EverydayCopy
import com.dpdpxray.app.ui.system.Group
import com.dpdpxray.app.ui.system.GroupDivider
import com.dpdpxray.app.ui.system.LangVoiceButton
import com.dpdpxray.app.ui.system.ListRow
import com.dpdpxray.app.ui.system.PrimaryButton
import com.dpdpxray.app.ui.system.TextAction
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.bandColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every word that is new on the redesigned Home, in both guidance languages. Older words come from [EverydayCopy]. */
object HomeCopy {
    val heroTitle = Bi("Does an app respect your choice?", "क्या कोई ऐप आपकी पसंद का सम्मान करता है?")
    val heroBody = Bi(
        "Check one app while you accept or refuse its consent popup.",
        "किसी एक ऐप को जाँचें, जब आप उसके कंसेंट पॉपअप को स्वीकार या अस्वीकार करते हैं।",
    )
    val checkApp = Bi("Check an app", "ऐप जाँचें")
    fun stepsToFinish(n: Int) = if (n == 1) Bi("1 step to finish setup", "सेटअप पूरा करने के लिए 1 कदम")
    else Bi("$n steps to finish setup", "सेटअप पूरा करने के लिए $n कदम")
    val setupTitle = Bi("Setup", "सेटअप")
    val toDo = Bi("To do", "करने के लिए")
    val allSet = Bi("All set", "सब तैयार")
    val optional = Bi("Optional", "वैकल्पिक")
    val moreWays = Bi("More ways to check", "जाँचने के और तरीके")
    val fileTitle = Bi("An app file (APK)", "ऐप फ़ाइल (APK)")
    val fileBody = Bi("See what it could do before you install it", "इंस्टॉल करने से पहले देखें कि यह क्या कर सकती है")
    val appsTitle = Bi("Apps on this phone", "इस फ़ोन के ऐप")
    val appsBody = Bi("What each one is allowed to do", "हर ऐप को क्या करने की अनुमति है")
    val cameraTitle = Bi("Camera check", "कैमरा जाँच")
    val cameraBody = Bi("Review a photo of a consent screen", "कंसेंट स्क्रीन की फ़ोटो जाँचें")
    val askTitle = Bi("Ask about my last check", "मेरी पिछली जाँच के बारे में पूछें")
    val samplesTitle = Bi("More samples", "और नमूने")
    val samplesBody = Bi("Synthetic examples, not a live audit", "बनावटी उदाहरण, असली जाँच नहीं")
    val automateTitle = Bi("Automate it", "ऑटोमेट करें")
    val automateBody = Bi("Run checks from your laptop or CI", "लैपटॉप या CI से जाँच चलाएँ")
    val aiTitle = Bi("On-device AI", "डिवाइस पर AI")
    val aiAction = Bi("AI lab", "AI लैब")
    fun checking(app: String) = Bi("Checking $app", "$app की जाँच हो रही है")
    val fix = Bi("Fix", "ठीक करें")
    val allow = Bi("Allow", "अनुमति दें")
}

private const val RECENT_ON_HOME = 3

/**
 * Home, redesigned: one hero with the one main action, one readiness line, three recent checks and a short list of other
 * ways to check. Developer mode shows the same Home with a few more rows. Everything here opens an existing screen; the
 * readiness gate is unchanged (`prepareAudit`): when the check cannot start yet, the Setup sheet opens and says why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: XrayViewModel,
    onAudit: () -> Unit,
    onOpen: (String) -> Unit,
    onLab: () -> Unit,
    onCamera: () -> Unit,
    onSettings: () -> Unit,
    onVoice: (String) -> Unit,
    /** Opens the APK result for a file-check key from [ApkFileChecks.start]. */
    onApkFile: (String) -> Unit,
    /** "Apps on this phone" (the phone checkup); null hides the row when this build has no such screen. */
    onCheckup: (() -> Unit)? = null,
    onLive: () -> Unit = {},
    onAllReports: () -> Unit = {},
    /** Developer mode only: the laptop and CI automation settings; null hides the row. */
    onAutomation: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val lang = guideLang()
    val pf by vm.preflight.collectAsState()
    val audits by vm.audits.collectAsState()
    val llm by vm.llmStatus.collectAsState()
    val progress by vm.progress.collectAsState()
    val settings by vm.settings.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    var showSamples by remember { mutableStateOf(false) }
    val developer = settings.audience == Audience.DEVELOPER

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshPreflight() }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onApkFile(ApkFileChecks.start(ctx, uri))
    }
    LaunchedEffect(vm) { vm.refreshPreflight() }
    DisposableEffect(ctx, vm) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { vm.refreshPreflight() }
        }
        ctx.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        onDispose { ctx.contentResolver.unregisterContentObserver(observer) }
    }

    val items = SetupSteps.from(pf)
    val todo = SetupSteps.todo(items)
    val running = progress as? AuditProgress.Running
    // The visible guide banner is gone from Home; read-aloud still speaks the same step.
    if (running == null) SpeakStep(com.dpdpxray.app.ui.guide.GuideFlow.home(pf))
    val fix: (SetupKind) -> Unit = { kind ->
        when (kind) {
            SetupKind.VPN -> if (DeviceChecks.otherVpnActive(ctx)) DeviceChecks.openVpnSettings(ctx) else VpnService.prepare(ctx)?.let { vpnLauncher.launch(it) } ?: vm.refreshPreflight()
            SetupKind.WATCHER -> DeviceChecks.openAccessibilitySettings(ctx)
            SetupKind.PRIVATE_DNS -> DeviceChecks.openPrivateDnsSettings(ctx)
            SetupKind.SHIZUKU -> vm.requestShizuku()
        }
    }
    // Same gate as before; when it is not met the sheet explains what is missing instead of a dead button.
    val startCheck = { if (pf.canAudit && vm.prepareAudit()) onAudit() else showSetup = true }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TraceVeil", style = MaterialTheme.typography.headlineMedium, maxLines = 1, modifier = Modifier.weight(1f).semantics { heading() })
            LangVoiceButton()
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = "Settings" }) {
                GlyphIcon(Glyph.SETTINGS, Film.boneDim, size = 24.dp)
            }
        }
        Spacer(Modifier.height(16.dp))

        // The one hero: a promise, one sentence, one action, one readiness line.
        Column(
            Modifier.fillMaxWidth().reveal(0).cardSurface().padding(24.dp),
        ) {
            if (running != null) {
                Text(HomeCopy.checking(running.config.targetLabel).pick(lang), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(20.dp))
                PrimaryButton(EverydayCopy.running.pick(lang), onLive)
            } else {
                Text(HomeCopy.heroTitle.pick(lang), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(HomeCopy.heroBody.pick(lang), style = MaterialTheme.typography.bodyLarge, color = Film.boneDim, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(20.dp))
                PrimaryButton(HomeCopy.checkApp.pick(lang), startCheck)
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (todo.isEmpty()) {
                        com.dpdpxray.app.ui.system.StatusDot(Film.clear)
                        Spacer(Modifier.size(8.dp))
                        Text(EverydayCopy.ready.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                    } else {
                        TextAction(HomeCopy.stepsToFinish(todo.size).pick(lang) + "  ›", { showSetup = true }, color = Film.warn)
                    }
                }
            }
        }

        // Recent checks: three rows, the rest in Reports.
        SectionHeader(EverydayCopy.recentTitle.pick(lang)) {
            if (audits.size > RECENT_ON_HOME) TextAction(EverydayCopy.seeAll.pick(lang), onAllReports)
        }
        if (audits.isEmpty()) {
            Group {
                ListRow(
                    EverydayCopy.emptyTitle.pick(lang), subtitle = null, titleColor = Film.boneDim,
                    trailing = { TextAction(EverydayCopy.seeSample.pick(lang), { vm.openSample(onOpen) }) },
                )
            }
        } else {
            Group {
                audits.take(RECENT_ON_HOME).forEachIndexed { i, meta ->
                    if (i > 0) GroupDivider()
                    CheckRow(meta, lang) { onOpen(meta.id) }
                }
            }
        }

        // Other ways to check; developer mode adds the tools that used to sit in Quick actions.
        SectionHeader(HomeCopy.moreWays.pick(lang))
        Group {
            ListRow(
                HomeCopy.fileTitle.pick(lang), subtitle = HomeCopy.fileBody.pick(lang),
                leading = { GlyphIcon(Glyph.APK, Film.boneDim, size = 24.dp) },
                onClick = { filePicker.launch(arrayOf(IncomingApk.APK_MIME, "application/octet-stream")) },
            )
            if (onCheckup != null) {
                GroupDivider(54.dp)
                ListRow(
                    HomeCopy.appsTitle.pick(lang), subtitle = HomeCopy.appsBody.pick(lang),
                    leading = { GlyphIcon(Glyph.SEARCH, Film.boneDim, size = 24.dp) }, onClick = onCheckup,
                )
            }
            if (developer) {
                GroupDivider(54.dp)
                ListRow(
                    HomeCopy.cameraTitle.pick(lang), subtitle = HomeCopy.cameraBody.pick(lang),
                    leading = { GlyphIcon(Glyph.CAMERA, Film.boneDim, size = 24.dp) }, onClick = onCamera,
                )
                audits.firstOrNull()?.let { latest ->
                    GroupDivider(54.dp)
                    ListRow(
                        HomeCopy.askTitle.pick(lang), subtitle = latest.targetLabel,
                        leading = { GlyphIcon(Glyph.ASK, Film.boneDim, size = 24.dp) }, onClick = { onVoice(latest.id) },
                    )
                }
                GroupDivider(54.dp)
                ListRow(
                    EverydayCopy.seeSample.pick(lang), subtitle = null,
                    leading = { GlyphIcon(Glyph.SAMPLE, Film.boneDim, size = 24.dp) }, onClick = { vm.openSample(onOpen) },
                )
                GroupDivider(54.dp)
                ListRow(
                    HomeCopy.samplesTitle.pick(lang), subtitle = HomeCopy.samplesBody.pick(lang),
                    leading = { GlyphIcon(Glyph.REPORTS, Film.boneDim, size = 24.dp) }, onClick = { showSamples = true },
                )
                if (onAutomation != null) {
                    GroupDivider(54.dp)
                    ListRow(
                        HomeCopy.automateTitle.pick(lang), subtitle = HomeCopy.automateBody.pick(lang),
                        leading = { GlyphIcon(Glyph.PLAY, Film.boneDim, size = 24.dp) }, onClick = onAutomation,
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showSetup) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSetup = false }, sheetState = sheet, containerColor = Film.base) {
            SetupSheetContent(items, todo, lang, aiLine = aiStatusLine(llm), fix = fix, onLab = { showSetup = false; onLab() })
        }
    }
    if (showSamples) SampleFixturesDialog(vm, onOpen) { showSamples = false }
}

@Composable
private fun SectionHeader(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** A saved check: name, plain result and date on the left, the score tinted by its band on the right. */
@Composable
internal fun CheckRow(meta: AuditMeta, lang: GuideLang, onClick: () -> Unit) {
    val partial = RecentChecks.coverageWarning(meta) != null
    val subtitle = (if (partial) EverydayCopy.partial.pick(lang) + " · " else "") +
        EverydayCopy.band(meta.band).pick(lang) + " · " + SimpleDateFormat("d MMM", Locale.US).format(Date(meta.createdAtMs))
    ListRow(
        title = meta.targetLabel, subtitle = subtitle, onClick = onClick,
        trailing = { Text(RecentChecks.scoreText(meta), style = MaterialTheme.typography.titleLarge, color = bandColor(meta.band)) },
    )
}

/** The Setup sheet: what still needs doing first, then what is already in place, then the optional on-device AI. */
@Composable
private fun SetupSheetContent(
    items: List<SetupItem>, todo: List<SetupItem>, lang: GuideLang, aiLine: String, fix: (SetupKind) -> Unit, onLab: () -> Unit,
) {
    val done = items.filter { it !in todo && it.need != SetupNeed.OPTIONAL }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
        Text(HomeCopy.setupTitle.pick(lang), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp).semantics { heading() })
        if (todo.isNotEmpty()) {
            Group(title = HomeCopy.toDo.pick(lang)) {
                todo.forEachIndexed { i, item ->
                    if (i > 0) GroupDivider()
                    val plain = EverydayCopy.setupTitle(item.kind).pick(lang)
                    val detail = EverydayCopy.setupDetail(item.kind).pick(lang)
                    ListRow(
                        plain, subtitle = detail, subtitleMaxLines = 4,
                        trailing = if (item.fixable) { { TextAction((if (item.kind == SetupKind.SHIZUKU) HomeCopy.allow else HomeCopy.fix).pick(lang), { fix(item.kind) }) } } else null,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Group(title = HomeCopy.allSet.pick(lang).takeIf { done.isNotEmpty() }) {
            done.forEachIndexed { i, item ->
                if (i > 0) GroupDivider()
                ListRow(item.title, subtitle = item.detail, trailing = { GlyphIcon(Glyph.CHECK, Film.clear, size = 20.dp) })
            }
        }
        Spacer(Modifier.height(20.dp))
        Group(title = HomeCopy.optional.pick(lang)) {
            ListRow(
                HomeCopy.aiTitle.pick(lang), subtitle = aiLine,
                trailing = { TextAction(HomeCopy.aiAction.pick(lang), onLab) },
            )
        }
    }
}
