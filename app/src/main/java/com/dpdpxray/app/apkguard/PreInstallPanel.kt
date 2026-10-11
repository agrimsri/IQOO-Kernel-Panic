package com.dpdpxray.app.apkguard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dpdpxray.app.BuildConfig
import com.dpdpxray.app.autoguard.DownloadAlertCopy
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.system.Group
import com.dpdpxray.app.ui.system.GroupDivider
import com.dpdpxray.app.ui.system.ListRow
import com.dpdpxray.app.ui.system.Notice
import com.dpdpxray.app.ui.system.NoticeKind
import com.dpdpxray.app.ui.system.PrimaryButton
import com.dpdpxray.app.ui.system.SecondaryButton
import com.dpdpxray.app.ui.system.TextAction
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.apk.ApkGuardReport
import com.dpdpxray.core.apk.BannerTone
import com.dpdpxray.core.apk.Bilingual
import com.dpdpxray.core.apk.CodeScanResult
import com.dpdpxray.core.apk.InstallHighlights
import com.dpdpxray.core.apk.PreInstallCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Bilingual.pick(hindi: Boolean) = if (hindi) hi else en

/** What the panel shows besides Android's own session state. */
private sealed interface LocalStep {
    data object Idle : LocalStep
    data object Working : LocalStep
    data object NeedsPermission : LocalStep
    data class Problem(val reason: String) : LocalStep
}

/**
 * "Check before install": the decision under an APK file's APK Guard result. A banner says how the file relates to an
 * installed app (a different signer is the big red flag) or why Android would refuse it; then "Install anyway" and
 * "Don't install", with "Don't install" first whenever something needs careful review. "Install anyway" hands the exact
 * checked copy to Android's installer, which asks the person to confirm; TraceVeil never installs by itself, never
 * bypasses Android's "Install unknown apps" switch and never starts a consent check by itself.
 */
@Composable
internal fun PreInstallPanel(
    checkKey: String,
    report: ApkGuardReport,
    hindi: Boolean,
    onConsentCheck: (String) -> Unit,
    onOpenInstalled: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val advice = remember(report) { PreInstallCheck.advise(report, Build.VERSION.SDK_INT) }
    val pkg = report.facts.packageName
    val sessions by InstallSessions.state.collectAsState()
    val session = sessions[checkKey]?.state
    var step by remember(checkKey) { mutableStateOf<LocalStep>(LocalStep.Idle) }
    var declined by rememberSaveable(checkKey) { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val allowedNow = remember(resumes, step) { runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false) }

    // Android's own confirmation screen, shown once while this screen is in front (an activity may start it).
    LaunchedEffect(session) {
        if (session is InstallState.WaitingForYou) InstallSessions.takeConfirmation(checkKey)?.let { runCatching { ctx.startActivity(it) } }
    }

    val install: () -> Unit = {
        step = LocalStep.Working
        scope.launch {
            InstallSessions.set(checkKey, InstallState.Preparing)
            val result = withContext(Dispatchers.IO) {
                InstallHandoff(PlatformInstaller(ctx)).start(checkKey, ApkFileChecks.heldCopy(checkKey), advice.block)
            }
            step = when (result) {
                is HandoffStart.Started -> {
                    InstallSessions.expected.expect(pkg, System.currentTimeMillis())
                    LocalStep.Idle
                }
                HandoffStart.NeedsPermission -> LocalStep.NeedsPermission
                is HandoffStart.Blocked -> LocalStep.Problem(PreInstallCopy.blocked.pick(hindi))
                is HandoffStart.Unavailable -> LocalStep.Problem(result.reason)
                is HandoffStart.Failed -> LocalStep.Problem(result.reason)
            }
            if (result !is HandoffStart.Started) InstallSessions.clear(checkKey)
        }
    }
    val dontInstall: () -> Unit = {
        ApkFileChecks.release(checkKey)
        InstallSessions.clear(checkKey)
        declined = true
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        advice.banner?.let { banner ->
            Notice(
                banner.text.pick(hindi),
                kind = when (banner.tone) { BannerTone.RISK -> NoticeKind.RISK; BannerTone.CAUTION -> NoticeKind.CAUTION; BannerTone.INFO -> NoticeKind.INFO },
            )
        }
        Group(title = PreInstallCopy.sectionTitle.pick(hindi), footer = PreInstallCopy.footnote.pick(hindi)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    declined -> {
                        Notice(PreInstallCopy.declined.pick(hindi))
                        TextAction(if (hindi) "वापस जाएँ" else "Back", onBack)
                    }
                    session is InstallState.Installed -> {
                        Notice(PreInstallCopy.installed.pick(hindi))
                        PrimaryButton(PreInstallCopy.runConsentCheck.pick(hindi), { onConsentCheck(session.packageName ?: pkg) })
                        SecondaryButton(PreInstallCopy.seeInstalled.pick(hindi), { onOpenInstalled(session.packageName ?: pkg) }, Modifier.fillMaxWidth())
                    }
                    session is InstallState.WaitingForYou -> Notice(PreInstallCopy.waiting.pick(hindi))
                    session is InstallState.Preparing || step == LocalStep.Working -> Notice(PreInstallCopy.preparing.pick(hindi))
                    !BuildConfig.INSTALL_HANDOFF -> {
                        Text(PreInstallCopy.continueWithInstaller.pick(hindi), style = MaterialTheme.typography.bodyMedium, color = Film.bone)
                        PrimaryButton(PreInstallCopy.dontInstall.pick(hindi), dontInstall)
                    }
                    else -> {
                        Text(
                            (if (advice.block != null) PreInstallCopy.blocked else PreInstallCopy.decide).pick(hindi),
                            style = MaterialTheme.typography.bodyMedium, color = Film.bone,
                        )
                        when (session) {
                            InstallState.Cancelled -> Notice(PreInstallCopy.cancelled.pick(hindi))
                            is InstallState.Failed -> Notice(session.reason.pick(hindi) + (session.detail?.let { "\n($it)" } ?: ""), kind = NoticeKind.RISK)
                            else -> Unit
                        }
                        when (val s = step) {
                            LocalStep.NeedsPermission -> Notice(
                                (if (allowedNow) PreInstallCopy.permissionGranted else PreInstallCopy.needsPermission).pick(hindi),
                                kind = if (allowedNow) NoticeKind.INFO else NoticeKind.CAUTION,
                                action = if (allowedNow) null else PreInstallCopy.openSetting.pick(hindi) to { openUnknownAppsSetting(ctx) },
                            )
                            is LocalStep.Problem -> Notice(s.reason, kind = NoticeKind.RISK)
                            else -> Unit
                        }
                        when {
                            advice.block != null -> PrimaryButton(PreInstallCopy.dontInstall.pick(hindi), dontInstall)
                            advice.leanDontInstall -> {
                                PrimaryButton(PreInstallCopy.dontInstall.pick(hindi), dontInstall)
                                SecondaryButton(PreInstallCopy.installAnyway.pick(hindi), install, Modifier.fillMaxWidth())
                            }
                            else -> {
                                PrimaryButton(PreInstallCopy.installAnyway.pick(hindi), install)
                                SecondaryButton(PreInstallCopy.dontInstall.pick(hindi), dontInstall, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Android's "Install unknown apps" page for TraceVeil. Opening it changes nothing; the person decides there. */
private fun openUnknownAppsSetting(ctx: android.content.Context) {
    val own = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(own) }.recoverCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
}

/**
 * "SDKs named in its code": the static code scan, as a grouped list. For an APK file the scan already ran while the
 * file was read; for an installed app it runs only when the person taps "Read its code" ([onRead]).
 */
@Composable
internal fun CodeFoundGroup(
    scan: CodeScanResult?,
    hindi: Boolean,
    modifier: Modifier = Modifier,
    reading: Boolean = false,
    unavailable: Boolean = false,
    onRead: (() -> Unit)? = null,
) {
    Group(modifier, title = PreInstallCopy.codeTitle.pick(hindi), footer = PreInstallCopy.codeFooter.pick(hindi)) {
        when {
            scan != null && scan.found.isEmpty() -> ListRow(PreInstallCopy.codeNone.pick(hindi))
            scan != null -> scan.found.entries.forEachIndexed { i, (entity, packages) ->
                if (i > 0) GroupDivider()
                val category = InstallHighlights.catalogueCategory(entity)
                ListRow(
                    entity,
                    subtitle = PreInstallCopy.categoryLabel(category).pick(hindi) + " · " + packages.joinToString(", "),
                    titleColor = if (category?.isTracking == true) Film.bone else Film.boneDim,
                )
            }
            reading -> ListRow(PreInstallCopy.codeReading.pick(hindi))
            unavailable -> ListRow(PreInstallCopy.codeUnavailable.pick(hindi), subtitleMaxLines = 3)
            onRead != null -> ListRow(PreInstallCopy.codeRead.pick(hindi), onClick = onRead, titleColor = Film.scan)
            else -> ListRow(PreInstallCopy.codeUnavailable.pick(hindi))
        }
        if (scan != null && !scan.complete && scan.dexFilesRead > 0) {
            GroupDivider()
            ListRow(PreInstallCopy.codePartial.pick(hindi), titleColor = Film.boneDim)
        }
    }
}

/** The installed-app version of [CodeFoundGroup]: reads the code on tap, off the main thread. */
@Composable
internal fun InstalledCodeGroup(packageName: String, hindi: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember(packageName) { mutableStateOf(false) }
    var done by remember(packageName) { mutableStateOf(false) }
    var scan by remember(packageName) { mutableStateOf<CodeScanResult?>(null) }
    CodeFoundGroup(scan, hindi, modifier, reading = reading, unavailable = done && scan == null, onRead = {
        if (!reading) {
            reading = true
            scope.launch {
                scan = withContext(Dispatchers.IO) { InstalledScan.code(ctx, packageName) }
                reading = false
                done = true
            }
        }
    })
}

/**
 * APK Guard hub: how "check before install" reaches the person. Which app opens a tapped APK (TraceVeil with Always means
 * every tapped APK is checked first) and whether new APK downloads are announced. Read-only; [refresh] re-reads both.
 */
@Composable
internal fun BeforeInstallGroup(downloadsOn: Boolean, refresh: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lang = guideLang()
    val (opener, otherLabel) = remember(refresh) { ApkOpener.read(ctx) }
    val hindi = lang == GuideLang.HI
    Group(modifier, title = if (hindi) "इंस्टॉल से पहले जाँच" else "Check before install") {
        ListRow(
            if (hindi) "APK फ़ाइलें TraceVeil से खोलें" else "Open APK files with TraceVeil",
            subtitle = ApkOpener.summary(opener, otherLabel).pick(lang),
            subtitleMaxLines = 4,
            leading = { GlyphIcon(Glyph.APK, Film.boneDim, size = 24.dp) },
        )
        GroupDivider(inset = 54.dp)
        ListRow(
            DownloadAlertCopy.switchTitle.pick(lang),
            subtitle = when {
                !downloadsOn -> if (hindi) "बंद · सेटिंग्स › Auto-guard में चालू करें" else "Off · switch on in Settings › Auto-guard"
                else -> if (hindi) "चालू · जब Android बताता है, सूचना आती है" else "On · a notification when Android reports one"
            },
            leading = { GlyphIcon(Glyph.BELL, Film.boneDim, size = 24.dp) },
        )
    }
}
