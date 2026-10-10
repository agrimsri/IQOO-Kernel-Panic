package com.dpdpxray.app.ui.modes

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dpdpxray.app.apkguard.ApkFileChecks
import com.dpdpxray.app.apkguard.IncomingApk
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.trust.HomeTrustCard
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.DisclosureRow
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.GroupHeader
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.IconBadge
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.foldSpec
import com.dpdpxray.app.ui.components.pressClickable
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.EmptyState
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.GuideBanner
import com.dpdpxray.app.ui.guide.GuideFlow
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.LinkAction
import com.dpdpxray.app.ui.guide.PillButton
import com.dpdpxray.app.ui.guide.RecentChecks
import com.dpdpxray.app.ui.guide.ScoreChip
import com.dpdpxray.app.ui.guide.SetupKind
import com.dpdpxray.app.ui.guide.SetupNeed
import com.dpdpxray.app.ui.guide.SetupSteps
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.guide.lessLabel
import com.dpdpxray.app.ui.guide.moreLabel
import com.dpdpxray.app.ui.screens.SetupRow
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.Radii
import com.dpdpxray.app.ui.theme.bandColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everyday ("Protect my phone") Home: a hero that carries the main "No" check, four plain cards, compact recent checks
 * and a way back to developer mode. Same engine and destinations as developer mode; only the front door is simpler.
 * Cards whose screen is not in this build (null callback) are left out rather than shown broken.
 */
@Composable
fun EverydayHomeScreen(
    vm: XrayViewModel,
    /** The existing app picker; its check sheet opens on the everyday Quick check. */
    onCheckApp: () -> Unit,
    /** Opens the APK Guard result for a file-check key from [ApkFileChecks.start]. */
    onApkFile: (String) -> Unit,
    /** Where the new-app alert setting lives (APK Guard's alerts card). */
    onAlerts: () -> Unit,
    onOpen: (String) -> Unit,
    onAllReports: () -> Unit,
    onLive: () -> Unit,
    onSettings: () -> Unit,
    /** "My phone checkup"; null hides the card. */
    onCheckup: (() -> Unit)? = null,
    /** "Why you can trust TraceVeil"; null hides the card. */
    onTrust: (() -> Unit)? = null,
    /** Update Watch "Check again": the same re-check route as Developer Home and Reports; null hides the card. */
    onRecheck: ((String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val lang = guideLang()
    val pf by vm.preflight.collectAsState()
    val audits by vm.audits.collectAsState()
    val progress by vm.progress.collectAsState()
    val settings by vm.settings.collectAsState()
    // Update Watch: the same list Developer Home and Reports show; the card appears only when it is not empty.
    val updated by vm.updatedApps.collectAsState()
    val updateCard = EverydayUpdates.card(updated)

    // Readiness and notification state can change in system settings; re-read them whenever Home comes back.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    LaunchedEffect(vm) { vm.refreshPreflight() }
    DisposableEffect(ctx, vm) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { vm.refreshPreflight() }
        }
        ctx.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        onDispose { ctx.contentResolver.unregisterContentObserver(observer) }
    }
    val notifications = remember(resumes) { notificationsAllowed(ctx) }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshPreflight() }
    // Same file picker as APK Guard's "Check an APK file": TraceVeil only reads the chosen file.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onApkFile(ApkFileChecks.start(ctx, uri))
    }
    val fix: (SetupKind) -> Unit = { kind ->
        when (kind) {
            SetupKind.VPN -> if (DeviceChecks.otherVpnActive(ctx)) DeviceChecks.openVpnSettings(ctx) else VpnService.prepare(ctx)?.let { vpnLauncher.launch(it) } ?: vm.refreshPreflight()
            SetupKind.WATCHER -> DeviceChecks.openAccessibilitySettings(ctx)
            SetupKind.PRIVATE_DNS -> DeviceChecks.openPrivateDnsSettings(ctx)
            SetupKind.SHIZUKU -> vm.requestShizuku()
        }
    }
    val todo = SetupSteps.todo(SetupSteps.from(pf))
    val required = todo.count { it.need == SetupNeed.REQUIRED }
    val running = progress as? AuditProgress.Running
    val alertStatus = EverydayCopy.alertStatus(notifications, pf.accessibilityOn)

    val checkApp = { if (vm.prepareAudit()) onCheckApp() }
    val setupRows: @Composable ColumnScope.() -> Unit = {
        // The readiness gate folded to its first line, then only the steps still to do, each with its own button.
        if (required > 0) FoldText(EverydayCopy.readiness.pick(lang), Modifier.padding(top = 4.dp), color = Film.bone, moreLabel = lang.moreLabel, lessLabel = lang.lessLabel)
        todo.forEachIndexed { i, item ->
            val plain = if (item.kind == SetupKind.VPN && pf.otherVpnActive)
                item.copy(title = EverydayCopy.otherVpnTitle.pick(lang), detail = EverydayCopy.otherVpnDetail.pick(lang))
            else item.copy(title = EverydayCopy.setupTitle(item.kind).pick(lang), detail = EverydayCopy.setupDetail(item.kind).pick(lang))
            SetupRow(plain, number = if (item.need == SetupNeed.REQUIRED) i + 1 else null) { fix(item.kind) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            com.dpdpxray.app.ui.components.BrandMark(size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("TraceVeil", style = MaterialTheme.typography.headlineMedium, maxLines = 1, modifier = Modifier.semantics { heading() })
                Box(Modifier.padding(top = 4.dp).size(width = 44.dp, height = 3.dp).clip(CircleShape).background(Brand.horizontal))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = "Settings" }) {
                GlyphIcon(Glyph.SETTINGS, Film.boneDim, size = 24.dp)
            }
        }
        val modeSettings by vm.settings.collectAsState()
        ModePill(
            modeSettings.audience, { a -> vm.updateSettings { it.copy(audience = a) } },
            Modifier.fillMaxWidth().padding(top = 12.dp), lang = lang,
        )

        Spacer(Modifier.height(16.dp))
        // The hero carries the one main action: the "No" check, with its readiness and any steps still to do.
        EverydayHero(
            lang, Modifier.reveal(0),
            onCheck = checkApp,
            status = {
                if (required == 0) StatusPill(EverydayCopy.ready.pick(lang), Film.clear)
                else StatusPill(EverydayCopy.stepsFirst(required).pick(lang), Film.warn)
            },
            showSetup = todo.isNotEmpty(),
            setup = setupRows,
        )

        if (running != null) {
            Spacer(Modifier.height(16.dp))
            GuideBanner(GuideFlow.homeRunning(running.config.targetLabel), speak = false, action = EverydayCopy.running.pick(lang) to onLive)
        }

        if (onRecheck != null && updateCard != null) {
            Spacer(Modifier.height(16.dp))
            UpdatedAppsCard(updateCard, updated.size, lang, onRecheck, onAllReports, Modifier.reveal(1))
        }

        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FrontCard(
                EverydayCopy.file, lang, accent = Film.scan, modifier = Modifier.reveal(1),
                icon = { GlyphIcon(Glyph.APK, it, size = 24.dp) },
                onClick = { filePicker.launch(arrayOf(IncomingApk.APK_MIME, "application/octet-stream")) },
            )
            if (onCheckup != null) FrontCard(
                EverydayCopy.apps, lang, accent = Film.scan, modifier = Modifier.reveal(2),
                icon = { ModeGlyphIcon(ModeGlyph.PHONE, it, size = 24.dp) },
                onClick = onCheckup,
            )
            FrontCard(
                EverydayCopy.alerts, lang, accent = Brand.peach, modifier = Modifier.reveal(3),
                icon = { GlyphIcon(Glyph.BELL, it, size = 24.dp) },
                onClick = onAlerts,
                status = {
                    val color = when (alertStatus) { AlertStatus.ON -> Film.clear; AlertStatus.WHILE_OPEN -> Film.warn; AlertStatus.BLOCKED -> Film.high }
                    StatusPill(EverydayCopy.alertLabel(alertStatus, settings.speakApkGuardAlerts).pick(lang), color)
                },
            )
            // The trust card: four code-checked facts and the live permission line; it opens the full trust page.
            if (onTrust != null) HomeTrustCard(lang, onTrust, Modifier.reveal(4))
        }

        GroupHeader(EverydayCopy.recentTitle.pick(lang)) {
            if (audits.size > RECENT_ON_HOME) LinkAction("${EverydayCopy.seeAll.pick(lang)} (${audits.size})", onAllReports)
        }
        if (audits.isEmpty()) {
            EmptyState(
                EverydayCopy.emptyTitle.pick(lang), EverydayCopy.emptyBody.pick(lang),
                action = EverydayCopy.seeSample.pick(lang) to { vm.openSample(onOpen) },
            )
        } else {
            Column(Modifier.fillMaxWidth().reveal(5).cardSurface().padding(horizontal = 12.dp, vertical = 4.dp)) {
                audits.take(RECENT_ON_HOME).forEachIndexed { i, meta ->
                    if (i > 0) Hairline()
                    CompactCheckRow(meta, lang) { onOpen(meta.id) }
                }
            }
        }

        LinkAction(
            ModeCopy.switchToDeveloper.pick(lang),
            { vm.updateSettings { it.copy(audience = Audience.DEVELOPER) } },
            Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp),
            color = Film.boneDim,
        )
        Spacer(Modifier.height(16.dp))
    }
}

private const val RECENT_ON_HOME = 3

/** The hero's fold label, in both languages. */
private val HowItWorks = Bi("How it works", "यह कैसे काम करता है")
private val HideHowItWorks = Bi("Hide", "छिपाएँ")

/**
 * The lightbox: mode chip, the promise, one sentence, two plain assurances, then the main action ("Does this app
 * respect my 'No'?") as the brand gradient with a slow light sweep. Readiness sits under the action; how the check
 * works folds behind "How it works"; steps still to do follow with their own buttons. A faint film grid, a consent
 * line and a warm brand wash sit behind it, with a soft coral glow beneath the card.
 */
@Composable
private fun EverydayHero(
    lang: GuideLang,
    modifier: Modifier,
    onCheck: () -> Unit,
    status: @Composable () -> Unit,
    showSetup: Boolean,
    setup: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().cardSurface(22.dp, glow = Brand.coral).background(Brand.heroWash)) {
        Canvas(Modifier.matchParentSize()) {
            val step = 22.dp.toPx()
            val grid = FilmExtra.hairline.copy(alpha = 0.55f)
            var x = step
            while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = step
            while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += step }
            val line = 150.dp.toPx().coerceAtMost(size.height * 0.42f)
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, Film.scan.copy(alpha = 0.45f), Color.Transparent)),
                Offset(0f, line), Offset(size.width, line), 2.dp.toPx(),
            )
        }
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier.clip(CircleShape).background(Brand.coral.copy(alpha = 0.14f)).border(1.dp, Brand.coral.copy(alpha = 0.45f), CircleShape)
                    .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeGlyphIcon(ModeGlyph.PHONE, Brand.coral, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(EverydayCopy.eyebrow.pick(lang), style = MaterialTheme.typography.labelMedium, color = Brand.coral)
            }
            Text(
                EverydayCopy.heroTitle.pick(lang), style = MaterialTheme.typography.headlineMedium, color = Film.bone,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
            FoldText(
                EverydayCopy.heroBody.pick(lang), Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge, moreLabel = lang.moreLabel, lessLabel = lang.lessLabel,
            )
            ChipFlow(Modifier.padding(top = 12.dp)) {
                Tag(EverydayCopy.onPhone.pick(lang), Film.clear)
                Tag(EverydayCopy.readsOnly.pick(lang), Film.scan)
            }

            GradientAction(EverydayCopy.sayNo.title.pick(lang), onCheck, Modifier.padding(top = 20.dp), sweep = true)

            var open by rememberSaveable { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize(foldSpec())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    status()
                    Spacer(Modifier.weight(1f))
                    DisclosureRow(open, HowItWorks.pick(lang), HideHowItWorks.pick(lang), { open = !open })
                }
                if (open) Text(
                    EverydayCopy.sayNo.body.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            if (showSetup) {
                Hairline(Modifier.padding(top = 8.dp))
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), content = setup)
            }
        }
    }
}

/**
 * One plain card: a tinted glyph badge, a title, an optional status pill, at most two lines of description and a
 * chevron. The whole card is one button for TalkBack (which still reads the full description) and shrinks slightly
 * while pressed.
 */
@Composable
private fun FrontCard(
    card: EverydayCopy.Card,
    lang: GuideLang,
    accent: Color,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Radii.card)
    Row(
        modifier.fillMaxWidth()
            .pressClickable(shape = shape, onClick = onClick)
            .cardSurface()
            .heightIn(min = 84.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0.08f))))
                .border(1.dp, accent.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { icon(accent) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(card.title.pick(lang), style = MaterialTheme.typography.titleMedium, color = Film.bone)
            if (status != null) Box(Modifier.padding(top = 6.dp)) { status() }
            Text(
                card.body.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        GlyphIcon(Glyph.CHEVRON_RIGHT, Film.boneDim, size = 20.dp)
    }
}

/**
 * The calm everyday Update Watch card: "An app you checked has updated", one line on what that means, then each app
 * ("LeakyShop 1.0 → 1.1") with "Check again", which opens the check for that app; nothing starts until Start is
 * tapped. Past [EverydayUpdates.MAX_ROWS] apps, "See all" opens Reports, which lists every one.
 */
@Composable
private fun UpdatedAppsCard(
    card: EverydayUpdateCard,
    total: Int,
    lang: GuideLang,
    onRecheck: (String) -> Unit,
    onAllReports: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().cardSurface().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Glyph.RESET, Film.scan, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                card.title.pick(lang), style = MaterialTheme.typography.titleMedium, color = Film.bone,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        Text(
            EverydayCopy.updatedNote.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim,
            modifier = Modifier.padding(top = 8.dp),
        )
        card.rows.forEach { row ->
            Hairline(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.line.pick(lang), style = MaterialTheme.typography.bodyLarge, color = Film.bone,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PillButton(EverydayCopy.checkAgain.pick(lang), { onRecheck(row.packageName) })
            }
        }
        if (card.more > 0) LinkAction("${EverydayCopy.seeAll.pick(lang)} ($total)", onAllReports)
    }
}

/** A coloured dot and a short state, in a soft pill. */
@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A saved check in one line: score, app, plain result name and date; amber "Partial check" when coverage was limited. */
@Composable
private fun CompactCheckRow(meta: AuditMeta, lang: GuideLang, onClick: () -> Unit) {
    val color = bandColor(meta.band)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .pressClickable(shape = RoundedCornerShape(Radii.small), onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScoreChip(RecentChecks.scoreText(meta), color, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(meta.targetLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (meta.sample) {
                    Spacer(Modifier.width(8.dp))
                    Tag(EverydayCopy.sample.pick(lang), Film.boneDim)
                }
            }
            val partial = RecentChecks.coverageWarning(meta) != null
            Text(
                (if (partial) EverydayCopy.partial.pick(lang) + " · " else "") + EverydayCopy.band(meta.band).pick(lang) + " · " +
                    SimpleDateFormat("d MMM", Locale.US).format(Date(meta.createdAtMs)),
                style = MaterialTheme.typography.bodyMedium, color = if (partial) Film.warn else Film.boneDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        GlyphIcon(Glyph.CHEVRON_RIGHT, Film.boneDim, size = 18.dp)
    }
}

/** Same check as APK Guard's alerts card: alerts need notifications allowed for TraceVeil. */
private fun notificationsAllowed(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    return ctx.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() != false
}
