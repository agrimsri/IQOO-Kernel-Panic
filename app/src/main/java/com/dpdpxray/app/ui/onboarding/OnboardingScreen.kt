package com.dpdpxray.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.LinkAction
import com.dpdpxray.app.ui.guide.LocalGuide
import com.dpdpxray.app.ui.modes.ModeGlyph
import com.dpdpxray.app.ui.modes.ModeGlyphIcon
import com.dpdpxray.app.ui.theme.ArchivoDisplay
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalScale
import kotlinx.coroutines.launch

/**
 * First-run onboarding: five short pages drawn in code (no image files). Skip is always visible; page two asks how the
 * person will use TraceVeil (everyday or developer), and the last page offers three safe first steps. Spoken guidance
 * and its language can be switched on here, before anything else.
 *
 * [audience] is the mode to show as chosen (null on first run, so neither door is pre-picked); [onAudience] saves a
 * pick at once. Skipping without a pick keeps the stored default, developer mode.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onSample: () -> Unit,
    onDemo: () -> Unit,
    onApk: (() -> Unit)?,
    audience: Audience? = null,
    onAudience: (Audience) -> Unit = {},
) {
    val guide = LocalGuide.current
    val lang = guide?.lang ?: GuideLang.EN
    var picked by rememberSaveable { mutableStateOf(audience) }
    val pages = OnboardingCopy.pagesFor(picked ?: Audience.DEFAULT)
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    val last = page == pages.lastIndex

    BackHandler(enabled = page > 0) { scope.launch { pager.animateScrollToPage(page - 1) } }

    val speaker = guide?.speaker
    val spokenOn = guide?.spoken == true
    if (speaker != null) {
        val key = "onboarding.${pages[page].key}"
        val text = pages[page].spoken.pick(lang)
        LaunchedEffect(key, spokenOn, lang) { if (spokenOn) speaker.say(key, text, lang) }
        DisposableEffect(key, speaker) { onDispose { speaker.stop(key) } }
    }

    Column(Modifier.fillMaxSize().background(Film.base)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressSegments(page, pages.size, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            LinkAction(OnboardingCopy.skip.pick(lang), onDone, color = Film.boneDim)
        }
        if (guide != null) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    text = OnboardingCopy.readAloud.pick(lang), glyph = if (spokenOn) Glyph.SPEAKER_ON else Glyph.SPEAKER_OFF,
                    on = spokenOn, onToggle = { guide.setSpoken(it) }, description = "Spoken guidance",
                )
                Pill(
                    text = if (lang == GuideLang.EN) "हिन्दी" else "English", glyph = null, on = lang == GuideLang.HI,
                    onToggle = { guide.setLang(if (it) GuideLang.HI else GuideLang.EN) }, description = "Hindi guidance",
                )
            }
        }

        // The mode page waits for a pick (no swiping past it); Skip (top right) still leaves with developer mode.
        val waitingForPick = pages[page].key == "audience" && picked == null
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = !waitingForPick) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(12.dp))
                when (p.key) {
                    "promise" -> PromiseArt()
                    "how" -> HowItWorksArt(lang)
                    else -> {}
                }
                Text(
                    p.title.pick(lang),
                    style = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(700), fontSize = LocalScale.current.sp(34), lineHeight = LocalScale.current.sp(38)),
                    color = Film.bone,
                    modifier = Modifier.padding(top = 20.dp).semantics { heading() },
                )
                Text(p.body.pick(lang), style = MaterialTheme.typography.bodyLarge, color = Film.boneDim, modifier = Modifier.padding(top = 10.dp))
                when (p.key) {
                    "audience" -> AudienceChoices(lang, picked) { a -> picked = a; onAudience(a) }
                    "needs" -> Needs(lang, OnboardingCopy.needsFor(picked ?: Audience.DEFAULT))
                    "try" -> TryChoices(lang, picked ?: Audience.DEFAULT, onSample, onDemo, onApk)
                    else -> {}
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) LinkAction(OnboardingCopy.back.pick(lang), { scope.launch { pager.animateScrollToPage(page - 1) } }, color = Film.boneDim)
            Spacer(Modifier.weight(1f))
            GradientAction(
                when { last -> OnboardingCopy.done; waitingForPick -> OnboardingCopy.pickOne; else -> OnboardingCopy.next }.pick(lang),
                { if (last) onDone() else scope.launch { pager.animateScrollToPage(page + 1) } },
                Modifier.width(184.dp),
                enabled = !waitingForPick,
            )
        }
    }
}

/** One segment per page; the current one stretches and takes the brand gradient. */
@Composable
private fun ProgressSegments(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier.semantics { contentDescription = "Page ${current + 1} of $total" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (0 until total).forEach { i ->
            val w by animateDpAsState(if (i == current) 34.dp else 10.dp, tween(260), label = "seg")
            val brush: Brush = when {
                i == current -> Brand.horizontal
                i < current -> SolidColor(Film.boneDim.copy(alpha = 0.6f))
                else -> SolidColor(Film.line)
            }
            Box(Modifier.size(width = w, height = 6.dp).clip(CircleShape).background(brush))
        }
    }
}

@Composable
private fun Pill(text: String, glyph: Glyph?, on: Boolean, onToggle: (Boolean) -> Unit, description: String) {
    val shape = RoundedCornerShape(24.dp)
    val border by animateColorAsState(if (on) Brand.coral else Film.line, tween(200), label = "pill")
    Row(
        Modifier.heightIn(min = 48.dp).clip(shape).border(1.dp, border, shape)
            .background(if (on) Brand.coral.copy(alpha = 0.12f) else Color.Transparent)
            .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
            .semantics { contentDescription = description; stateDescription = if (on) "On" else "Off" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, if (on) Brand.coral else Film.boneDim, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) Film.bone else Film.boneDim)
    }
}

/**
 * The two front doors as big radio cards. The picked one takes the brand outline and a check; nothing is pre-picked
 * on first run.
 */
@Composable
private fun AudienceChoices(lang: GuideLang, picked: Audience?, onPick: (Audience) -> Unit) {
    Column(Modifier.padding(top = 20.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OnboardingCopy.audiences.forEach { option ->
            val selected = option.audience == picked
            val shape = RoundedCornerShape(20.dp)
            val accent = if (option.audience == Audience.USER) Brand.coral else Film.scan
            Row(
                Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(shape)
                    .background(if (selected) accent.copy(alpha = 0.10f) else Film.raised)
                    .border(if (selected) 2.dp else 1.dp, if (selected) Brand.horizontal else SolidColor(FilmExtra.hairline), shape)
                    .selectable(selected = selected, role = Role.RadioButton) { onPick(option.audience) }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    ModeGlyphIcon(if (option.audience == Audience.USER) ModeGlyph.PHONE else ModeGlyph.CODE, accent, size = 30.dp)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(option.title.pick(lang), style = MaterialTheme.typography.titleLarge, color = Film.bone)
                    Text(option.tagline.pick(lang), style = MaterialTheme.typography.labelLarge, color = accent, modifier = Modifier.padding(top = 2.dp))
                    Text(option.detail.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.width(10.dp))
                val ring by animateColorAsState(if (selected) Brand.coral else Film.line, tween(200), label = "pick")
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(if (selected) Brand.coral else Color.Transparent).border(1.5.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) GlyphIcon(Glyph.CHECK, Film.base, size = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun Needs(lang: GuideLang, needs: List<OnboardingNeed>) {
    val glyphs = mapOf("vpn" to Glyph.VPN, "watcher" to Glyph.WATCHER, "shizuku" to Glyph.RESET)
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        needs.forEach { need ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Film.raised).padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val accent = if (need.required) Film.scan else Film.boneDim
                Box(Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                    GlyphIcon(glyphs.getValue(need.key), accent, size = 24.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(need.title.pick(lang), style = MaterialTheme.typography.titleMedium, color = Film.bone)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (if (need.required) OnboardingCopy.required else OnboardingCopy.optional).pick(lang),
                            style = MaterialTheme.typography.labelMedium, color = if (need.required) Brand.peach else Film.boneDim,
                        )
                    }
                    Text(need.body.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.LOCK, Film.clear, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(OnboardingCopy.privacy.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.clear)
        }
    }
}

@Composable
private fun TryChoices(lang: GuideLang, audience: Audience, onSample: () -> Unit, onDemo: () -> Unit, onApk: (() -> Unit)?) {
    val shown = OnboardingCopy.choicesFor(audience).mapNotNull { c ->
        when (c.key) {
            OnboardingCopy.sample.key -> Triple(c, Glyph.SAMPLE, onSample)
            OnboardingCopy.demo.key -> Triple(c, Glyph.PLAY, onDemo)
            else -> onApk?.let { Triple(c, Glyph.APK, it) }
        }
    }
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shown.forEachIndexed { i, (choice, glyph, onClick) -> Choice(choice, glyph, lang, highlight = i == 0, onClick = onClick) }
    }
}

@Composable
private fun Choice(c: OnboardingChoice, glyph: Glyph, lang: GuideLang, highlight: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(shape).background(Film.raised)
            .border(if (highlight) 1.5.dp else 1.dp, if (highlight) Brand.horizontal else SolidColor(FilmExtra.hairline), shape)
            .clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = if (highlight) Brand.coral else Film.scan
        Box(Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, accent, size = 22.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title.pick(lang), style = MaterialTheme.typography.titleMedium, color = Film.bone)
            Text(c.detail.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
    }
}
