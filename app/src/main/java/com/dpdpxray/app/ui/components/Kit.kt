package com.dpdpxray.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.Radii

/*
 * Small building blocks shared by every screen: cards with a header, folding "Learn more" text, caveat callouts,
 * navigation rows and switch rows. They only change how things look and fold; every caller keeps its own text and
 * actions, so nothing is removed, only moved behind a tap.
 */

/** A raised card that groups one idea, with an optional glyph + title header. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    glyph: Glyph? = null,
    accent: Color = Film.scan,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radii.card)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Film.raised).border(1.dp, FilmExtra.hairline, shape).padding(16.dp),
    ) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (glyph != null) {
                    IconBadge(glyph, accent, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.weight(1f).semantics { heading() })
                trailing?.invoke()
            }
        }
        content()
    }
}

/** A glyph centred in a softly tinted circle. */
@Composable
fun IconBadge(glyph: Glyph, accent: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, accent, size = size * 0.55f)
    }
}

/**
 * Keeps a screen short: [label] is always visible with a chevron; the full text appears below it on tap. Use it for
 * explanations and limitations that must stay available but need not be read every time.
 */
@Composable
fun LearnMore(
    modifier: Modifier = Modifier,
    label: String = "Learn more",
    openLabel: String = "Show less",
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "learn-more")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(Radii.small))
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide details" else "Show details") { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.INFO, Film.scan, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(if (open) openLabel else label, style = MaterialTheme.typography.labelLarge, color = Film.scan)
            Spacer(Modifier.width(4.dp))
            GlyphIcon(Glyph.CHEVRON_DOWN, Film.scan, Modifier.rotate(turn), size = 16.dp)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/**
 * A card whose body folds away. The header (title, one-line summary, chevron) is a 56dp touch target; the summary
 * says what is inside, so a folded card still tells the reader what it holds.
 */
@Composable
fun CollapsibleCard(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryColor: Color = Film.boneDim,
    glyph: Glyph? = null,
    accent: Color = Film.scan,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "collapsible")
    val shape = RoundedCornerShape(Radii.card)
    Column(modifier.fillMaxWidth().clip(shape).background(Film.raised).border(1.dp, FilmExtra.hairline, shape)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Collapse $title" else "Expand $title") { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glyph != null) {
                IconBadge(glyph, accent, size = 36.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.semantics { heading() })
                if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = summaryColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            GlyphIcon(Glyph.CHEVRON_DOWN, Film.boneDim, Modifier.rotate(turn), size = 20.dp)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/** A caveat that must stay visible: tinted wash, coloured rule, short text. */
@Composable
fun Callout(text: String, modifier: Modifier = Modifier, color: Color = Film.warn, title: String? = null, glyph: Glyph = Glyph.WARN) {
    val shape = RoundedCornerShape(Radii.row)
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), shape),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            GlyphIcon(glyph, color, Modifier.padding(top = 2.dp), size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = color)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
            }
        }
    }
}

/** A tappable row that opens another screen: glyph badge, title, one line of detail, chevron. At least 64dp tall. */
@Composable
fun NavRow(
    title: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    icon: ImageVector? = null,
    accent: Color = Film.scan,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(Radii.small))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            glyph != null -> IconBadge(glyph, accent)
            icon != null -> Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
        }
        if (glyph != null || icon != null) Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Film.bone)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        GlyphIcon(Glyph.CHEVRON_RIGHT, Film.boneDim, size = 18.dp)
    }
}

/** A thin divider between rows inside a card. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(FilmExtra.hairline))
}

/** A setting with a switch. The whole row is the touch target and reads as one switch to TalkBack. */
@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Radii.small))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Film.bone else Film.boneDim)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Film.scan, checkedThumbColor = Film.base, uncheckedTrackColor = FilmExtra.raisedHigh),
        )
    }
}

/** Chips that wrap onto the next line instead of overflowing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A heading above a group of cards, with an optional trailing link (for example "See all"). */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Film.bone, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}
