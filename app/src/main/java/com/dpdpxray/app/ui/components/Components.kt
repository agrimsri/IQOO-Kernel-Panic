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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
 * The app bar every screen shares. A tab root (no back button) shows a large title; a screen you pushed shows a back
 * chevron and an inline title. The title is a heading for TalkBack either way.
 */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    if (onBack == null) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            trailing()
        }
    } else {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = Film.scan, modifier = Modifier.size(30.dp))
            }
            Text(
                title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 2.dp).semantics { heading() },
            )
            trailing()
        }
    }
}

/** A heading inside a screen. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, color = Film.bone,
        modifier = modifier.padding(top = 24.dp, bottom = 10.dp).semantics { heading() },
    )
}

/**
 * A row whose meaning colour is a small dot at its leading edge (severity, category, status), on the standard card
 * surface. Tappable rows shrink slightly while pressed.
 */
@Composable
fun RuleRow(color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Radii.row)
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.pressClickable(shape = shape, onClick = onClick) else it }
            .clip(shape)
            .background(Film.raised),
    ) {
        Box(Modifier.padding(start = 16.dp, top = 21.dp).size(8.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 14.dp).weight(1f)) { content() }
    }
}

@Composable
fun StatusDot(ok: Boolean?, modifier: Modifier = Modifier) {
    val c = when (ok) { true -> Film.clear; false -> Film.leak; null -> Film.warn }
    Box(modifier.size(8.dp).clip(CircleShape).background(c))
}

/** A small label: quiet fill, text in the meaning colour, no outline. */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier.clip(shape).background(Film.fill).padding(horizontal = 8.dp, vertical = 3.dp),
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
        modifier.fillMaxWidth().clip(shape).background(Film.fill, shape).horizontalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Text(code, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp, color = Film.bone)
    }
}

/**
 * The main action of a section: solid accent, accent-contrast text, at least 52dp tall (text may wrap, never clips).
 * Shrinks slightly while pressed.
 */
@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(Radii.button)
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .pressClickable(enabled = enabled, shape = shape, onClick = onClick)
            .background(if (enabled) Film.scan else Film.fill)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = if (enabled) Film.onAccent else Film.tertiary)
    }
}

/** A secondary action: a quiet filled button, same radius as [PrimaryAction], at least 48dp tall. */
@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(Radii.button)
    Box(
        modifier.heightIn(min = 48.dp)
            .pressClickable(enabled = enabled, shape = shape, onClick = onClick)
            .background(Film.fill)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = if (enabled) Film.bone else Film.tertiary)
    }
}

/**
 * The moment the user chose, drawn as a thin rule in the phase colour. Everything above it on the timeline happened
 * before consent.
 */
@Composable
fun ConsentLine(label: String, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(color))
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
