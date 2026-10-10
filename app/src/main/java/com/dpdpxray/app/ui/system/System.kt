package com.dpdpxray.app.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.components.pressClickable
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Radii

/*
 * The building blocks the refactored screens use (UI_UX_REFACTOR_GUIDE.md section 9). They read the active palette, carry
 * no animation of their own beyond the press feedback, and never draw borders, glows or gradients. Older components in
 * ui/components keep working and are retired as screens move over.
 */

/**
 * A grouped section: an optional small title above, rows on one flat surface, an optional footnote below. Separate rows
 * with [GroupDivider]. Grouping is done with space and tone, not borders.
 */
@Composable
fun Group(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title, style = MaterialTheme.typography.labelMedium, color = Film.boneDim,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.card)).background(Film.raised), content = content)
        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
    }
}

/** A hairline between rows in a [Group], inset from the leading edge so it lines up with the row text. */
@Composable
fun GroupDivider(inset: Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(Film.line))
}

/**
 * The one row pattern: optional leading content, a title with an optional one-line subtitle, optional trailing content
 * and a chevron when it opens something. At least 56dp tall.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    titleColor: Color = Film.bone,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .let { if (onClick != null) it.pressClickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            GlyphIcon(Glyph.CHEVRON_RIGHT, Film.tertiary, size = 16.dp)
        }
    }
}

/** An 8dp status dot. The meaning is always also in words next to it. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** The one filled action on a screen: solid accent, full width, 52dp. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(Radii.button)
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .pressClickable(enabled = enabled, shape = shape, onClick = onClick)
            .background(if (enabled) Film.scan else Film.fill)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = if (enabled) Film.onAccent else Film.tertiary)
    }
}

/** A quiet filled button for the second action. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(Radii.button)
    Box(
        modifier.heightIn(min = 48.dp)
            .pressClickable(enabled = enabled, shape = shape, onClick = onClick)
            .background(Film.fill)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = if (enabled) Film.bone else Film.tertiary)
    }
}

/** A text-only action in the accent colour, with a 48dp touch target. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Film.scan) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

enum class NoticeKind { INFO, CAUTION, RISK }

/** A calm inline notice for a caveat or a state that needs attention: an icon, one sentence, an optional action. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, kind: NoticeKind = NoticeKind.INFO, action: Pair<String, () -> Unit>? = null) {
    val color = when (kind) { NoticeKind.INFO -> Film.boneDim; NoticeKind.CAUTION -> Film.warn; NoticeKind.RISK -> Film.leak }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).background(Film.raised).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlyphIcon(if (kind == NoticeKind.INFO) Glyph.INFO else Glyph.WARN, color, Modifier.padding(top = 2.dp), size = 18.dp)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
            if (action != null) TextAction(action.first, action.second, Modifier.padding(top = 2.dp))
        }
    }
}
