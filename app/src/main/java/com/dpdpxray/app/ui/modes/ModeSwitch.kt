package com.dpdpxray.app.ui.modes

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.Radii

/**
 * Everyday or Developer, as two side-by-side radio tiles, plus one line on what changes. Used in More and Settings;
 * a tap switches at once (Home follows on return).
 */
@Composable
fun ModeSwitch(current: Audience, onChange: (Audience) -> Unit, modifier: Modifier = Modifier, lang: GuideLang = GuideLang.EN) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Audience.USER, Audience.DEVELOPER).forEach { a ->
                ModeTile(a, a == current, lang, Modifier.weight(1f).fillMaxHeight()) { if (a != current) onChange(a) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Note(ModeCopy.note.pick(lang))
    }
}

@Composable
private fun ModeTile(audience: Audience, selected: Boolean, lang: GuideLang, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.tile)
    val accent = if (audience == Audience.USER) Brand.coral else Film.scan
    Column(
        modifier.heightIn(min = 96.dp).clip(shape)
            .background(if (selected) accent.copy(alpha = 0.10f) else FilmExtra.raisedHigh.copy(alpha = 0.5f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) accent else FilmExtra.hairline, shape)
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
