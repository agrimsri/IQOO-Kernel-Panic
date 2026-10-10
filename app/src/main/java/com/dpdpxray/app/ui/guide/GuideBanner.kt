package com.dpdpxray.app.ui.guide

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.components.BreathingDot
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra

/** Guidance preferences and the speaker, provided once by MainActivity for every guided screen. */
@Stable
class GuideState(
    val spoken: Boolean,
    val lang: GuideLang,
    val speaker: GuideSpeaker?,
    val note: String?,
    val setSpoken: (Boolean) -> Unit,
    val setLang: (GuideLang) -> Unit,
)

val LocalGuide = compositionLocalOf<GuideState?> { null }

/** "More" / "Less" for fold toggles, in the guide language. */
val GuideLang.moreLabel: String get() = if (this == GuideLang.HI) "और पढ़ें" else "More"
val GuideLang.lessLabel: String get() = if (this == GuideLang.HI) "कम दिखाएँ" else "Less"

/** The current language for guide copy; English when no guide state is provided. */
@Composable
fun guideLang(): GuideLang = LocalGuide.current?.lang ?: GuideLang.EN

/**
 * "Step N of M: what to do now". Reads the step aloud when spoken guidance is on and [speak] is true (screens whose
 * steps are already read by [GuideNarrator] pass false). The speaker and language buttons live on the banner itself.
 */
@Composable
fun GuideBanner(step: GuideStep, modifier: Modifier = Modifier, speak: Boolean = true, action: Pair<String, () -> Unit>? = null) {
    val guide = LocalGuide.current
    val lang = guide?.lang ?: GuideLang.EN
    val spokenOn = guide?.spoken == true
    val title = step.title.pick(lang)
    val body = step.body.pick(lang)
    val spokenText = step.spoken.pick(lang)
    val speaker = guide?.speaker
    if (speak && speaker != null) {
        LaunchedEffect(step.key, spokenOn, lang) { if (spokenOn) speaker.say(step.key, spokenText, lang) }
        DisposableEffect(step.key, speaker) { onDispose { speaker.stop(step.key) } }
    }

    val rail: Brush = when (step.tone) {
        GuideTone.ACTION -> Brand.vertical
        GuideTone.CAUTION -> SolidColor(Film.warn)
        GuideTone.DONE -> SolidColor(Film.clear)
        GuideTone.WAIT -> SolidColor(Film.scan)
        GuideTone.INFO -> SolidColor(Film.line)
    }
    val shape = RoundedCornerShape(18.dp)
    // The rail is drawn, not measured, so wrapped text and the folded body never get clipped.
    Row(
        modifier.fillMaxWidth().clip(shape).background(BannerFill).border(1.dp, FilmExtra.cardEdge, shape)
            .drawBehind { drawRect(rail, size = Size(5.dp.toPx(), size.height)) }
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.weight(1f).padding(start = 19.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepTicks(step.index, step.total, step.tone)
                Spacer(Modifier.width(10.dp))
                Text(step.label(lang), style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
                if (step.tone == GuideTone.WAIT) {
                    Spacer(Modifier.width(8.dp))
                    PulseDot()
                }
            }
            AnimatedContent(
                targetState = title to body,
                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(140)) },
                label = "guide-step",
            ) { (t, b) ->
                Column {
                    Text(t, style = MaterialTheme.typography.titleLarge, color = Film.bone, modifier = Modifier.padding(top = 6.dp))
                    // Text diet: the first sentence shows; the rest folds behind "More" (the speaker still reads it all).
                    if (b.isNotBlank()) FoldText(b, Modifier.padding(top = 4.dp), moreLabel = lang.moreLabel, lessLabel = lang.lessLabel)
                }
            }
            if (spokenOn) guide.note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Film.warn, modifier = Modifier.padding(top = 6.dp)) }
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                PillButton(action.first, action.second)
            }
        }
        if (guide != null) {
            Column(Modifier.padding(top = 4.dp, end = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Reads this step once when tapped. Reading every step automatically is a separate choice in Settings.
                SpeakerToggle(spokenOn) { speaker?.sayNow(step.key, spokenText, lang) }
                LanguageToggle(lang) { guide.setLang(it) }
            }
        }
    }
}

/** The banner surface: one step lighter than a card, lit from the top. */
private val BannerFill = Brush.verticalGradient(listOf(Color(0xFF213B4F), FilmExtra.raisedHigh))

/** Progress as small ticks: done steps dim, the current one in its tone colour, the rest as outlines. */
@Composable
private fun StepTicks(index: Int, total: Int, tone: GuideTone) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (1..total).forEach { i ->
            val target = when {
                i < index -> Film.boneDim.copy(alpha = 0.55f)
                i == index -> when (tone) { GuideTone.ACTION -> Brand.coral; GuideTone.CAUTION -> Film.warn; GuideTone.DONE -> Film.clear; else -> Film.scan }
                else -> Film.line
            }
            val color by animateColorAsState(target, tween(300), label = "tick")
            Box(Modifier.size(width = if (i == index) 18.dp else 10.dp, height = 4.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun PulseDot() {
    BreathingDot(Film.scan, size = 7.dp)
}

@Composable
fun SpeakerToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val bg by animateColorAsState(if (on) Brand.coral.copy(alpha = 0.16f) else Film.raised.copy(alpha = 0f), tween(200), label = "speaker")
    Box(
        Modifier.size(48.dp).toggleable(value = on, role = Role.Button, onValueChange = onChange)
            .semantics {
                contentDescription = "Read this step aloud"
                stateDescription = if (on) "Every step is read automatically" else "Tap to hear this step"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            GlyphIcon(if (on) Glyph.SPEAKER_ON else Glyph.SPEAKER_OFF, if (on) Brand.coral else Film.boneDim, size = 22.dp)
        }
    }
}

@Composable
fun LanguageToggle(lang: GuideLang, onChange: (GuideLang) -> Unit) {
    val next = if (lang == GuideLang.EN) GuideLang.HI else GuideLang.EN
    Box(
        Modifier.size(48.dp).clickable(role = Role.Button) { onChange(next) }
            .semantics { contentDescription = if (lang == GuideLang.EN) "Guidance in English. Switch to Hindi" else "Guidance in Hindi. Switch to English" },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (lang == GuideLang.EN) "हि" else "EN", style = MaterialTheme.typography.labelLarge, color = Film.scan)
    }
}

/** Compact outlined action used inside banners and panels; 48dp tall. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape).border(1.dp, Film.scan.copy(alpha = 0.7f), shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Film.scan)
    }
}
