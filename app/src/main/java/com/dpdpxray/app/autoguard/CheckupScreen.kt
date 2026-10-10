package com.dpdpxray.app.autoguard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dpdpxray.app.graph
import com.dpdpxray.app.ui.components.BreathingDot
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.IconBadge
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.SkeletonCard
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.pressClickable
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.OutlineAction
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.Mono
import com.dpdpxray.app.ui.theme.Radii
import com.dpdpxray.core.apk.Bilingual
import com.dpdpxray.core.apk.CheckupApp
import com.dpdpxray.core.apk.CheckupArea
import com.dpdpxray.core.apk.CheckupSection
import com.dpdpxray.core.apk.PhoneCheckup
import com.dpdpxray.core.apk.PhoneCheckupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Screen text for "My phone checkup", English and Hindi in one table (the counts and area text come from
 * [com.dpdpxray.core.apk.PhoneCheckup]). `{n}` and `{time}` are filled in at display time.
 */
internal object CheckupText {
    val TITLE = Bilingual("My phone checkup", "मेरे फ़ोन की जाँच")
    val INTRO = Bilingual(
        "Counts the apps you installed that hold powerful access right now, so you know what to review.",
        "आपके इंस्टॉल किए उन ऐप को गिनता है जिनके पास अभी ख़ास ऐक्सेस है, ताकि आप जानें कि क्या देखना है।",
    )
    val RUN_NOW = Bilingual("Run checkup now", "अभी जाँच करें")
    val CHECKING = Bilingual("Checking the apps on this phone…", "इस फ़ोन के ऐप जाँचे जा रहे हैं…")
    val CHECKED = Bilingual("Checked {time} · {n} apps you installed", "{time} पर जाँचा गया · आपके इंस्टॉल किए {n} ऐप")
    val FAILED = Bilingual(
        "Android did not let TraceVeil list the installed apps, so nothing was counted. Try again.",
        "Android ने TraceVeil को इंस्टॉल ऐप की सूची नहीं पढ़ने दी, इसलिए कुछ गिना नहीं गया। फिर से कोशिश करें।",
    )
    val TAP_APP = Bilingual("Tap an app to see everything it can do in APK Guard.", "कोई ऐप टैप करें और APK Guard में देखें कि वह क्या-क्या कर सकता है।")
    val UNKNOWN_FOR = Bilingual(
        "Android did not report this for {n} apps, so for them it is unknown, not zero.",
        "Android ने {n} ऐप के लिए यह नहीं बताया, इसलिए उनके लिए यह अज्ञात है, शून्य नहीं।",
    )
    val WHAT_YOU_CAN_DO = Bilingual("What you can review", "आप क्या देख सकते हैं")
    val OPEN_SETTINGS = Bilingual("Open Android settings", "Android सेटिंग्स खोलें")
    val NOT_FOUND = Bilingual("None found in this checkup", "इस जाँच में कोई नहीं मिला")
    val SCOPE_LABEL = Bilingual("What was checked and what was not", "क्या जाँचा गया और क्या नहीं")
    val NOT_ANTIVIRUS = Bilingual("Not an antivirus", "एंटीवायरस नहीं")
    val ON_PHONE = Bilingual("Stays on this phone", "इसी फ़ोन पर रहता है")
    val WEEKLY = Bilingual("Weekly phone checkup", "हर हफ़्ते फ़ोन जाँच")
    val WEEKLY_DETAIL = Bilingual(
        "Once a week, in the background, TraceVeil runs this checkup and shows one notification if any app needs review.",
        "हफ़्ते में एक बार, बैकग्राउंड में, TraceVeil यह जाँच करता है और किसी ऐप को देखने की ज़रूरत हो तो एक नोटिफ़िकेशन दिखाता है।",
    )
    val LAST_WEEKLY = Bilingual("Last weekly checkup: {time}", "पिछली साप्ताहिक जाँच: {time}")
    val NO_WEEKLY_YET = Bilingual(
        "No weekly checkup has run yet. The first one runs about a week after it is switched on; Android decides the exact time.",
        "अभी तक कोई साप्ताहिक जाँच नहीं चली। पहली जाँच चालू करने के लगभग एक हफ़्ते बाद चलती है; सही समय Android तय करता है।",
    )
    val PRIVACY = Bilingual("Runs on this phone only. Nothing is sent anywhere.", "सिर्फ़ इसी फ़ोन पर चलता है। कुछ भी कहीं नहीं भेजा जाता।")

    fun Bilingual.pick(hindi: Boolean): String = if (hindi) hi else en
}

private class Reading(val result: PhoneCheckupResult?, val at: Long)

@Composable
fun CheckupScreen(onBack: () -> Unit, onOpenApp: (String) -> Unit) {
    val ctx = LocalContext.current
    val store = remember(ctx) { ctx.graph.settings }
    val settings by store.settings.collectAsState()
    var hindi by rememberSaveable { mutableStateOf(settings.guideLanguage == "hi") }
    var runs by remember { mutableIntStateOf(0) }
    var resumedOnce by remember { mutableStateOf(false) }
    // Read again on return from Android settings, so a switched-off access shows at once (not twice on first open).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (resumedOnce) runs++ else resumedOnce = true }
    var busy by remember { mutableStateOf(true) }
    val reading by produceState<Reading?>(null, runs) {
        busy = true
        value = withContext(Dispatchers.IO) { Reading(runCatching { PhoneCheckupRun.now(ctx) }.getOrNull(), System.currentTimeMillis()) }
        busy = false
    }
    val lastWeekly = remember(runs) { AutoGuardStore(ctx).checkupLastRunAt }
    LaunchedEffect(Unit) { PhoneCheckupRun.ensureChannel(ctx) }

    with(CheckupText) {
        Column(Modifier.fillMaxSize()) {
            TopBar(TITLE.pick(hindi), onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val r = reading
                val result = r?.result
                // Amber glow only when there is something to review; never green, because a quiet checkup is not a safety verdict.
                val toReview = (result?.appsToReview ?: 0) > 0
                val tone = if (toReview) Film.warn else Film.scan
                val wash = remember(tone) { tintWash(tone) }
                Column(Modifier.reveal(0).fillMaxWidth().cardSurface(glow = if (toReview) Film.warn else null).background(wash).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Glyph.CHECK, tone, size = 48.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            result?.headline?.pick(hindi) ?: INTRO.pick(hindi),
                            style = if (result != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                            color = Film.bone,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                            BreathingDot(Film.scan)
                            Spacer(Modifier.width(10.dp))
                            Note(CHECKING.pick(hindi))
                        }
                        r?.result == null -> Callout(FAILED.pick(hindi))
                        else -> Text(
                            CHECKED.pick(hindi).replace("{time}", time(r.at, hindi)).replace("{n}", r.result.appsChecked.toString()),
                            style = MaterialTheme.typography.labelMedium, color = Film.boneDim,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    ChipFlow {
                        Tag(NOT_ANTIVIRUS.pick(hindi), Film.warn)
                        Tag(ON_PHONE.pick(hindi), Film.clear)
                    }
                    Spacer(Modifier.height(16.dp))
                    GradientAction(RUN_NOW.pick(hindi), { runs++ }, enabled = !busy, glyph = Glyph.SEARCH)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                        LanguageToggle(hindi) { hindi = it }
                    }
                }

                // First reading: placeholders shaped like the area cards, instead of blank space.
                if (busy && r == null) {
                    SkeletonCard(label = CHECKING.pick(hindi))
                    SkeletonCard()
                }

                var order = 1
                if (result != null) {
                    val found = result.sections.filter { it.count > 0 }
                    if (found.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.CHEVRON_RIGHT, Film.scan, size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(TAP_APP.pick(hindi), style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
                    }
                    found.forEach { AreaCard(it, hindi, onOpenApp, Modifier.reveal(order++)) }
                    val quiet = result.sections.filter { it.count == 0 && it.readable }
                    if (quiet.isNotEmpty()) SectionCard(Modifier.reveal(order++), title = NOT_FOUND.pick(hindi), glyph = Glyph.INFO, accent = Film.boneDim) {
                        ChipFlow { quiet.forEach { s -> Tag(s.area.title.pick(hindi), Film.boneDim) } }
                        // Unknown is never zero: each area Android did not fully report keeps its own visible line.
                        quiet.filter { it.unknown > 0 }.forEach { s ->
                            KeyCaveat("${s.area.title.pick(hindi)}: " + UNKNOWN_FOR.pick(hindi).replace("{n}", s.unknown.toString()), Modifier.padding(top = 8.dp), color = Film.boneDim)
                        }
                    }
                    result.notRead?.let { Callout(it.pick(hindi)) }
                }

                CollapsibleCard(SCOPE_LABEL.pick(hindi), Modifier.reveal(order++), glyph = Glyph.INFO) {
                    Note((result?.scope ?: PhoneCheckup.SCOPE).pick(hindi))
                }

                SectionCard(Modifier.reveal(order)) {
                    Row(verticalAlignment = Alignment.Top) {
                        IconBadge(Glyph.CLOCK, Film.scan, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        SwitchRow(WEEKLY.pick(hindi), WEEKLY_DETAIL.pick(hindi), settings.weeklyCheckup, { v -> store.update { it.copy(weeklyCheckup = v) } }, Modifier.weight(1f))
                    }
                    Hairline(Modifier.padding(vertical = 8.dp))
                    FoldText(
                        lastWeekly?.let { LAST_WEEKLY.pick(hindi).replace("{time}", time(it, hindi)) } ?: NO_WEEKLY_YET.pick(hindi),
                        moreLabel = if (hindi) "और पढ़ें" else "More", lessLabel = if (hindi) "कम दिखाएँ" else "Less",
                    )
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.LOCK, Film.clear, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Note(PRIVACY.pick(hindi), Film.clear)
                    }
                }
            }
        }
    }
}

/**
 * One area with apps that hold it: what was counted (one line), the apps (tap to open APK Guard), the unknown count
 * if any, and what to review behind one disclosure.
 */
@Composable
private fun AreaCard(section: CheckupSection, hindi: Boolean, onOpenApp: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    with(CheckupText) {
        SectionCard(
            modifier, title = section.area.title.pick(hindi), glyph = glyph(section.area), accent = Film.warn,
            trailing = { CountBubble(section.count, hindi) },
        ) {
            Text(section.area.checks.pick(hindi), style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
            Spacer(Modifier.height(4.dp))
            section.apps.forEach { app -> AppRow(app) { onOpenApp(app.packageName) } }
            if (section.unknown > 0) KeyCaveat(UNKNOWN_FOR.pick(hindi).replace("{n}", section.unknown.toString()), color = Film.boneDim)
            LearnMore(label = WHAT_YOU_CAN_DO.pick(hindi), openLabel = if (hindi) "कम दिखाएँ" else "Show less") {
                Text(section.area.review.pick(hindi), style = MaterialTheme.typography.bodyMedium, color = Film.bone)
            }
            AutoGuardRoutes.settingsAction(section.area)?.let { action ->
                OutlineAction(OPEN_SETTINGS.pick(hindi), { openSettings(ctx, action) }, glyph = Glyph.SETTINGS)
            }
        }
    }
}

/** The area's count in a tinted circle; TalkBack hears "N apps". */
@Composable
private fun CountBubble(n: Int, hindi: Boolean) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Film.warn.copy(alpha = 0.16f)).border(1.dp, Film.warn.copy(alpha = 0.5f), CircleShape)
            .clearAndSetSemantics { contentDescription = if (hindi) "$n ऐप" else if (n == 1) "1 app" else "$n apps" },
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = MaterialTheme.typography.titleMedium, color = Film.warn)
    }
}

@Composable
private fun AppRow(app: CheckupApp, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).pressClickable(shape = RoundedCornerShape(Radii.small), onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(Radii.small))) }
            ?: Spacer(Modifier.size(36.dp).clip(RoundedCornerShape(Radii.small)).background(FilmExtra.raisedHigh))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, fontFamily = Mono, fontSize = 12.sp, color = Film.boneDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlyphIcon(Glyph.CHEVRON_RIGHT, Film.boneDim, size = 18.dp)
    }
}

/** English / Hindi as one segmented pill; each half is a radio button for TalkBack. */
@Composable
private fun LanguageToggle(hindi: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(Modifier.clip(shape).background(Film.base.copy(alpha = 0.5f)).border(1.dp, Film.line, shape)) {
        LanguageChip("English", !hindi) { onChange(false) }
        LanguageChip("हिंदी", hindi) { onChange(true) }
    }
}

@Composable
private fun LanguageChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.heightIn(min = 48.dp).clip(shape).background(if (selected) Film.scan else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Film.base else Film.bone)
    }
}

/** A faint wash of [c] from the top-left corner, fading into the card. */
private fun tintWash(c: Color): Brush = Brush.linearGradient(listOf(c.copy(alpha = 0.20f), c.copy(alpha = 0.06f), Color.Transparent))

private fun glyph(area: CheckupArea): Glyph = when (area) {
    CheckupArea.SMS -> Glyph.ASK
    CheckupArea.SCREEN_CONTROL -> Glyph.WATCHER
    CheckupArea.NOTIFICATIONS -> Glyph.BELL
    CheckupArea.DRAW_OVER_APPS -> Glyph.SAMPLE
    CheckupArea.INSTALL_APPS -> Glyph.APK
    CheckupArea.DEVICE_ADMIN -> Glyph.LOCK
    CheckupArea.OUTSIDE_STORE -> Glyph.INFO
}

private fun time(epochMs: Long, hindi: Boolean): String =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag(if (hindi) "hi" else "en"))
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Opens an Android settings screen; nothing changes until the person changes it there. */
private fun openSettings(ctx: Context, action: String) {
    runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recoverCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
