package com.dpdpxray.app.ui.modes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.lessLabel
import com.dpdpxray.app.ui.guide.moreLabel
import com.dpdpxray.app.ui.onboarding.OnboardingCopy
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.Motion
import com.dpdpxray.app.ui.theme.Radii

/**
 * Everyday or Developer, as two side-by-side radio tiles, plus one line on what changes. Used in More and Settings;
 * a tap switches at once (Home follows on return).
 */
@Composable
fun ModeSwitch(current: Audience, onChange: (Audience) -> Unit, modifier: Modifier = Modifier, lang: GuideLang = GuideLang.EN) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Audience.USER, Audience.DEVELOPER).forEach { a ->
                ModeTile(a, a == current, lang, Modifier.weight(1f).fillMaxHeight()) { if (a != current) onChange(a) }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Text diet: the first sentence shows; "your checks stay the same" folds behind More.
        FoldText(ModeCopy.note.pick(lang), moreLabel = lang.moreLabel, lessLabel = lang.lessLabel)
    }
}

@Composable
private fun ModeTile(audience: Audience, selected: Boolean, lang: GuideLang, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.tile)
    val accent = if (audience == Audience.USER) Brand.coral else Film.scan
    val reduce = LocalReduceMotion.current
    val fill by animateColorAsState(
        if (selected) accent.copy(alpha = 0.12f) else FilmExtra.raisedHigh.copy(alpha = 0.5f),
        if (reduce) snap() else tween(Motion.MEDIUM, easing = Motion.emphasized), label = "mode-tile",
    )
    Column(
        modifier.heightIn(min = 96.dp).clip(shape)
            .background(fill)
            .then(
                if (selected) Modifier.border(1.5.dp, if (audience == Audience.USER) Brand.horizontal else SolidColor(accent), shape)
                else Modifier.border(1.dp, FilmExtra.hairline, shape),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ModeGlyphIcon(if (audience == Audience.USER) ModeGlyph.PHONE else ModeGlyph.CODE, if (selected) accent else Film.boneDim, size = 24.dp)
            Spacer(Modifier.weight(1f))
            if (selected) GlyphIcon(Glyph.CHECK, accent, size = 18.dp)
        }
        Text(ModeCopy.name(audience).pick(lang), style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.padding(top = 8.dp))
        Text(ModeCopy.tagline(audience).pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
    }
}

