package com.dpdpxray.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.Motion
import com.dpdpxray.app.ui.theme.Radii

/*
 * Small building blocks shared by every screen: cards with a header, folding "Learn more" text, caveat callouts,
 * navigation rows and switch rows. They only change how things look and fold; every caller keeps its own text and
 * actions, so nothing is removed, only moved behind a tap.
 *
 * Text diet: a screen shows at most about two short lines of explanation by default. Longer text goes behind a
 * disclosure ([LearnMore], [FoldText], [CollapsibleCard]) that starts closed and remembers its state for as long as
 * the screen is on the back stack. Honesty and safety caveats are folded, never deleted; the one-line key caveat
 * stays visible ([KeyCaveat]).
 */

/** Size animation for anything that folds open: emphasized 250 ms, or instant with reduced motion. */
@Composable
internal fun foldSpec(): androidx.compose.animation.core.FiniteAnimationSpec<IntSize> =
    if (LocalReduceMotion.current) snap() else tween(Motion.MEDIUM, easing = Motion.emphasized)

/** Chevron turn for a disclosure: 0 closed, 180 open. */
@Composable
internal fun chevronTurn(open: Boolean): Float {
    val reduce = LocalReduceMotion.current
    val turn by animateFloatAsState(if (open) 180f else 0f, if (reduce) snap() else tween(Motion.MEDIUM, easing = Motion.emphasized), label = "chevron")
    return turn
}

/** The raised card surface every card shares: lit slightly from the top, with a brighter rim. */
fun Modifier.cardSurface(radius: Dp = Radii.card, glow: Color? = null): Modifier {
    val shape = RoundedCornerShape(radius)
    val lifted = if (glow != null) this.shadow(14.dp, shape, ambientColor = glow.copy(alpha = 0.35f), spotColor = glow.copy(alpha = 0.55f)) else this
    return lifted.clip(shape).background(FilmExtra.card).border(1.dp, FilmExtra.cardEdge, shape)
}

/** A raised card that groups one idea, with an optional glyph + title header. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    glyph: Glyph? = null,
    accent: Color = Film.scan,
    trailing: (@Composable () -> Unit)? = null,
    glow: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().cardSurface(glow = glow).padding(16.dp)) {
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

/** A glyph centred in a softly tinted circle, with a faint ring of the same colour. */
@Composable
fun IconBadge(glyph: Glyph, accent: Color, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.08f))))
            .border(1.dp, accent.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, accent, size = size * 0.55f)
    }
}

/**
 * The one disclosure row every fold uses: info glyph, label, rotating chevron, 48dp tall. TalkBack hears the label,
 * "Expanded"/"Collapsed", and "Show details"/"Hide details" as the action.
 */
@Composable
fun DisclosureRow(open: Boolean, label: String, openLabel: String, onToggle: () -> Unit, modifier: Modifier = Modifier, color: Color = Film.scan, glyph: Glyph? = Glyph.INFO) {
    val turn = chevronTurn(open)
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(Radii.small))
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide details" else "Show details", onClick = onToggle)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, color, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (open) openLabel else label, style = MaterialTheme.typography.labelLarge, color = color)
        Spacer(Modifier.width(4.dp))
        GlyphIcon(Glyph.CHEVRON_DOWN, color, Modifier.graphicsLayer { rotationZ = turn }, size = 16.dp)
    }
}

/**
 * Keeps a screen short: [label] is always visible with a chevron; the full text appears below it on tap. Use it for
 * explanations and limitations that must stay available but need not be read every time. Closed by default and
 * remembered while the screen is on the back stack.
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
    Column(modifier.fillMaxWidth().animateContentSize(foldSpec())) {
        DisclosureRow(open, label, openLabel, { open = !open })
        if (open) {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/** A sentence ends at . ! ? or the Devanagari danda, followed by a capital, quote, bracket, Hindi letter or the end. */
private val SENTENCE_END = Regex("""[.!?।](?=\s+["'(\p{Lu}ऀ-ॿ]|\s*$)""")

/**
 * First sentence of [text] (up to and including its full stop), or the whole text when it is one sentence.
 * Abbreviations such as "e.g. sm8850" do not end a sentence, and the lead is at least 20 characters long.
 */
fun leadSentence(text: String): String {
    val t = text.trim()
    val end = SENTENCE_END.findAll(t).firstOrNull { it.range.first >= 20 }?.range?.first ?: return t
    return t.substring(0, end + 1)
}

/**
 * Text on a diet: the first sentence shows (at most [maxLines] lines); "More" reveals the rest in place. Short text
 * is shown as is, with no toggle. TalkBack always reads the full text from the expanded state, and the toggle
 * announces Expanded/Collapsed.
 */
@Composable
fun FoldText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Film.boneDim,
    maxLines: Int = 2,
    moreLabel: String = "More",
    lessLabel: String = "Less",
) {
    val lead = remember(text) { leadSentence(text) }
    val multi = lead.length < text.trim().length
    var clipped by remember(text) { mutableStateOf(false) }
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize(foldSpec())) {
        if (open) {
            Text(text, style = style, color = color)
        } else {
            Text(
                if (multi) lead else text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow && !clipped) clipped = true },
            )
        }
        if (multi || clipped) DisclosureRow(open, moreLabel, lessLabel, { open = !open }, glyph = null)
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
    val turn = chevronTurn(open)
    Column(modifier.fillMaxWidth().cardSurface().animateContentSize(foldSpec())) {
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
            GlyphIcon(Glyph.CHEVRON_DOWN, Film.boneDim, Modifier.graphicsLayer { rotationZ = turn }, size = 20.dp)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/**
 * A caveat that must stay visible: tinted wash, coloured rule, short text. A longer explanation can sit behind
 * "More" in [detail]. The rule is drawn, not measured, so wrapped text is never clipped.
 */
@Composable
fun Callout(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Film.warn,
    title: String? = null,
    glyph: Glyph = Glyph.WARN,
    detail: String? = null,
    /** Show only [text]'s first sentence and fold the rest behind More (ignored when [detail] is given). */
    fold: Boolean = false,
    moreLabel: String = "More",
    lessLabel: String = "Less",
) {
    val lead = if (fold && detail == null) remember(text) { leadSentence(text) } else text
    val more = detail ?: if (fold && lead.length < text.trim().length) text.trim().substring(lead.length).trim() else null
    val shape = RoundedCornerShape(Radii.row)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0.06f))))
            .drawBehind { drawRect(color, size = Size(4.dp.toPx(), size.height)) }
            .border(1.dp, color.copy(alpha = 0.32f), shape)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        GlyphIcon(glyph, color, Modifier.padding(top = 2.dp), size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            Text(lead, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
            if (more != null) {
                var open by rememberSaveable(more) { mutableStateOf(false) }
                Column(Modifier.animateContentSize(foldSpec())) {
                    if (open) Text(more, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(top = 6.dp))
                    DisclosureRow(open, moreLabel, lessLabel, { open = !open }, color = color, glyph = null)
                }
            }
        }
    }
}

/**
 * The one-line caveat that must stay in view (for example "A lookup is an attempt to contact, not proof data was
 * sent"): a small info glyph and one line, no box. Put the longer explanation in a [LearnMore] next to it.
 */
@Composable
fun KeyCaveat(text: String, modifier: Modifier = Modifier, color: Color = Film.warn) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        GlyphIcon(Glyph.INFO, color, Modifier.padding(top = 2.dp), size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
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
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .pressClickable(shape = RoundedCornerShape(Radii.small), onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
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

/**
 * A setting with a switch. The whole row is the touch target and reads as one switch to TalkBack. A long [detail]
 * shows its first sentence (at most two lines); "More" below the row opens the full text, and also appears when a
 * single long sentence is cut off.
 */
@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val full = detail?.trim()
    val lead = full?.let { remember(it) { leadSentence(it) } }
    val multi = full != null && lead != null && lead.length < full.length
    var clipped by remember(full) { mutableStateOf(false) }
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize(foldSpec())) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Radii.small))
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Film.bone else Film.boneDim)
                if (full != null && lead != null) {
                    if (open) Text(full, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                    else Text(
                        lead, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (it.hasVisualOverflow && !clipped) clipped = true },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked, onCheckedChange = null, enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = Film.scan, checkedThumbColor = Film.base, uncheckedTrackColor = FilmExtra.raisedHigh),
            )
        }
        if (multi || clipped) DisclosureRow(open, "More", "Less", { open = !open }, glyph = null)
    }
}

/** Chips that wrap onto the next line instead of overflowing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** The short brand rule under a heading: 28dp of peach-to-rose. */
@Composable
fun GradientRule(modifier: Modifier = Modifier, width: Dp = 28.dp) {
    Box(modifier.size(width = width, height = 3.dp).clip(CircleShape).background(Brand.underline))
}

/** A heading above a group of cards, with a thin gradient rule and an optional trailing link (for example "See all"). */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleLarge, color = Film.bone, modifier = Modifier.semantics { heading() })
            GradientRule(Modifier.padding(top = 6.dp))
        }
        trailing?.invoke()
    }
}

/** An empty state on one line: a tinted glyph and a short sentence. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier, glyph: Glyph = Glyph.INFO, color: Color = Film.boneDim) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(glyph, color, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
