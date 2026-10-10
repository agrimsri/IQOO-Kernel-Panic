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
import com.dpdpxray.app.ui.theme.Radii

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

/**
 * Speaks a guide step when read-aloud is on, without showing anything. Screens that dropped the visible banner use it so
 * people who rely on spoken guidance still hear the same step, once, in their language.
 */
@Composable
fun SpeakStep(step: GuideStep) {
    val guide = LocalGuide.current ?: return
    val speaker = guide.speaker ?: return
    val lang = guide.lang
    val spokenText = step.spoken.pick(lang)
    LaunchedEffect(step.key, guide.spoken, lang) { if (guide.spoken) speaker.say(step.key, spokenText, lang) }
    DisposableEffect(step.key, speaker) { onDispose { speaker.stop(step.key) } }
}
