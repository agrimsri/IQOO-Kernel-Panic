package com.dpdpxray.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.Mono
import com.dpdpxray.app.ui.theme.Radii

/**
 * The app bar every inner screen shares: 64dp tall, a 48dp back button when there is somewhere to go back to, and the
 * title as a heading for TalkBack. Tab screens pass `onBack = null`, so their title lines up with the content.
 */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Film.bone)
            }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(
            title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/** A heading inside a screen, with a thin brand rule under it. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 24.dp, bottom = 10.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.semantics { heading() })
        GradientRule(Modifier.padding(top = 6.dp), width = 22.dp)
    }
}

/**
 * A row with a coloured rule on its left edge: the rule colour is the meaning (severity, category, status). A faint
 * wash of the same colour runs in from the rule. Tappable rows shrink slightly while pressed.
 */
@Composable
fun RuleRow(color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Radii.row)
    val wash = remember(color) { Brush.horizontalGradient(0f to color.copy(alpha = 0.10f), 0.35f to Color.Transparent) }
    // The left rule is drawn, not measured: IntrinsicSize.Min under-counts wrapped chip rows (FlowRow) and clipped cards.
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.pressClickable(shape = shape, onClick = onClick) else it }
            .clip(shape)
            .background(FilmExtra.card)
            .background(wash)
            .drawBehind { drawRect(color, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
            .border(1.dp, FilmExtra.cardEdge, shape),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp).weight(1f)) { content() }
    }
}

@Composable
fun StatusDot(ok: Boolean?, modifier: Modifier = Modifier) {
    val c = when (ok) { true -> Film.clear; false -> Film.leak; null -> Film.warn }
    Box(modifier.size(10.dp).clip(CircleShape).background(c))
}

/** A small label chip: tinted fill, soft outline, text in the meaning colour. */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier.clip(shape).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.40f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun HostText(host: String, color: Color = Film.bone) {
    Text(host, fontFamily = Mono, fontSize = 13.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radii.small)
    Box(
        modifier.fillMaxWidth().clip(shape).background(Color(0xFF0B1722))
            .border(1.dp, Film.line, shape).horizontalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Text(code, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp, color = Film.bone)
    }
}

/** Filled scan colour, lit from the top, for the main action of a section. */
private val ScanFill = Brush.verticalGradient(listOf(Color(0xFF9FE2F0), Film.scan))

/**
 * The main action of a section: filled scan gradient, dark text, at least 56dp tall (text may wrap, never clips).
 * Shrinks slightly while pressed.
 */
@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(Radii.button)
    Box(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .pressClickable(enabled = enabled, shape = shape, onClick = onClick)
            .background(if (enabled) ScanFill else SolidColor(Film.line))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = if (enabled) Film.base else Film.boneDim)
    }
}

/** A secondary action: outlined, light text, same corner radius as [PrimaryAction], at least 52dp tall. */
@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(Radii.button)
    Box(
        modifier.heightIn(min = 52.dp)
            .pressClickable(enabled = enabled, shape = shape, onClick = onClick)
            .background(if (enabled) Film.bone.copy(alpha = 0.04f) else Color.Transparent)
            .border(1.dp, if (enabled) Film.boneDim.copy(alpha = 0.55f) else Film.line, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = if (enabled) Film.bone else Film.boneDim)
    }
}

/**
 * The Consent Line: the signature element. A glowing horizontal rule marking the moment the user chose.
 * Everything above it on the timeline happened before consent.
 */
@Composable
fun ConsentLine(label: String, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(
            Modifier.fillMaxWidth().height(3.dp).background(
                Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color, color, color.copy(alpha = 0f))),
            ),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Note(text: String, color: Color = Film.boneDim) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
fun Spread(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { content() }
}
